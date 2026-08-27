package dev.sergio.lifeinsights.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class UsageAggregatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val aggregator = UsageAggregator(zone)
    private val day: LocalDate = LocalDate.of(2026, 3, 10)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun resumed(pkg: String, t: Long) = PhoneEvent(t, PhoneEventType.ACTIVITY_RESUMED, pkg)
    private fun paused(pkg: String, t: Long) = PhoneEvent(t, PhoneEventType.ACTIVITY_PAUSED, pkg)

    @Test
    fun `sums foreground time per app`() {
        val events = listOf(
            resumed("com.social", at(day, 10, 0)),
            paused("com.social", at(day, 10, 30)),
            resumed("com.mail", at(day, 11, 0)),
            paused("com.mail", at(day, 11, 15)),
        )
        val result = aggregator.aggregate(events, at(day, 9), at(day, 12)).single()

        assertEquals(45.0, result.screenMinutes, 1e-6)
        assertEquals(30.0, result.perAppMinutes["com.social"]!!, 1e-6)
        assertEquals(15.0, result.perAppMinutes["com.mail"]!!, 1e-6)
    }

    @Test
    fun `a new app resuming ends the previous session`() {
        // No explicit pause: switching apps is the only signal the first one stopped.
        val events = listOf(
            resumed("com.social", at(day, 10, 0)),
            resumed("com.mail", at(day, 10, 20)),
            paused("com.mail", at(day, 10, 30)),
        )
        val result = aggregator.aggregate(events, at(day, 9), at(day, 11)).single()

        assertEquals(20.0, result.perAppMinutes["com.social"]!!, 1e-6)
        assertEquals(10.0, result.perAppMinutes["com.mail"]!!, 1e-6)
        assertEquals(30.0, result.screenMinutes, 1e-6)
    }

    /**
     * The failure that would quietly wreck the sleep proxy: a phone put down with an app open must
     * not accrue screen time all night.
     */
    @Test
    fun `screen off ends an open session`() {
        val events = listOf(
            resumed("com.social", at(day, 23, 0)),
            PhoneEvent(at(day, 23, 20), PhoneEventType.SCREEN_NON_INTERACTIVE),
        )
        val result = aggregator.aggregate(events, at(day, 22), at(day.plusDays(1), 3)).single()

        assertEquals(20.0, result.screenMinutes, 1e-6)
    }

    @Test
    fun `counts unlocks as pickups`() {
        val events = listOf(
            PhoneEvent(at(day, 9, 0), PhoneEventType.KEYGUARD_HIDDEN),
            PhoneEvent(at(day, 12, 0), PhoneEventType.KEYGUARD_HIDDEN),
            PhoneEvent(at(day, 12, 5), PhoneEventType.KEYGUARD_SHOWN),
            PhoneEvent(at(day, 18, 0), PhoneEventType.KEYGUARD_HIDDEN),
        )
        val result = aggregator.aggregate(events, at(day, 8), at(day, 20)).single()

        assertEquals(3, result.unlockCount)
    }

    /**
     * A phone with no PIN emits no keyguard events at all. Counting only unlocks would report zero
     * pickups forever, which looks like a broken feature rather than an absent lock screen.
     */
    @Test
    fun `falls back to screen-on when the device has no lock screen`() {
        val events = listOf(
            PhoneEvent(at(day, 9, 0), PhoneEventType.SCREEN_INTERACTIVE),
            PhoneEvent(at(day, 9, 30), PhoneEventType.SCREEN_NON_INTERACTIVE),
            PhoneEvent(at(day, 14, 0), PhoneEventType.SCREEN_INTERACTIVE),
        )
        val result = aggregator.aggregate(events, at(day, 8), at(day, 20)).single()

        assertEquals(2, result.unlockCount)
    }

    @Test
    fun `prefers unlocks over screen-on when a lock screen exists`() {
        // Both event kinds present: the screen also turns on for notifications, so counting it
        // would inflate pickups on a device that does report unlocks.
        val events = listOf(
            PhoneEvent(at(day, 9, 0), PhoneEventType.SCREEN_INTERACTIVE),
            PhoneEvent(at(day, 9, 1), PhoneEventType.KEYGUARD_HIDDEN),
            PhoneEvent(at(day, 11, 0), PhoneEventType.SCREEN_INTERACTIVE),
            PhoneEvent(at(day, 12, 0), PhoneEventType.SCREEN_INTERACTIVE),
            PhoneEvent(at(day, 12, 1), PhoneEventType.KEYGUARD_HIDDEN),
        )
        val result = aggregator.aggregate(events, at(day, 8), at(day, 20)).single()

        assertEquals(2, result.unlockCount)
    }

    /**
     * Use at 01:00 belongs to the night that is ending. Splitting it onto the next calendar day
     * would file the behaviour and the mood rating it relates to on different rows.
     */
    @Test
    fun `after-midnight use counts towards the day that is ending`() {
        val events = listOf(
            resumed("com.social", at(day.plusDays(1), 0, 30)),
            paused("com.social", at(day.plusDays(1), 1, 30)),
        )
        val result = aggregator.aggregate(events, at(day, 20), at(day.plusDays(1), 3)).single()

        assertEquals(day, result.date)
        assertEquals(60.0, result.screenMinutes, 1e-6)
    }

    @Test
    fun `a session spanning the 4am boundary is split between both days`() {
        val events = listOf(
            resumed("com.social", at(day.plusDays(1), 3, 30)),
            paused("com.social", at(day.plusDays(1), 4, 30)),
        )
        val result = aggregator.aggregate(events, at(day, 20), at(day.plusDays(1), 6))

        assertEquals(2, result.size)
        assertEquals(30.0, result.first { it.date == day }.screenMinutes, 1e-6)
        assertEquals(30.0, result.first { it.date == day.plusDays(1) }.screenMinutes, 1e-6)
    }

    @Test
    fun `late night minutes count only use after the configured hour`() {
        val events = listOf(
            resumed("com.social", at(day, 22, 30)),
            paused("com.social", at(day, 23, 30)),
        )
        val result = aggregator.aggregate(
            events, at(day, 20), at(day.plusDays(1), 3), lateNightHour = 23,
        ).single()

        assertEquals(60.0, result.screenMinutes, 1e-6)
        assertEquals(30.0, result.lateNightScreenMinutes, 1e-6)
    }

    @Test
    fun `late night minutes include the small hours before the cutoff`() {
        val events = listOf(
            resumed("com.social", at(day.plusDays(1), 1, 0)),
            paused("com.social", at(day.plusDays(1), 2, 0)),
        )
        val result = aggregator.aggregate(
            events, at(day, 20), at(day.plusDays(1), 3), lateNightHour = 23,
        ).single()

        assertEquals(60.0, result.lateNightScreenMinutes, 1e-6)
    }

    @Test
    fun `only user-chosen packages count as social media`() {
        val events = listOf(
            resumed("com.social", at(day, 10, 0)),
            paused("com.social", at(day, 10, 40)),
            resumed("com.work", at(day, 11, 0)),
            paused("com.work", at(day, 11, 40)),
        )
        val result = aggregator.aggregate(
            events, at(day, 9), at(day, 12), distractingPackages = setOf("com.social"),
        ).single()

        assertEquals(40.0, result.socialMediaMinutes, 1e-6)
        assertEquals(80.0, result.screenMinutes, 1e-6)
    }

    @Test
    fun `an app still open at the window end is closed there rather than running forever`() {
        val events = listOf(resumed("com.social", at(day, 10, 0)))
        val result = aggregator.aggregate(events, at(day, 9), at(day, 10, 30)).single()

        assertEquals(30.0, result.screenMinutes, 1e-6)
        assertTrue(result.screenMinutes < 24 * 60)
    }

    @Test
    fun `handles a spring-forward night without inventing or losing an hour`() {
        // Europe/Madrid skips 02:00-03:00 on 2026-03-29.
        val dst = LocalDate.of(2026, 3, 28)
        val events = listOf(
            resumed("com.social", at(dst.plusDays(1), 1, 30)),
            paused("com.social", at(dst.plusDays(1), 3, 30)),
        )
        val result = aggregator.aggregate(events, at(dst, 20), at(dst.plusDays(1), 6))

        // 01:30 to 03:30 local is one real hour on this date, because 02:00 does not exist.
        val total = result.sumOf { it.screenMinutes }
        assertEquals(60.0, total, 1e-6)
    }
}
