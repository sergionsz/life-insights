package dev.sergio.lifeinsights.server

import dev.sergio.lifeinsights.sync.ChangeSet
import dev.sergio.lifeinsights.sync.PullResponse
import dev.sergio.lifeinsights.sync.PushRequest
import dev.sergio.lifeinsights.sync.PushResponse
import dev.sergio.lifeinsights.sync.SyncCheckIn
import dev.sergio.lifeinsights.sync.SyncStatus
import io.ktor.server.engine.EmbeddedServer
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/**
 * The server started for real: Netty bound to a socket, Postgres behind it, requests over HTTP.
 *
 * Everything else in this module tests a piece. [RoutesTest] drives routing through Ktor's test
 * harness, which never opens a socket, and [PostgresRowStoreTest] drives the SQL with no server at
 * all. Neither would notice if the thing simply failed to come up, which is the first way a deploy
 * goes wrong and the only one that cannot be diagnosed from the outside.
 */
class ServerBootTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun request(
        path: String,
        method: String = "GET",
        token: String? = TOKEN,
        body: String? = null,
    ): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port$path").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = try {
            (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } catch (_: IOException) {
            ""
        }
        connection.disconnect()
        return status to text
    }

    @Test
    fun `the server comes up and answers a health check`() {
        val (status, body) = request("/health", token = null)
        assertEquals(200, status)
        assertEquals("ok", body)
    }

    @Test
    fun `a check-in survives a real round trip over http`() {
        val row = SyncCheckIn(
            uid = "3f1a0d2c-0000-4000-8000-00000000beef",
            timestampUtc = 1_700_000_000_000,
            zoneId = "Europe/Madrid",
            localDate = 20_123,
            mood = -3,
            energy = 2,
            note = "unicode and punctuation: cafe, 21:00, \"quoted\"",
            tags = listOf("caffeine", "social contact"),
            updatedAtUtc = 1_700_000_100_000,
        )

        val (pushStatus, pushBody) = request(
            "/v1/sync/push",
            method = "POST",
            body = json.encodeToString(
                PushRequest(deviceId = "boot-test", changes = ChangeSet(checkIns = listOf(row))),
            ),
        )
        assertEquals(200, pushStatus)
        assertEquals(1, json.decodeFromString<PushResponse>(pushBody).applied)

        val (pullStatus, pullBody) = request("/v1/sync/pull?since=0&limit=10")
        assertEquals(200, pullStatus)
        val pulled = json.decodeFromString<PullResponse>(pullBody).changes.checkIns.single()
        assertEquals("every field survived the wire", row, pulled)

        val (_, statusBody) = request("/v1/sync/status")
        assertTrue(json.decodeFromString<SyncStatus>(statusBody).checkIns >= 1)
    }

    @Test
    fun `a request without a token is refused over the wire too`() {
        assertEquals(401, request("/v1/sync/status", token = null).first)
        assertEquals(401, request("/v1/sync/status", token = "wrong-token-of-a-plausible-length").first)
    }

    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var server: EmbeddedServer<*, *>
        private var port = 0
        private const val TOKEN = "a-token-long-enough-to-be-accepted-here"

        @BeforeClass
        @JvmStatic
        fun startServer() {
            postgres = EmbeddedPostgres.start()
            // Migrations run against a database that has never seen this schema, which is the state
            // a first deploy is in and the one the migration runner has to handle.
            Database.migrate(postgres.postgresDatabase)

            port = ServerSocket(0).use { it.localPort }
            server = buildServer(postgres.postgresDatabase, TOKEN, port)
            server.start(wait = false)
            awaitReady()
        }

        /** Netty binds asynchronously, so the first request can otherwise beat the listener. */
        private fun awaitReady() {
            repeat(100) {
                try {
                    java.net.Socket("127.0.0.1", port).close()
                    return
                } catch (_: IOException) {
                    Thread.sleep(100)
                }
            }
            error("the server did not start listening on port $port")
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            postgres.close()
        }
    }
}
