package dev.sergio.lifeinsights.server

import dev.sergio.lifeinsights.sync.SyncStore
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("dev.sergio.lifeinsights.server")

fun main() {
    val config = try {
        Config.fromEnvironment()
    } catch (e: IllegalArgumentException) {
        // Refusing to start beats starting without a token and quietly serving someone's data to
        // anyone who finds the host.
        log.error("Cannot start: {}", e.message)
        return
    }

    val dataSource = Database.dataSource(config)
    Database.migrate(dataSource)
    log.info("Schema is up to date; listening on port {}", config.port)

    buildServer(dataSource, config.syncToken, config.port).start(wait = true)
}

/**
 * Split out from [main] so a test can start the real server on a real socket.
 *
 * Everything above this line reads the environment and exits; everything below is what actually
 * serves requests. Without the seam, the only way to find out whether the server comes up at all is
 * to deploy it.
 */
fun buildServer(
    dataSource: DataSource,
    token: String,
    port: Int,
): EmbeddedServer<*, *> = embeddedServer(Netty, port = port, host = "0.0.0.0") {
    syncModule(SyncStore(PostgresRowStore(dataSource)), token)
}
