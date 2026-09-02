package dev.sergio.lifeinsights.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

/** Where and how to reach the server. Read fresh on every sync, since Settings can change it. */
data class SyncTarget(val baseUrl: String, val token: String) {
    /** Trailing slashes are easy to paste in and would otherwise produce a double slash. */
    val normalisedUrl: String get() = baseUrl.trim().trimEnd('/')

    val isConfigured: Boolean get() = normalisedUrl.isNotEmpty() && token.isNotBlank()
}

/** A failure worth showing to the user, with a message written for one. */
class SyncFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

class SyncApi(private val client: HttpClient = defaultClient()) {

    suspend fun status(target: SyncTarget): SyncStatus = request {
        client.get("${target.normalisedUrl}/v1/sync/status") {
            header(HttpHeaders.Authorization, "Bearer ${target.token}")
        }
    }

    suspend fun pull(target: SyncTarget, since: Long, limit: Int): PullResponse = request {
        client.get("${target.normalisedUrl}/v1/sync/pull?since=$since&limit=$limit") {
            header(HttpHeaders.Authorization, "Bearer ${target.token}")
        }
    }

    suspend fun push(target: SyncTarget, body: PushRequest): PushResponse = request {
        client.post("${target.normalisedUrl}/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer ${target.token}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /**
     * Turns every way a call can go wrong into a [SyncFailure] carrying a sentence a person can
     * act on.
     *
     * The status codes are separated because they mean genuinely different things to the user: a
     * 401 is a token to fix, a 404 usually means the address points at something that is not this
     * server, and a 5xx is the server's problem and worth simply retrying later.
     */
    private suspend inline fun <reified T> request(call: () -> HttpResponse): T {
        val response = try {
            call()
        } catch (e: IOException) {
            throw SyncFailure("Could not reach the server. Check the address and your connection.", e)
        }

        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.Unauthorized ->
                throw SyncFailure("The server rejected the sync token.")
            response.status == HttpStatusCode.NotFound ->
                throw SyncFailure("No sync server at that address.")
            response.status.value >= 500 ->
                throw SyncFailure("The server had a problem (${response.status.value}). It will retry.")
            else -> throw SyncFailure("The server refused the request (${response.status.value}).")
        }

        return try {
            response.body()
        } catch (e: Exception) {
            // A body that will not parse usually means the address reached something else entirely,
            // a captive portal or a proxy's error page rather than this server.
            throw SyncFailure("Unexpected reply from the server.", e)
        }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                // The phone may be newer or older than the server; neither should fall over because
                // the other knows a field it does not.
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }
}
