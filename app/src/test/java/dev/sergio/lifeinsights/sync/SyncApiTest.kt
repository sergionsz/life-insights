package dev.sergio.lifeinsights.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncApiTest {

    private val target = SyncTarget("https://insights.example.com", "a-token")

    private fun apiReturning(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        record: MutableList<String>? = null,
    ): SyncApi {
        val engine = MockEngine { request ->
            record?.add(request.url.toString())
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        return SyncApi(
            HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )
    }

    private fun failure(block: suspend () -> Unit): String = try {
        kotlinx.coroutines.runBlocking { block() }
        throw AssertionError("expected a SyncFailure")
    } catch (e: SyncFailure) {
        e.message.orEmpty()
    }

    /**
     * The status codes mean different things to the person holding the phone, and the messages have
     * to say which. "Sync failed" for all of them would leave a wrong token indistinguishable from
     * a server that is merely down, and only one of those is worth going and fixing.
     */
    @Test
    fun `a rejected token says so`() {
        val api = apiReturning(HttpStatusCode.Unauthorized, """{"error":"bad or missing token"}""")
        assertTrue(failure { api.status(target) }.contains("token"))
    }

    @Test
    fun `an address that is not a sync server says so`() {
        val api = apiReturning(HttpStatusCode.NotFound, "not found")
        assertTrue(failure { api.status(target) }.contains("No sync server"))
    }

    @Test
    fun `a server fault is reported as the server's problem`() {
        val api = apiReturning(HttpStatusCode.InternalServerError, """{"error":"internal error"}""")
        assertTrue(failure { api.status(target) }.contains("server had a problem"))
    }

    @Test
    fun `an unreachable server is reported as a connection problem`() {
        val api = SyncApi(
            HttpClient(MockEngine { throw IOException("no route to host") }) {
                expectSuccess = false
                install(ContentNegotiation) { json() }
            },
        )
        assertTrue(failure { api.status(target) }.contains("Could not reach"))
    }

    /**
     * A captive portal or a proxy answers 200 with an HTML page. Without this the failure surfaces
     * as a serialisation exception, which tells the user nothing.
     */
    @Test
    fun `a reply that is not the expected shape is reported plainly`() {
        val api = apiReturning(HttpStatusCode.OK, "<html>Sign in to use this WiFi</html>")
        assertTrue(failure { api.status(target) }.contains("Unexpected reply"))
    }

    @Test
    fun `a successful pull is decoded`() = runTest {
        val api = apiReturning(
            HttpStatusCode.OK,
            """{"changes":{"checkIns":[],"dailyMetrics":[],"tags":[]},"nextSince":7,"hasMore":false}""",
        )
        val response = api.pull(target, since = 0, limit = 200)
        assertEquals(7L, response.nextSince)
        assertFalse(response.hasMore)
        assertTrue(response.changes.isEmpty)
    }

    /** A pasted address usually keeps its trailing slash, and a double slash is a 404. */
    @Test
    fun `a trailing slash in the address does not become a double slash`() = runTest {
        val urls = mutableListOf<String>()
        val api = apiReturning(
            HttpStatusCode.OK,
            """{"serverSeq":3,"checkIns":1,"dailyMetrics":1,"tags":1}""",
            record = urls,
        )
        api.status(SyncTarget("https://insights.example.com/", "a-token"))
        assertEquals("https://insights.example.com/v1/sync/status", urls.single())
    }

    @Test
    fun `a target is only configured once both the address and the token are set`() {
        assertFalse(SyncTarget("", "token").isConfigured)
        assertFalse(SyncTarget("https://h", "").isConfigured)
        assertFalse(SyncTarget("https://h", "   ").isConfigured)
        assertTrue(SyncTarget("https://h", "token").isConfigured)
    }
}
