package dev.sergio.lifeinsights.server

import dev.sergio.lifeinsights.sync.SyncRowStore
import dev.sergio.lifeinsights.sync.SyncRows
import dev.sergio.lifeinsights.sync.SyncStore
import dev.sergio.lifeinsights.sync.SyncCheckIn
import dev.sergio.lifeinsights.sync.SyncDailyMetric
import dev.sergio.lifeinsights.sync.SyncTag
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * The SQL. No decisions are made here; see [SyncStore] for those.
 */
class PostgresRowStore(private val dataSource: DataSource) : SyncRowStore {

    override fun <T> write(body: (SyncRows) -> T): T = transaction { connection ->
        lockForWriting(connection)
        body(PostgresRows(connection))
    }

    override fun <T> read(body: (SyncRows) -> T): T = transaction { connection ->
        body(PostgresRows(connection))
    }

    private fun <T> transaction(body: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = body(connection)
                connection.commit()
                result
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                throw t
            }
        }

    /**
     * Serialises writers for the duration of the transaction.
     *
     * Postgres hands out sequence numbers when `nextval` is called, not when the transaction
     * commits, so two overlapping writers can commit out of order: a client polling in the gap
     * would see sequence 6, move its cursor past it, and never receive the row holding 5. One lock
     * makes sequence order and commit order the same thing. For a server with one person's phone
     * talking to it this costs nothing measurable.
     */
    private fun lockForWriting(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, WRITE_LOCK_KEY)
            statement.executeQuery().use { it.next() }
        }
    }

    private companion object {
        /** Arbitrary but fixed, so every instance of the server takes the same lock. */
        const val WRITE_LOCK_KEY = 0x1FE11511L
    }
}

private class PostgresRows(private val connection: Connection) : SyncRows {

    override fun checkIn(uid: String): SyncCheckIn? =
        connection.prepareStatement("SELECT * FROM check_in WHERE uid = ?").use { statement ->
            statement.setString(1, uid)
            statement.executeQuery().use { if (it.next()) it.toCheckIn() else null }
        }

    override fun dailyMetric(localDate: Long): SyncDailyMetric? =
        connection.prepareStatement("SELECT * FROM daily_metric WHERE local_date = ?")
            .use { statement ->
                statement.setLong(1, localDate)
                statement.executeQuery().use { if (it.next()) it.toDailyMetric() else null }
            }

    override fun tag(name: String): SyncTag? =
        connection.prepareStatement("SELECT * FROM tag WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { if (it.next()) it.toTag() else null }
        }

    override fun checkInsSince(since: Long, limit: Int): List<Pair<Long, SyncCheckIn>> =
        page("check_in", since, limit) { it.toCheckIn() }

    override fun dailyMetricsSince(since: Long, limit: Int): List<Pair<Long, SyncDailyMetric>> =
        page("daily_metric", since, limit) { it.toDailyMetric() }

    override fun tagsSince(since: Long, limit: Int): List<Pair<Long, SyncTag>> =
        page("tag", since, limit) { it.toTag() }

