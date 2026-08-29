package dev.sergio.lifeinsights.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    private val goodToken = "0123456789012345678901234567890123456789"

    private fun env(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    /**
     * Every managed host hands out the `postgres://` form, and the JDBC driver rejects it. Getting
     * this wrong means the server starts fine locally and dies on deploy with a URL error.
     */
    @Test
    fun `a hosting provider's database url is converted to jdbc`() {
        val parsed = Config.parseDatabaseUrl("postgres://bob:hunter2@db.example.com:5432/insights")
        assertEquals("jdbc:postgresql://db.example.com:5432/insights", parsed.jdbcUrl)
        assertEquals("bob", parsed.user)
        assertEquals("hunter2", parsed.password)
    }

    @Test
    fun `the postgresql scheme is accepted too`() {
        val parsed = Config.parseDatabaseUrl("postgresql://db.example.com/insights")
        assertEquals("jdbc:postgresql://db.example.com/insights", parsed.jdbcUrl)
        assertNull(parsed.user)
    }

    @Test
    fun `query parameters such as sslmode survive the conversion`() {
        val parsed = Config.parseDatabaseUrl("postgres://h/insights?sslmode=require")
        assertEquals("jdbc:postgresql://h/insights?sslmode=require", parsed.jdbcUrl)
    }

    /** Passwords from a generated URL are percent-encoded, and the driver needs the real bytes. */
    @Test
    fun `an escaped password is decoded`() {
        val parsed = Config.parseDatabaseUrl("postgres://bob:p%40ss%3Aword@h/insights")
        assertEquals("p@ss:word", parsed.password)
    }

    @Test
    fun `a jdbc url is left alone`() {
        val parsed = Config.parseDatabaseUrl("jdbc:postgresql://localhost:5432/insights")
        assertEquals("jdbc:postgresql://localhost:5432/insights", parsed.jdbcUrl)
    }

    @Test
    fun `an unrecognised scheme is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            Config.parseDatabaseUrl("mysql://h/insights")
        }
    }

    /**
     * The server holds mood, sleep and phone-usage history on a public host. Refusing to start is
     * the correct response to a missing or guessable token; a default would be worse than useless.
     */
    @Test
    fun `a missing token stops the server starting`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Config.fromEnvironment(env("DATABASE_URL" to "postgres://h/db"))
        }
        assertTrue(failure.message!!.contains("SYNC_TOKEN"))
    }

    @Test
    fun `a short token stops the server starting`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Config.fromEnvironment(env("DATABASE_URL" to "postgres://h/db", "SYNC_TOKEN" to "hunter2"))
        }
        assertTrue(failure.message!!.contains("at least"))
    }

    @Test
    fun `a missing database url stops the server starting`() {
        assertThrows(IllegalArgumentException::class.java) {
            Config.fromEnvironment(env("SYNC_TOKEN" to goodToken))
        }
    }

    @Test
    fun `explicit credentials override the ones in the url`() {
        val config = Config.fromEnvironment(
            env(
                "DATABASE_URL" to "postgres://bob:hunter2@h/db",
                "DATABASE_USER" to "alice",
                "DATABASE_PASSWORD" to "correct-horse",
                "SYNC_TOKEN" to goodToken,
            ),
        )
        assertEquals("alice", config.dbUser)
        assertEquals("correct-horse", config.dbPassword)
    }

    @Test
    fun `the port defaults to 8080 and is read from the environment when set`() {
        val base = env("DATABASE_URL" to "postgres://h/db", "SYNC_TOKEN" to goodToken)
        assertEquals(8080, Config.fromEnvironment(base).port)
        assertEquals(3000, Config.fromEnvironment(base + ("PORT" to "3000")).port)
    }
}
