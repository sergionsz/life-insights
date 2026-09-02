package dev.sergio.lifeinsights.server

import dev.sergio.lifeinsights.sync.SyncRowStore
import dev.sergio.lifeinsights.sync.SyncStore
import dev.sergio.lifeinsights.sync.ChangeSet
import dev.sergio.lifeinsights.sync.SyncCheckIn
import dev.sergio.lifeinsights.sync.SyncDailyMetric
import dev.sergio.lifeinsights.sync.SyncTag
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import javax.sql.DataSource

/**
 * The SQL, run against a real Postgres.
 *
 * [SyncStoreTest] covers what the server decides, against maps. None of that touches a column name,
 * an `ON CONFLICT` clause, or the difference between a SQL NULL and a zero, and those are exactly
 * the mistakes that compile cleanly and then fail on the first deploy. An embedded Postgres is used
 * rather than a container so this runs on a laptop without Docker as well as in CI.
 *
 * These deliberately repeat scenarios from the in-memory tests. The point is not the scenarios; it
 * is that both implementations of [SyncRowStore] answer them the same way.
 */
class PostgresRowStoreTest {

    private lateinit var store: SyncStore
    private lateinit var rowStore: PostgresRowStore

    @Before
    fun setUp() {
        truncate()
        rowStore = PostgresRowStore(dataSource)
        store = SyncStore(rowStore)
    }

    private fun checkIn(
        uid: String,
        mood: Int = 1,
        note: String? = null,
        tags: List<String> = emptyList(),
        updatedAtUtc: Long,
        deleted: Boolean = false,
    ) = SyncCheckIn(
        uid = uid,
        timestampUtc = 1_700_000_000_000,
        zoneId = "Europe/Madrid",
        localDate = 20_000,
        mood = mood,
        energy = -1,
        note = note,
        tags = tags,
        updatedAtUtc = updatedAtUtc,
        deleted = deleted,
    )

    @Test
    fun `a check-in round trips through every column`() {
        val row = checkIn(
            uid = "3f1a0d2c-0000-4000-8000-000000000001",
            mood = -3,
            note = "a note with 'quotes' and a comma, too",
            tags = listOf("caffeine", "outdoors"),
            updatedAtUtc = 42,
        )
        store.push(ChangeSet(checkIns = listOf(row)))

        assertEquals(row, store.pull(0, 100).changes.checkIns.single())
    }

    @Test
    fun `every daily metric column round trips, nulls included`() {
        val full = SyncDailyMetric(
            localDate = 20_000,
            screenMinutes = 274.5,
            socialMediaMinutes = 61.25,
            lateNightScreenMinutes = 18.0,
            unlockCount = 61,
            usageUpdatedAtUtc = 111,
            sleepMinutes = 430.0,
            sleepSource = "MANUAL",
            sleepStartUtc = 1_000,
            sleepEndUtc = 26_800,
            sleepUpdatedAtUtc = 222,
            steps = 8_400.0,
            exerciseMinutes = 35.0,
            restingHeartRate = 54.0,
            hrv = 62.5,
            healthUpdatedAtUtc = 333,
            updatedAtUtc = 333,
        )
        val empty = SyncDailyMetric(localDate = 20_001, updatedAtUtc = 5)

        store.push(ChangeSet(dailyMetrics = listOf(full, empty)))
        val pulled = store.pull(0, 100).changes.dailyMetrics.associateBy { it.localDate }

        assertEquals(full, pulled[20_000L])
        assertEquals(empty, pulled[20_001L])
        // getDouble returns 0.0 for SQL NULL. If the null-aware reads were wrong this is where a
        // day with no reading would come back claiming zero minutes of everything.
        assertNull(pulled[20_001L]!!.screenMinutes)
        assertNull(pulled[20_001L]!!.unlockCount)
        assertNull(pulled[20_001L]!!.sleepStartUtc)
    }

    @Test
    fun `a tag round trips`() {
        val row = SyncTag("social contact", sortOrder = 4, enabled = false, updatedAtUtc = 9)
        store.push(ChangeSet(tags = listOf(row)))
        assertEquals(row, store.pull(0, 100).changes.tags.single())
    }

