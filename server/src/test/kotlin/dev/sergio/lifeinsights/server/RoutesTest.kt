package dev.sergio.lifeinsights.server

import dev.sergio.lifeinsights.sync.InMemoryRowStore
import dev.sergio.lifeinsights.sync.SyncStore
import dev.sergio.lifeinsights.sync.ChangeSet
import dev.sergio.lifeinsights.sync.PullResponse
import dev.sergio.lifeinsights.sync.PushRequest
import dev.sergio.lifeinsights.sync.PushResponse
import dev.sergio.lifeinsights.sync.SyncCheckIn
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    private val token = "a-token-long-enough-to-be-accepted-here"
    private val json = Json { ignoreUnknownKeys = true }

    private fun checkIn(uid: String, updatedAtUtc: Long) = SyncCheckIn(
        uid = uid,
        timestampUtc = 1_700_000_000_000,
        zoneId = "Europe/Madrid",
        localDate = 20_000,
        mood = 1,
        energy = -1,
        updatedAtUtc = updatedAtUtc,
    )

    private fun test(body: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { syncModule(SyncStore(InMemoryRowStore()), token) }
            body()
        }

    @Test
    fun `health needs no token`() = test {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `sync endpoints reject a missing token`() = test {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/pull").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/push").status)
    }

    @Test
    fun `sync endpoints reject a wrong token`() = test {
        val response = client.get("/v1/sync/pull") {
            header(HttpHeaders.Authorization, "Bearer not-the-right-token-but-the-right-length")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a token that is a prefix of the real one is still rejected`() = test {
        val response = client.get("/v1/sync/pull") {
            header(HttpHeaders.Authorization, "Bearer ${token.dropLast(1)}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a pushed row can be pulled back`() = test {
        val push = client.post("/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    PushRequest(
                        deviceId = "phone-1",
                        changes = ChangeSet(checkIns = listOf(checkIn("a", 10))),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, push.status)
        assertEquals(1, json.decodeFromString<PushResponse>(push.bodyAsText()).applied)

        val pull = client.get("/v1/sync/pull?since=0") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, pull.status)
        val body = json.decodeFromString<PullResponse>(pull.bodyAsText())
        assertEquals("a", body.changes.checkIns.single().uid)
        assertFalse(body.hasMore)
    }

    @Test
    fun `an oversized push is refused rather than opening a huge transaction`() = test {
        val tooMany = (0..MAX_PUSH_ROWS).map { checkIn("c$it", 10) }
        val response = client.post("/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    PushRequest(deviceId = "phone-1", changes = ChangeSet(checkIns = tooMany)),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a negative cursor is refused`() = test {
        val response = client.get("/v1/sync/pull?since=-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `an absurd limit is clamped rather than honoured`() = test {
        client.post("/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    PushRequest(
                        deviceId = "phone-1",
                        changes = ChangeSet(checkIns = (0 until 600).map { checkIn("c$it", 10) }),
                    ),
                ),
            )
        }

        val response = client.get("/v1/sync/pull?since=0&limit=100000") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = json.decodeFromString<PullResponse>(response.bodyAsText())
        assertEquals(MAX_PULL_LIMIT, body.changes.size)
        assertTrue(body.hasMore)
    }

    @Test
    fun `a malformed body is a bad request, not a crash`() = test {
        val response = client.post("/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertTrue(
            "expected a 4xx, got ${response.status}",
            response.status.value in 400..499,
        )
    }
}
