package dev.sergio.lifeinsights.server

import java.net.URI

/**
 * Everything the server needs to start, read from the environment.
 *
 * Nothing here has a default that would let the server come up in an insecure state. In particular
 * there is no fallback token: a server that generated one, or shipped with a known one, would sit
 * on the public internet holding someone's mood and sleep history.
 */
data class Config(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String?,
    val dbPassword: String?,
    val syncToken: String,
) {
    companion object {

        const val MIN_TOKEN_LENGTH = 32

        fun fromEnvironment(env: Map<String, String> = System.getenv()): Config {
            val token = env["SYNC_TOKEN"]?.trim().orEmpty()
            require(token.isNotEmpty()) {
                "SYNC_TOKEN is not set. Generate one with: openssl rand -base64 32"
            }
            // Length is the only thing that can be checked here, and a short token on a public
            // host is the difference between "private" and "whoever tries a few thousand guesses".
            require(token.length >= MIN_TOKEN_LENGTH) {
                "SYNC_TOKEN must be at least $MIN_TOKEN_LENGTH characters; " +
                    "generate one with: openssl rand -base64 32"
            }

            val rawUrl = env["DATABASE_URL"]?.trim().orEmpty()
            require(rawUrl.isNotEmpty()) { "DATABASE_URL is not set" }
            val parsed = parseDatabaseUrl(rawUrl)

            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                jdbcUrl = parsed.jdbcUrl,
                dbUser = env["DATABASE_USER"] ?: parsed.user,
                dbPassword = env["DATABASE_PASSWORD"] ?: parsed.password,
                syncToken = token,
            )
        }

        data class ParsedDatabaseUrl(
            val jdbcUrl: String,
            val user: String?,
            val password: String?,
        )

        /**
         * Accepts both the JDBC form and the `postgres://user:pass@host/db` form.
         *
         * Managed hosts (Fly, Railway, Render, Heroku) all hand out the second one, and the JDBC
         * driver rejects it outright. Converting here rather than asking for the URL to be
         * rewritten by hand means `DATABASE_URL` can be wired straight through from whatever the
         * host provides.
         */
        fun parseDatabaseUrl(url: String): ParsedDatabaseUrl {
            if (url.startsWith("jdbc:")) return ParsedDatabaseUrl(url, null, null)

            require(url.startsWith("postgres://") || url.startsWith("postgresql://")) {
                "DATABASE_URL must start with jdbc:, postgres:// or postgresql://"
            }

            val uri = URI(url)
            val userInfo = uri.userInfo?.split(":", limit = 2).orEmpty()
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val query = uri.query?.let { "?$it" }.orEmpty()

            return ParsedDatabaseUrl(
                jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.path}$query",
                user = userInfo.getOrNull(0)?.let(::decode),
                password = userInfo.getOrNull(1)?.let(::decode),
            )
        }

        private fun decode(value: String): String =
            java.net.URLDecoder.decode(value, Charsets.UTF_8)
    }
}
