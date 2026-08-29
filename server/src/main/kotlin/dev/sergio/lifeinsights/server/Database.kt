package dev.sergio.lifeinsights.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Connection pool and schema setup.
 *
 * The migration runner is deliberately tiny rather than a dependency: this schema is three tables
 * that change when the app's own schema does, and a numbered list of statements applied inside one
 * transaction covers that without adding a tool to learn.
 */
object Database {

    fun dataSource(config: Config): DataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            config.dbUser?.let { username = it }
            config.dbPassword?.let { password = it }
            // One user syncing a phone needs very little. A small pool also keeps the server well
            // inside the connection limits of the free Postgres tiers this is likely to run on.
            maximumPoolSize = 4
            minimumIdle = 1
            poolName = "life-insights"
        }
        return HikariDataSource(hikari)
    }

    /**
     * Applies every migration the database has not seen yet.
     *
     * Each runs in its own transaction alongside the row recording it, so a failure halfway through
     * leaves the database at the last version that fully applied rather than in a half-migrated
     * state that the next start would try to migrate again from the wrong place.
     */
    fun migrate(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version        INT PRIMARY KEY,
                        applied_at_utc BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            connection.commit()

            val applied = mutableSetOf<Int>()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_version").use { rows ->
                    while (rows.next()) applied += rows.getInt(1)
                }
            }

            for ((version, statements) in MIGRATIONS) {
                if (version in applied) continue
                connection.createStatement().use { statement ->
                    for (sql in statements) statement.execute(sql)
                    statement.execute(
                        "INSERT INTO schema_version (version, applied_at_utc) " +
                            "VALUES ($version, ${System.currentTimeMillis()})",
                    )
                }
                connection.commit()
            }
        }
    }

    private val MIGRATIONS: List<Pair<Int, List<String>>> = listOf(
        1 to listOf(
            // One sequence shared by every table, so a client can hold a single cursor and pull
            // changes across all three in a well-defined order. Per-table sequences would give no
            // way to interleave them.
            "CREATE SEQUENCE IF NOT EXISTS sync_seq",

            """
            CREATE TABLE IF NOT EXISTS check_in (
                uid            TEXT PRIMARY KEY,
                timestamp_utc  BIGINT NOT NULL,
                zone_id        TEXT NOT NULL,
                local_date     BIGINT NOT NULL,
                mood           INT NOT NULL,
                energy         INT NOT NULL,
                note           TEXT,
                -- A JSON array. Tags belong to the check-in and are never queried on their own
                -- here, so a child table would buy nothing and cost the atomicity of writing an
                -- entry and its tags together.
                tags           TEXT NOT NULL DEFAULT '[]',
                updated_at_utc BIGINT NOT NULL,
                deleted        BOOLEAN NOT NULL DEFAULT FALSE,
                seq            BIGINT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS check_in_seq_idx ON check_in (seq)",

            """
            CREATE TABLE IF NOT EXISTS daily_metric (
                local_date                BIGINT PRIMARY KEY,
                screen_minutes            DOUBLE PRECISION,
                social_media_minutes      DOUBLE PRECISION,
                late_night_screen_minutes DOUBLE PRECISION,
                unlock_count              INT,
                usage_updated_at_utc      BIGINT NOT NULL DEFAULT 0,
                sleep_minutes             DOUBLE PRECISION,
                sleep_source              TEXT,
                sleep_start_utc           BIGINT,
                sleep_end_utc             BIGINT,
                sleep_updated_at_utc      BIGINT NOT NULL DEFAULT 0,
                steps                     DOUBLE PRECISION,
                exercise_minutes          DOUBLE PRECISION,
                resting_heart_rate        DOUBLE PRECISION,
                hrv                       DOUBLE PRECISION,
                health_updated_at_utc     BIGINT NOT NULL DEFAULT 0,
                updated_at_utc            BIGINT NOT NULL,
                seq                       BIGINT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS daily_metric_seq_idx ON daily_metric (seq)",

            """
            CREATE TABLE IF NOT EXISTS tag (
                name           TEXT PRIMARY KEY,
                sort_order     INT NOT NULL,
                enabled        BOOLEAN NOT NULL DEFAULT TRUE,
                updated_at_utc BIGINT NOT NULL,
                deleted        BOOLEAN NOT NULL DEFAULT FALSE,
                seq            BIGINT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS tag_seq_idx ON tag (seq)",
        ),
    )
}
