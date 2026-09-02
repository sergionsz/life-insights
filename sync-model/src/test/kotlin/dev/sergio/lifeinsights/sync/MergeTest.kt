package dev.sergio.lifeinsights.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MergeTest {

    private fun checkIn(
        uid: String = "u1",
        mood: Int = 0,
        energy: Int = 0,
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
        energy = energy,
        note = note,
        tags = tags,
        updatedAtUtc = updatedAtUtc,
        deleted = deleted,
    )

    /**
     * A group's timestamp is set only when that group actually holds a reading, which is what the
     * app does: `writeDay` stamps the usage group when it aggregates usage, and the sleep group
     * when it has an estimate. A row that stamped every group would claim to have written fields it
     * never touched.
     */
    private fun metric(
        localDate: Long = 20_000,
        screenMinutes: Double? = null,
        unlockCount: Int? = null,
        steps: Double? = null,
        sleepMinutes: Double? = null,
        sleepSource: String? = null,
        sleepStartUtc: Long? = null,
        sleepEndUtc: Long? = null,
        updatedAtUtc: Long,
        usageUpdatedAtUtc: Long? = null,
        sleepUpdatedAtUtc: Long? = null,
        healthUpdatedAtUtc: Long? = null,
    ): SyncDailyMetric {
        val hasUsage = screenMinutes != null || unlockCount != null
        val hasSleep = sleepMinutes != null
        val hasHealth = steps != null
        return SyncDailyMetric(
            localDate = localDate,
            screenMinutes = screenMinutes,
            unlockCount = unlockCount,
            usageUpdatedAtUtc = usageUpdatedAtUtc ?: if (hasUsage) updatedAtUtc else 0,
            sleepMinutes = sleepMinutes,
            sleepSource = sleepSource,
            sleepStartUtc = sleepStartUtc,
            sleepEndUtc = sleepEndUtc,
            sleepUpdatedAtUtc = sleepUpdatedAtUtc ?: if (hasSleep) updatedAtUtc else 0,
            steps = steps,
            healthUpdatedAtUtc = healthUpdatedAtUtc ?: if (hasHealth) updatedAtUtc else 0,
            updatedAtUtc = updatedAtUtc,
        )
    }

    // ---- check-ins ----------------------------------------------------------------------------

    @Test
    fun `newer check-in wins`() {
        val old = checkIn(mood = 1, updatedAtUtc = 100)
        val new = checkIn(mood = -2, updatedAtUtc = 200)
        assertEquals(new, Merge.checkIn(old, new))
        assertEquals(new, Merge.checkIn(new, old))
    }

    @Test
    fun `a missing side is not a conflict`() {
        val row = checkIn(updatedAtUtc = 100)
        assertEquals(row, Merge.checkIn(null, row))
        assertEquals(row, Merge.checkIn(row, null))
        assertNull(Merge.checkIn(null, null))
    }

    @Test
    fun `a tombstone beats an older edit`() {
        val edit = checkIn(mood = 2, updatedAtUtc = 100)
        val deleted = checkIn(mood = 2, updatedAtUtc = 200, deleted = true)
        assertEquals(deleted, Merge.checkIn(edit, deleted))
        assertEquals(deleted, Merge.checkIn(deleted, edit))
    }

    @Test
    fun `an older tombstone does not undo a newer edit`() {
        val deleted = checkIn(updatedAtUtc = 100, deleted = true)
        val edit = checkIn(mood = 3, updatedAtUtc = 200)
        assertEquals(edit, Merge.checkIn(deleted, edit))
        assertEquals(edit, Merge.checkIn(edit, deleted))
    }

    /**
     * The property that makes the whole scheme work. Both ends see the same pair in opposite
     * orders, so a rule that preferred "the incoming row" would leave each end holding its own
     * version and pushing it back at the other forever.
     */
    @Test
    fun `tied check-ins resolve identically from both sides`() {
        val a = checkIn(mood = 1, note = "walked", updatedAtUtc = 500)
        val b = checkIn(mood = -1, note = "did not", updatedAtUtc = 500)
        assertEquals(Merge.checkIn(a, b), Merge.checkIn(b, a))
    }

    @Test
    fun `tags travel with the check-in that owns them`() {
        val old = checkIn(tags = listOf("caffeine"), updatedAtUtc = 100)
        val new = checkIn(tags = listOf("alcohol", "stress"), updatedAtUtc = 200)
        assertEquals(listOf("alcohol", "stress"), Merge.checkIn(old, new)?.tags)
    }

    @Test
    fun `identical rows merge to themselves`() {
        val row = checkIn(mood = 2, tags = listOf("outdoors"), updatedAtUtc = 100)
        assertEquals(row, Merge.checkIn(row, row.copy()))
    }

    // ---- daily metrics ------------------------------------------------------------------------

    @Test
    fun `a field only one side has survives the merge`() {
        val phone = metric(screenMinutes = 240.0, unlockCount = 61, updatedAtUtc = 100)
        val watch = metric(steps = 8_400.0, updatedAtUtc = 200)

        val merged = Merge.dailyMetric(phone, watch)!!
        assertEquals(240.0, merged.screenMinutes!!, 0.0)
        assertEquals(61, merged.unlockCount)
        assertEquals(8_400.0, merged.steps!!, 0.0)
        assertEquals(Merge.dailyMetric(watch, phone), merged)
    }

    @Test
    fun `when both sides have a field the newer one wins`() {
        val old = metric(screenMinutes = 100.0, updatedAtUtc = 100)
        val new = metric(screenMinutes = 300.0, updatedAtUtc = 200)
        assertEquals(300.0, Merge.dailyMetric(old, new)!!.screenMinutes!!, 0.0)
        assertEquals(300.0, Merge.dailyMetric(new, old)!!.screenMinutes!!, 0.0)
    }

    /**
     * The case that motivates ranking sleep by source instead of by time. Re-aggregation runs on
     * every app open, so a proxy write is almost always the most recent thing to touch the row.
     */
    @Test
    fun `a hand corrected night survives a later proxy estimate`() {
        val corrected = metric(
            sleepMinutes = 430.0,
            sleepSource = "MANUAL",
            sleepStartUtc = 1_000,
            sleepEndUtc = 26_800,
            updatedAtUtc = 100,
        )
        val proxy = metric(
            sleepMinutes = 180.0,
            sleepSource = "PROXY",
            sleepStartUtc = 5_000,
            sleepEndUtc = 15_800,
            updatedAtUtc = 999_999,
        )

        val merged = Merge.dailyMetric(corrected, proxy)!!
        assertEquals(430.0, merged.sleepMinutes!!, 0.0)
        assertEquals("MANUAL", merged.sleepSource)
        assertEquals(Merge.dailyMetric(proxy, corrected), merged)
    }

    @Test
    fun `a wearable reading beats the proxy whichever arrived first`() {
        val proxy = metric(sleepMinutes = 400.0, sleepSource = "PROXY", updatedAtUtc = 900)
        val wearable = metric(sleepMinutes = 455.0, sleepSource = "WEARABLE", updatedAtUtc = 100)
        assertEquals("WEARABLE", Merge.dailyMetric(proxy, wearable)!!.sleepSource)
        assertEquals("WEARABLE", Merge.dailyMetric(wearable, proxy)!!.sleepSource)
    }

    /**
     * Sleep is one measurement spread over four columns. Merging them independently could pair a
     * duration from one night with the start and end of another.
     */
    @Test
    fun `sleep fields move as a single group`() {
        val manual = metric(
            sleepMinutes = 430.0,
            sleepSource = "MANUAL",
            sleepStartUtc = 1_000,
            sleepEndUtc = 26_800,
            updatedAtUtc = 100,
        )
        val proxy = metric(
            sleepMinutes = 180.0,
            sleepSource = "PROXY",
            sleepStartUtc = 5_000,
            sleepEndUtc = 15_800,
            updatedAtUtc = 500,
        )

        val merged = Merge.dailyMetric(manual, proxy)!!
        assertEquals(1_000L, merged.sleepStartUtc)
        assertEquals(26_800L, merged.sleepEndUtc)
        assertEquals(430.0, merged.sleepMinutes!!, 0.0)
    }

    @Test
    fun `a night neither side measured stays absent`() {
        val a = metric(screenMinutes = 10.0, updatedAtUtc = 100)
        val b = metric(steps = 20.0, updatedAtUtc = 200)
        val merged = Merge.dailyMetric(a, b)!!
        assertNull(merged.sleepMinutes)
        assertNull(merged.sleepSource)
        assertNull(merged.sleepStartUtc)
    }

    @Test
    fun `the merged row carries the later timestamp`() {
        val merged = Merge.dailyMetric(metric(updatedAtUtc = 100), metric(updatedAtUtc = 700))!!
        assertEquals(700L, merged.updatedAtUtc)
    }

    // ---- tags ---------------------------------------------------------------------------------

    @Test
    fun `a deleted tag stays deleted until something newer says otherwise`() {
        val live = SyncTag("caffeine", sortOrder = 0, updatedAtUtc = 100)
        val gone = SyncTag("caffeine", sortOrder = 0, updatedAtUtc = 200, deleted = true)
        assertTrue(Merge.tag(live, gone)!!.deleted)
        assertTrue(Merge.tag(gone, live)!!.deleted)

        val revived = SyncTag("caffeine", sortOrder = 4, updatedAtUtc = 300)
        assertEquals(revived, Merge.tag(gone, revived))
    }

    // ---- properties ---------------------------------------------------------------------------

    /**
     * Order independence, checked over random rows rather than hand-picked ones.
     *
     * Devices do not agree on the order they see changes in: one pulls the server's version first,
     * another pushes its own first, a third syncs after both. If the merge depended on that order
     * the three would settle on different answers and keep overwriting each other.
     */
    /**
     * The exact shape that broke the first version of this merge, kept as a regression test.
     *
     * A row that has only health data is the most recently touched of the three, but it says
     * nothing about phone usage. When the row carried a single timestamp, merging the old unlock
     * count into it restamped that count as the newest thing on the row, and the genuinely newer
     * count then lost to it. Merging the other pair first gave the other answer.
     *
     * With the usage group keeping its own timestamp, the newest health write cannot vouch for a
     * usage value it never produced, and both orders agree.
     */
    @Test
    fun `a value does not inherit an unrelated group's timestamp`() {
        val recentHealthOnly = metric(steps = 19_437.0, updatedAtUtc = 7057)
        val oldCount = metric(unlockCount = 63, updatedAtUtc = 1744)
        val newerCount = metric(unlockCount = 4, updatedAtUtc = 4321)

        val leftFirst = Merge.dailyMetric(
            Merge.dailyMetric(recentHealthOnly, oldCount),
            newerCount,
        )!!
        val rightFirst = Merge.dailyMetric(
            recentHealthOnly,
            Merge.dailyMetric(oldCount, newerCount),
        )!!

        assertEquals(leftFirst, rightFirst)
        assertEquals("the count written at 4321 is the newer one", 4, leftFirst.unlockCount)
        assertEquals(19_437.0, leftFirst.steps!!, 0.0)
    }

    /**
     * The flip side: fields inside one group are produced by a single computation, so an
     * aggregation that found screen time but no unlocks really does mean there were no unlocks.
     * That null replaces an earlier count rather than deferring to it.
     */
    @Test
    fun `a later aggregation replaces the whole usage group`() {
        val earlier = metric(screenMinutes = 100.0, unlockCount = 63, updatedAtUtc = 1_000)
        val later = metric(screenMinutes = 491.6, updatedAtUtc = 2_000)

        val merged = Merge.dailyMetric(earlier, later)!!
        assertEquals(491.6, merged.screenMinutes!!, 0.0)
        assertNull(merged.unlockCount)
    }

    /**
     * Order independence, checked over random rows rather than hand-picked ones.
     *
     * Devices do not agree on the order they see changes in: one pulls the server's version first,
     * another pushes its own first, a third syncs after both. If the merge depended on that order
     * the three would settle on different answers and keep overwriting each other. Group timestamps
     * vary independently here, because that is what happens in practice: usage aggregation, the
     * sleep proxy and Health Connect all run on their own schedules.
     */
    @Test
    fun `merging is order independent`() {
        val random = Random(20260829)
        repeat(2_000) {
            val rows = (0 until 3).map {
                val hasSleep = random.nextInt(4) != 0
                SyncDailyMetric(
                    localDate = 20_000,
                    screenMinutes = maybe(random) { random.nextDouble(0.0, 600.0) },
                    socialMediaMinutes = maybe(random) { random.nextDouble(0.0, 300.0) },
                    lateNightScreenMinutes = maybe(random) { random.nextDouble(0.0, 120.0) },
                    unlockCount = maybe(random) { random.nextInt(0, 200) },
                    usageUpdatedAtUtc = random.nextLong(0, 12),
                    sleepMinutes = if (hasSleep) random.nextDouble(120.0, 600.0) else null,
                    sleepSource = if (hasSleep) {
                        listOf(null, "PROXY", "WEARABLE", "MANUAL")[random.nextInt(4)]
                    } else {
                        null
                    },
                    sleepStartUtc = if (hasSleep) random.nextLong(0, 100_000) else null,
                    sleepEndUtc = if (hasSleep) random.nextLong(0, 100_000) else null,
                    sleepUpdatedAtUtc = if (hasSleep) random.nextLong(0, 12) else 0,
                    steps = maybe(random) { random.nextDouble(0.0, 20_000.0) },
                    exerciseMinutes = maybe(random) { random.nextDouble(0.0, 180.0) },
                    restingHeartRate = maybe(random) { random.nextDouble(40.0, 90.0) },
                    hrv = maybe(random) { random.nextDouble(10.0, 120.0) },
                    healthUpdatedAtUtc = random.nextLong(0, 12),
                    updatedAtUtc = random.nextLong(0, 12),
                )
            }

            val (a, b, c) = rows
            val leftFirst = Merge.dailyMetric(Merge.dailyMetric(a, b), c)
            val rightFirst = Merge.dailyMetric(a, Merge.dailyMetric(b, c))
            val shuffled = Merge.dailyMetric(Merge.dailyMetric(c, a), b)

            assertEquals("left vs right for $rows", leftFirst, rightFirst)
            assertEquals("left vs shuffled for $rows", leftFirst, shuffled)
        }
    }

    @Test
    fun `merging check-ins is order independent`() {
        val random = Random(4242)
        repeat(500) {
            val times = generateSequence { random.nextLong(1, 1_000) }
                .distinct().take(3).toList()
            val rows = times.map { t ->
                checkIn(
                    mood = random.nextInt(-3, 4),
                    energy = random.nextInt(-3, 4),
                    note = maybe(random) { "note ${random.nextInt(100)}" },
                    tags = listOf("a", "b", "c").filter { random.nextBoolean() },
                    updatedAtUtc = t,
                    deleted = random.nextInt(5) == 0,
                )
            }
            val (a, b, c) = rows
            assertEquals(
                Merge.checkIn(Merge.checkIn(a, b), c),
                Merge.checkIn(a, Merge.checkIn(b, c)),
            )
            assertEquals(
                Merge.checkIn(Merge.checkIn(a, b), c),
                Merge.checkIn(Merge.checkIn(c, b), a),
            )
        }
    }

    /** Every pair must settle, including exact ties, which a clock set backwards makes common. */
    @Test
    fun `every pair of rows has a winner both sides agree on`() {
        val random = Random(7)
        repeat(2_000) {
            val shared = random.nextLong(1, 5)
            val a = checkIn(
                mood = random.nextInt(-3, 4),
                note = maybe(random) { "n${random.nextInt(3)}" },
                updatedAtUtc = shared,
                deleted = random.nextBoolean(),
            )
            val b = checkIn(
                mood = random.nextInt(-3, 4),
                note = maybe(random) { "n${random.nextInt(3)}" },
                updatedAtUtc = random.nextLong(1, 5),
                deleted = random.nextBoolean(),
            )
            val forward = Merge.checkIn(a, b)
            assertNotNull(forward)
            assertEquals(forward, Merge.checkIn(b, a))
        }
    }

    private fun <T> maybe(random: Random, value: () -> T): T? =
        if (random.nextInt(3) == 0) null else value()
}