    /** Exercises the ON CONFLICT branch, which the insert path never reaches. */
    @Test
    fun `updating an existing row goes through the conflict clause`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = 1, updatedAtUtc = 100))))
        store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = -2, updatedAtUtc = 200))))

        val all = store.pull(0, 100).changes.checkIns
        assertEquals("the row was updated, not duplicated", 1, all.size)
        assertEquals(-2, all.single().mood)
    }

    @Test
    fun `an older version does not overwrite a newer one`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = 3, updatedAtUtc = 200))))
        val response = store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = -3, updatedAtUtc = 100))))

        assertEquals(0, response.applied)
        assertEquals(listOf("a"), response.superseded)
        assertEquals(3, store.pull(0, 100).changes.checkIns.single().mood)
    }

    /**
     * The three tables draw from one sequence, which is what lets a client hold a single cursor.
     * Per-table counters would hand out the same numbers in each and the interleaving would be
     * meaningless.
     */
    @Test
    fun `sequence numbers are unique across all three tables`() {
        repeat(5) { i ->
            store.push(
                ChangeSet(
                    checkIns = listOf(checkIn("c$i", updatedAtUtc = 10L + i)),
                    dailyMetrics = listOf(SyncDailyMetric(localDate = 20_000L + i, updatedAtUtc = 10L + i)),
                    tags = listOf(SyncTag("t$i", sortOrder = i, updatedAtUtc = 10L + i)),
                ),
            )
        }

        val seen = mutableSetOf<Long>()
        var cursor = 0L
        var rows = 0
        while (true) {
            val page = store.pull(cursor, 4)
            rows += page.changes.size
            assertTrue("cursor moves forward", page.nextSince > cursor || page.changes.isEmpty)
            seen += page.nextSince
            cursor = page.nextSince
            if (!page.hasMore) break
        }

        assertEquals("every row was handed out exactly once", 15, rows)
    }

    @Test
    fun `paging across the three tables terminates and repeats nothing`() {
        repeat(9) { i ->
            store.push(
                ChangeSet(
                    checkIns = listOf(checkIn("c$i", updatedAtUtc = 10L + i)),
                    dailyMetrics = listOf(SyncDailyMetric(localDate = 20_000L + i, updatedAtUtc = 10L + i)),
                    tags = listOf(SyncTag("t$i", sortOrder = i, updatedAtUtc = 10L + i)),
                ),
            )
        }

        val seen = mutableListOf<Any>()
        var cursor = 0L
        var pages = 0
        while (true) {
            val page = store.pull(cursor, 5)
            seen.addAll(page.changes.checkIns)
            seen.addAll(page.changes.dailyMetrics)
            seen.addAll(page.changes.tags)
            cursor = page.nextSince
            check(++pages < 50) { "paging did not terminate" }
            if (!page.hasMore) break
        }

        assertEquals(27, seen.size)
        assertEquals("no row handed out twice", 27, seen.distinct().size)
        assertTrue(pages > 1)
    }

    @Test
    fun `re-pushing an unchanged row burns no sequence number`() {
        val row = checkIn("a", updatedAtUtc = 10)
        store.push(ChangeSet(checkIns = listOf(row)))
        val seq = store.status().serverSeq

        val second = store.push(ChangeSet(checkIns = listOf(row)))

        assertEquals(0, second.applied)
        assertEquals(seq, store.status().serverSeq)
        assertFalse(store.pull(seq, 100).changes.checkIns.isNotEmpty())
    }

    @Test
    fun `status counts live rows and ignores tombstones`() {
        store.push(
            ChangeSet(
                checkIns = listOf(checkIn("a", updatedAtUtc = 10), checkIn("b", updatedAtUtc = 11)),
                dailyMetrics = listOf(SyncDailyMetric(localDate = 20_000, updatedAtUtc = 10)),
                tags = listOf(SyncTag("caffeine", sortOrder = 0, updatedAtUtc = 10)),
            ),
        )
        store.push(ChangeSet(checkIns = listOf(checkIn("a", updatedAtUtc = 20, deleted = true))))

        val status = store.status()
        assertEquals(1, status.checkIns)
        assertEquals(1, status.dailyMetrics)
        assertEquals(1, status.tags)
    }

    @Test
    fun `a tombstone is still handed out on a pull`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", updatedAtUtc = 10))))
        store.push(ChangeSet(checkIns = listOf(checkIn("a", updatedAtUtc = 20, deleted = true))))

        assertTrue(store.pull(0, 100).changes.checkIns.single().deleted)
    }

    @Test
    fun `the sleep group survives a later push that carries only usage`() {
        store.push(
            ChangeSet(
                dailyMetrics = listOf(
                    SyncDailyMetric(
                        localDate = 20_000,
                        sleepMinutes = 430.0,
                        sleepSource = "MANUAL",
                        sleepUpdatedAtUtc = 100,
                        updatedAtUtc = 100,
                    ),
                ),
            ),
        )
        store.push(
            ChangeSet(
                dailyMetrics = listOf(
                    SyncDailyMetric(
                        localDate = 20_000,
                        screenMinutes = 274.0,
                        usageUpdatedAtUtc = 900,
                        updatedAtUtc = 900,
                    ),
                ),
            ),
        )

        val stored = store.pull(0, 100).changes.dailyMetrics.single()
        assertEquals(430.0, stored.sleepMinutes!!, 0.0)
        assertEquals("MANUAL", stored.sleepSource)
        assertEquals(274.0, stored.screenMinutes!!, 0.0)
    }

    @Test
    fun `migrations are idempotent`() {
        // Whatever restarts the server (a redeploy, a crash loop) runs these again on every boot.
        Database.migrate(dataSource)
        Database.migrate(dataSource)
        assertEquals(0, store.status().checkIns)
    }

    private fun truncate() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE check_in, daily_metric, tag")
                it.execute("ALTER SEQUENCE sync_seq RESTART WITH 1")
            }
        }
    }

    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var dataSource: DataSource

        @BeforeClass
        @JvmStatic
        fun startPostgres() {
            postgres = EmbeddedPostgres.start()
            dataSource = postgres.postgresDatabase
            Database.migrate(dataSource)
        }

        @AfterClass
        @JvmStatic
        fun stopPostgres() {
            postgres.close()
        }
    }
}