    // The table name is a literal from the three call sites above, never anything a request
    // supplies. `since` and `limit` are bound as parameters like everything else.
    private fun <T> page(
        table: String,
        since: Long,
        limit: Int,
        read: (ResultSet) -> T,
    ): List<Pair<Long, T>> = connection.prepareStatement(
        "SELECT * FROM $table WHERE seq > ? ORDER BY seq ASC LIMIT ?",
    ).use { statement ->
        statement.setLong(1, since)
        statement.setInt(2, limit)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getLong("seq") to read(rows)) }
        }
    }

    override fun put(row: SyncCheckIn) {
        connection.prepareStatement(
            """
            INSERT INTO check_in (uid, timestamp_utc, zone_id, local_date, mood, energy, note,
                                  tags, updated_at_utc, deleted, seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, nextval('sync_seq'))
            ON CONFLICT (uid) DO UPDATE SET
                timestamp_utc = EXCLUDED.timestamp_utc,
                zone_id = EXCLUDED.zone_id,
                local_date = EXCLUDED.local_date,
                mood = EXCLUDED.mood,
                energy = EXCLUDED.energy,
                note = EXCLUDED.note,
                tags = EXCLUDED.tags,
                updated_at_utc = EXCLUDED.updated_at_utc,
                deleted = EXCLUDED.deleted,
                seq = nextval('sync_seq')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, row.uid)
            statement.setLong(2, row.timestampUtc)
            statement.setString(3, row.zoneId)
            statement.setLong(4, row.localDate)
            statement.setInt(5, row.mood)
            statement.setInt(6, row.energy)
            statement.setString(7, row.note)
            statement.setString(8, Json.encodeToString(row.tags))
            statement.setLong(9, row.updatedAtUtc)
            statement.setBoolean(10, row.deleted)
            statement.executeUpdate()
        }
    }

    override fun put(row: SyncDailyMetric) {
        connection.prepareStatement(
            """
            INSERT INTO daily_metric (local_date, screen_minutes, social_media_minutes,
                                      late_night_screen_minutes, unlock_count, usage_updated_at_utc,
                                      sleep_minutes, sleep_source, sleep_start_utc, sleep_end_utc,
                                      sleep_updated_at_utc, steps, exercise_minutes,
                                      resting_heart_rate, hrv, health_updated_at_utc,
                                      updated_at_utc, seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, nextval('sync_seq'))
            ON CONFLICT (local_date) DO UPDATE SET
                screen_minutes = EXCLUDED.screen_minutes,
                social_media_minutes = EXCLUDED.social_media_minutes,
                late_night_screen_minutes = EXCLUDED.late_night_screen_minutes,
                unlock_count = EXCLUDED.unlock_count,
                usage_updated_at_utc = EXCLUDED.usage_updated_at_utc,
                sleep_minutes = EXCLUDED.sleep_minutes,
                sleep_source = EXCLUDED.sleep_source,
                sleep_start_utc = EXCLUDED.sleep_start_utc,
                sleep_end_utc = EXCLUDED.sleep_end_utc,
                sleep_updated_at_utc = EXCLUDED.sleep_updated_at_utc,
                steps = EXCLUDED.steps,
                exercise_minutes = EXCLUDED.exercise_minutes,
                resting_heart_rate = EXCLUDED.resting_heart_rate,
                hrv = EXCLUDED.hrv,
                health_updated_at_utc = EXCLUDED.health_updated_at_utc,
                updated_at_utc = EXCLUDED.updated_at_utc,
                seq = nextval('sync_seq')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, row.localDate)
            statement.setDoubleOrNull(2, row.screenMinutes)
            statement.setDoubleOrNull(3, row.socialMediaMinutes)
            statement.setDoubleOrNull(4, row.lateNightScreenMinutes)
            statement.setIntOrNull(5, row.unlockCount)
            statement.setLong(6, row.usageUpdatedAtUtc)
            statement.setDoubleOrNull(7, row.sleepMinutes)
            statement.setString(8, row.sleepSource)
            statement.setLongOrNull(9, row.sleepStartUtc)
            statement.setLongOrNull(10, row.sleepEndUtc)
            statement.setLong(11, row.sleepUpdatedAtUtc)
            statement.setDoubleOrNull(12, row.steps)
            statement.setDoubleOrNull(13, row.exerciseMinutes)
            statement.setDoubleOrNull(14, row.restingHeartRate)
            statement.setDoubleOrNull(15, row.hrv)
            statement.setLong(16, row.healthUpdatedAtUtc)
            statement.setLong(17, row.updatedAtUtc)
            statement.executeUpdate()
        }
    }

    override fun put(row: SyncTag) {
        connection.prepareStatement(
            """
            INSERT INTO tag (name, sort_order, enabled, updated_at_utc, deleted, seq)
            VALUES (?, ?, ?, ?, ?, nextval('sync_seq'))
            ON CONFLICT (name) DO UPDATE SET
                sort_order = EXCLUDED.sort_order,
                enabled = EXCLUDED.enabled,
                updated_at_utc = EXCLUDED.updated_at_utc,
                deleted = EXCLUDED.deleted,
                seq = nextval('sync_seq')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, row.name)
            statement.setInt(2, row.sortOrder)
            statement.setBoolean(3, row.enabled)
            statement.setLong(4, row.updatedAtUtc)
            statement.setBoolean(5, row.deleted)
            statement.executeUpdate()
        }
    }

    override fun currentSeq(): Long = connection.createStatement().use { statement ->
        statement.executeQuery(
            """
            SELECT GREATEST(
                COALESCE((SELECT MAX(seq) FROM check_in), 0),
                COALESCE((SELECT MAX(seq) FROM daily_metric), 0),
                COALESCE((SELECT MAX(seq) FROM tag), 0)
            )
            """.trimIndent(),
        ).use { if (it.next()) it.getLong(1) else 0L }
    }

    override fun counts(): SyncRows.Counts = SyncRows.Counts(
        checkIns = count("SELECT COUNT(*) FROM check_in WHERE NOT deleted"),
        dailyMetrics = count("SELECT COUNT(*) FROM daily_metric"),
        tags = count("SELECT COUNT(*) FROM tag WHERE NOT deleted"),
    )

    private fun count(sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { if (it.next()) it.getInt(1) else 0 }
    }

    private fun ResultSet.toCheckIn() = SyncCheckIn(
        uid = getString("uid"),
        timestampUtc = getLong("timestamp_utc"),
        zoneId = getString("zone_id"),
        localDate = getLong("local_date"),
        mood = getInt("mood"),
        energy = getInt("energy"),
        note = getString("note"),
        tags = Json.decodeFromString(getString("tags")),
        updatedAtUtc = getLong("updated_at_utc"),
        deleted = getBoolean("deleted"),
    )

    private fun ResultSet.toDailyMetric() = SyncDailyMetric(
        localDate = getLong("local_date"),
        screenMinutes = doubleOrNull("screen_minutes"),
        socialMediaMinutes = doubleOrNull("social_media_minutes"),
        lateNightScreenMinutes = doubleOrNull("late_night_screen_minutes"),
        unlockCount = intOrNull("unlock_count"),
        usageUpdatedAtUtc = getLong("usage_updated_at_utc"),
        sleepMinutes = doubleOrNull("sleep_minutes"),
        sleepSource = getString("sleep_source"),
        sleepStartUtc = longOrNull("sleep_start_utc"),
        sleepEndUtc = longOrNull("sleep_end_utc"),
        sleepUpdatedAtUtc = getLong("sleep_updated_at_utc"),
        steps = doubleOrNull("steps"),
        exerciseMinutes = doubleOrNull("exercise_minutes"),
        restingHeartRate = doubleOrNull("resting_heart_rate"),
        hrv = doubleOrNull("hrv"),
        healthUpdatedAtUtc = getLong("health_updated_at_utc"),
        updatedAtUtc = getLong("updated_at_utc"),
    )

    private fun ResultSet.toTag() = SyncTag(
        name = getString("name"),
        sortOrder = getInt("sort_order"),
        enabled = getBoolean("enabled"),
        updatedAtUtc = getLong("updated_at_utc"),
        deleted = getBoolean("deleted"),
    )
}

// getDouble returns 0.0 for SQL NULL, which is exactly the confusion between "no reading" and
// "zero" that the nullable columns exist to avoid. Every nullable read goes through these.

private fun ResultSet.doubleOrNull(column: String): Double? =
    getDouble(column).takeUnless { wasNull() }

private fun ResultSet.intOrNull(column: String): Int? =
    getInt(column).takeUnless { wasNull() }

private fun ResultSet.longOrNull(column: String): Long? =
    getLong(column).takeUnless { wasNull() }

private fun PreparedStatement.setDoubleOrNull(index: Int, value: Double?) =
    if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)

private fun PreparedStatement.setIntOrNull(index: Int, value: Int?) =
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)

private fun PreparedStatement.setLongOrNull(index: Int, value: Long?) =
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
