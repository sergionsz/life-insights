package dev.sergio.lifeinsights.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SleepProxyTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val proxy = SleepProxy(zone)
    private val wakeDay: LocalDate = LocalDate.of(2026, 3, 10)
    private val nightBefore: LocalDate = wakeDay.minusDays(1)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun touch(t: Long) = PhoneEvent(t, PhoneEventType.KEYGUARD_HIDDEN)

    @Test
    fun `finds the longest overnight gap in phone use`() {
        val events = listOf(
            touch(at(nightBefore, 22, 30)),
            touch(at(nightBefore, 23, 15)), // last look at the phone
            touch(at(wakeDay, 7, 20)),      // first look in the morning
            touch(at(wakeDay, 8, 0)),
        )
        val estimate = proxy.estimateFor(wakeDay, events)

        assertNotNull(estimate)
        assertEquals(at(nightBefore, 23, 15), estimate!!.startUtc)
        assertEquals(at(wakeDay, 7, 20), estimate.endUtc)
        assertEquals(485.0, estimate.minutes, 1e-6)
        assertEquals(wakeDay, estimate.wakeDay)
    }

    @Test
    fun `a brief night-time check does not split the night in two`() {
        val events = listOf(
            touch(at(nightBefore, 23, 0)),
            touch(at(wakeDay, 3, 10)), // a 3am glance at the clock
            touch(at(wakeDay, 7, 30)),
        )
        val estimate = proxy.estimateFor(wakeDay, events)!!

        // The longer of the two stretches wins: 23:00-03:10 is 250 minutes, 03:10-07:30 is 260.
        assertEquals(at(wakeDay, 3, 10), estimate.startUtc)
        assertEquals(260.0, estimate.minutes, 1e-6)
    }

    @Test
    fun `an evening away from the phone is not reported as sleep`() {
        val events = listOf(
            touch(at(nightBefore, 20, 30)),
            touch(at(nightBefore, 22, 45)), // a two-hour dinner, not sleep
            touch(at(wakeDay, 0, 30)),
            touch(at(wakeDay, 1, 0)),
            touch(at(wakeDay, 2, 0)),
            touch(at(wakeDay, 3, 0)),
            touch(at(wakeDay, 4, 0)),
            touch(at(wakeDay, 5, 0)),
            touch(at(wakeDay, 6, 0)),
            touch(at(wakeDay, 7, 0)),
            touch(at(wakeDay, 8, 0)),
        )
        assertNull("gaps under three hours are not sleep", proxy.estimateFor(wakeDay, events))
    }

    /**
     * A phone left on a charger in another room for days must not report 72 hours of sleep, so the
     * estimate is clipped to the night window.
     */
    @Test
    fun `an unused phone is clipped to the night window`() {
        val events = listOf(
            touch(at(nightBefore.minusDays(2), 12, 0)),
            touch(at(wakeDay.plusDays(2), 12, 0)),
        )
        val estimate = proxy.estimateFor(wakeDay, events)!!

        assertEquals(at(nightBefore, 20, 0), estimate.startUtc)
        assertEquals(at(wakeDay, 12, 0), estimate.endUtc)
        assertTrue("clipped to the 16-hour window", estimate.minutes <= 16 * 60.0)
    }

    @Test
    fun `screen off and lock events are not treated as interaction`() {
        // Only the lock event sits in the middle of the night; it must not break the gap, because
        // the screen turning itself off is exactly what happens when someone falls asleep.
        val events = listOf(
            touch(at(nightBefore, 23, 0)),
            PhoneEvent(at(wakeDay, 2, 0), PhoneEventType.SCREEN_NON_INTERACTIVE),
            PhoneEvent(at(wakeDay, 2, 1), PhoneEventType.KEYGUARD_SHOWN),
            touch(at(wakeDay, 7, 0)),
        )
        val estimate = proxy.estimateFor(wakeDay, events)!!

        assertEquals(480.0, estimate.minutes, 1e-6)
    }

    @Test
    fun `nights not covered by the event history are skipped rather than guessed`() {
        val events = listOf(touch(at(wakeDay, 7, 0)))
        // Events only reach back to this morning, so last night is not actually observed.
        val estimates = proxy.estimateRange(
            wakeDays = listOf(wakeDay),
            events = events,
            earliestEventUtc = at(wakeDay, 6, 0),
        )
        assertTrue("a half-covered night must produce no estimate", estimates.isEmpty())
    }

    @Test
    fun `a fully covered night is estimated`() {
        val events = listOf(
            touch(at(nightBefore, 23, 0)),
            touch(at(wakeDay, 7, 0)),
        )
        val estimates = proxy.estimateRange(
            wakeDays = listOf(wakeDay),
            events = events,
            earliestEventUtc = at(nightBefore, 12, 0),
        )
        assertEquals(1, estimates.size)
        assertEquals(480.0, estimates.single().minutes, 1e-6)
    }

    @Test
    fun `sleep is attributed to the morning it ends`() {
        val events = listOf(
            touch(at(nightBefore, 23, 30)),
            touch(at(wakeDay, 7, 30)),
        )
        val estimate = proxy.estimateFor(wakeDay, events)!!
        assertEquals("last night's sleep is today's number", wakeDay, estimate.wakeDay)
    }
}
