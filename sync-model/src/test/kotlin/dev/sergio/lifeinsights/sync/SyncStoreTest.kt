package dev.sergio.lifeinsights.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStoreTest {

    private val rows = InMemoryRowStore()
    private val store = SyncStore(rows)

    private fun checkIn(uid: String, mood: Int = 0, updatedAtUtc: Long, deleted: Boolean = false) =
        SyncCheckIn(
            uid = uid,
            timestampUtc = 1_700_000_000_000,
            zoneId = "Europe/Madrid",
            localDate = 20_000,
            mood = mood,
            energy = 0,
            updatedAtUtc = updatedAtUtc,
            deleted = deleted,
        )

    @Test
    fun `pushed rows come back on a pull from zero`() {
        store.push(
            ChangeSet(
                checkIns = listOf(checkIn("a", updatedAtUtc = 10)),
                dailyMetrics = listOf(SyncDailyMetric(localDate = 20_000, updatedAtUtc = 10)),
                tags = listOf(SyncTag("caffeine", sortOrder = 0, updatedAtUtc = 10)),
            ),
        )

        val pulled = store.pull(since = 0, limit = 100)
        assertEquals(1, pulled.changes.checkIns.size)
        assertEquals(1, pulled.changes.dailyMetrics.size)
        assertEquals(1, pulled.changes.tags.size)
        assertFalse(pulled.hasMore)
        assertEquals(3L, pulled.nextSince)
    }

    /**
     * A device that keeps offering rows the server already has must not keep generating new
     * sequence numbers for them. If it did, every push would create something to pull, and the two
     * would trade the same rows back and forth without ever settling.
     */
    @Test
    fun `re-pushing an unchanged row writes nothing`() {
        val row = checkIn("a", updatedAtUtc = 10)
        store.push(ChangeSet(checkIns = listOf(row)))
        val afterFirst = rows.currentSeq()
        val writesAfterFirst = rows.writes

        val second = store.push(ChangeSet(checkIns = listOf(row)))

        assertEquals(0, second.applied)
        assertEquals(afterFirst, rows.currentSeq())
        assertEquals(writesAfterFirst, rows.writes)
        assertTrue(second.superseded.isEmpty())
    }

    @Test
    fun `an older version loses and is reported as superseded`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = 3, updatedAtUtc = 200))))
        val seqAfterNewer = rows.currentSeq()

        val response = store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = -3, updatedAtUtc = 100))))

        assertEquals(0, response.applied)
        assertEquals(listOf("a"), response.superseded)
        assertEquals("no sequence burned on a losing push", seqAfterNewer, rows.currentSeq())
        assertEquals(3, rows.checkIn("a")!!.mood)
    }

    @Test
    fun `a newer version wins and is stored`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = 1, updatedAtUtc = 100))))
        val response = store.push(ChangeSet(checkIns = listOf(checkIn("a", mood = -2, updatedAtUtc = 200))))

        assertEquals(1, response.applied)
        assertEquals(-2, rows.checkIn("a")!!.mood)
    }

    @Test
    fun `a tombstone is stored and handed out like any other row`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", updatedAtUtc = 100))))
        store.push(ChangeSet(checkIns = listOf(checkIn("a", updatedAtUtc = 200, deleted = true))))

        val pulled = store.pull(since = 0, limit = 100)
        assertEquals(1, pulled.changes.checkIns.size)
        assertTrue(pulled.changes.checkIns.single().deleted)
        assertEquals("deleted rows do not count towards the total", 0, store.status().checkIns)
    }

    @Test
    fun `a pull returns nothing once the cursor has caught up`() {
        store.push(ChangeSet(checkIns = listOf(checkIn("a", updatedAtUtc = 10))))
        val first = store.pull(since = 0, limit = 100)
        val second = store.pull(since = first.nextSince, limit = 100)

        assertTrue(second.changes.isEmpty)
        assertFalse(second.hasMore)
        assertEquals("an empty page leaves the cursor alone", first.nextSince, second.nextSince)
    }

    /**
     * Paging has to cut across all three tables at once, because they share one counter and a
     * client holds one cursor. Walking a full page at a time must visit every row exactly once.
     */
    @Test
    fun `paging walks every row once across all three tables`() {
        repeat(7) { i ->
            store.push(
                ChangeSet(
                    checkIns = listOf(checkIn("c$i", updatedAtUtc = 10L + i)),
                    dailyMetrics = listOf(
                        SyncDailyMetric(localDate = 20_000L + i, updatedAtUtc = 10L + i),
                    ),
                    tags = listOf(SyncTag("t$i", sortOrder = i, updatedAtUtc = 10L + i)),
                ),
            )
        }

        val seen = mutableListOf<Any>()
        var cursor = 0L
        var pages = 0
        do {
            val page = store.pull(since = cursor, limit = 4)
            seen.addAll(page.changes.checkIns)
            seen.addAll(page.changes.dailyMetrics)
            seen.addAll(page.changes.tags)
            cursor = page.nextSince
            pages++
            check(pages < 50) { "paging did not terminate" }
        } while (page.hasMore)

        assertEquals(21, seen.size)
        assertEquals("no row handed out twice", 21, seen.distinct().size)
        assertTrue("more than one page was needed", pages > 1)
    }

    @Test
    fun `a page never hands out more than the limit`() {
        repeat(10) { i -> store.push(ChangeSet(checkIns = listOf(checkIn("c$i", updatedAtUtc = 10L + i)))) }

        val page = store.pull(since = 0, limit = 3)
        assertEquals(3, page.changes.size)
        assertTrue(page.hasMore)
        assertEquals("the cursor stops at the last row returned", 3L, page.nextSince)
    }

    /**
     * Daily metrics merge per source group, so a push carrying only steps must not wipe the screen
     * time another device recorded for the same day.
     */
    @Test
    fun `a push from one source does not erase another source's fields`() {
        store.push(
            ChangeSet(
                dailyMetrics = listOf(
                    SyncDailyMetric(
                        localDate = 20_000,
                        screenMinutes = 240.0,
                        unlockCount = 61,
                        usageUpdatedAtUtc = 100,
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
                        steps = 8_400.0,
                        healthUpdatedAtUtc = 200,
                        updatedAtUtc = 200,
                    ),
                ),
            ),
        )

        val stored = rows.dailyMetric(20_000)!!
        assertEquals(240.0, stored.screenMinutes!!, 0.0)
        assertEquals(8_400.0, stored.steps!!, 0.0)
    }

    @Test
    fun `status counts what is stored`() {
        store.push(
            ChangeSet(
                checkIns = listOf(checkIn("a", updatedAtUtc = 10), checkIn("b", updatedAtUtc = 11)),
                dailyMetrics = listOf(SyncDailyMetric(localDate = 20_000, updatedAtUtc = 10)),
                tags = listOf(SyncTag("caffeine", sortOrder = 0, updatedAtUtc = 10)),
            ),
        )

        val status = store.status()
        assertEquals(2, status.checkIns)
        assertEquals(1, status.dailyMetrics)
        assertEquals(1, status.tags)
        assertEquals(4L, status.serverSeq)
    }
}
