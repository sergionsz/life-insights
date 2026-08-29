package dev.sergio.lifeinsights.server

import dev.sergio.lifeinsights.sync.SyncStore
import dev.sergio.lifeinsights.sync.PushRequest
import dev.sergio.lifeinsights.sync.SyncError
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException as KtorBadRequest
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.security.MessageDigest

/** Rows accepted in one push. Bounds both the request body and the size of a write transaction. */
const val MAX_PUSH_ROWS = 5_000

/** Rows returned by one pull. The client follows `hasMore` until it is caught up. */
const val MAX_PULL_LIMIT = 500
const val DEFAULT_PULL_LIMIT = 200

fun Application.syncModule(store: SyncStore, token: String) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(CallLogging) {
        level = Level.INFO
        // The path and status are useful; a body holding someone's mood history is not something to
        // write into logs that a hosting provider retains and ships elsewhere.
        format { call -> "${call.request.local.method.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }

    install(StatusPages) {
        exception<BadRequest> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, SyncError(cause.message ?: "bad request"))
        }
        // A body that will not parse is the client's problem, and it has to be told so. Left to the
        // catch-all below it became a 500, which a client is right to read as "the server is
        // broken, try again later" and retry forever, while the server logged a stack trace for
        // every attempt.
        exception<KtorBadRequest> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, SyncError("could not parse the request body"))
        }
        exception<Throwable> { call, cause ->
            // Log the detail, return none: an error message from a database driver can quote the
            // row that caused it.
            call.application.log.error("Unhandled failure", cause)
            call.respond(HttpStatusCode.InternalServerError, SyncError("internal error"))
        }
    }

    routing {
        // Unauthenticated on purpose: platform health checks run before any secret is configured,
        // and it reveals nothing beyond the fact that a server is listening.
        get("/health") {
            call.respondText("ok")
        }

        route("/v1/sync") {
            get("/status") {
                if (!call.authorised(token)) return@get
                call.respond(store.status())
            }

            get("/pull") {
                if (!call.authorised(token)) return@get
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                if (since < 0) throw BadRequest("since must not be negative")
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PULL_LIMIT)
                    .coerceIn(1, MAX_PULL_LIMIT)
                call.respond(store.pull(since, limit))
            }

            post("/push") {
                if (!call.authorised(token)) return@post
                val request = call.receive<PushRequest>()
                if (request.changes.size > MAX_PUSH_ROWS) {
                    throw BadRequest("push holds ${request.changes.size} rows, limit is $MAX_PUSH_ROWS")
                }
                call.respond(store.push(request.changes))
            }
        }
    }
}

class BadRequest(message: String) : RuntimeException(message)

/**
 * Checks the bearer token, responding 401 and returning false when it does not match.
 *
 * The comparison is constant-time. A plain `==` on strings stops at the first differing byte, and
 * the timing difference is enough to recover a token one character at a time given enough requests,
 * which an unattended server on the public internet is well placed to supply.
 */
private suspend fun ApplicationCall.authorised(expected: String): Boolean {
    val header = request.headers["Authorization"].orEmpty()
    val presented = header.removePrefix("Bearer ").trim()
    val matches = MessageDigest.isEqual(
        presented.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
    if (!matches) {
        respond(HttpStatusCode.Unauthorized, SyncError("bad or missing token"))
        return false
    }
    return true
}

private fun io.ktor.server.request.ApplicationRequest.path(): String = local.uri.substringBefore('?')
