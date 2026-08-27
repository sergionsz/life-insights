package dev.sergio.lifeinsights.usage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class SleepEstimate(
    /** The day the sleep is attributed to: the morning the person woke up. */
    val wakeDay: LocalDate,
    val startUtc: Long,
    val endUtc: Long,
) {
    val minutes: Double get() = (endUtc - startUtc) / 60_000.0
}

/**
 * Infers a sleep window from the absence of phone interaction, for use when no wearable supplies
 * real sleep sessions.
 *
 * This is an estimate and the UI must always say so. It is wrong when the phone is left charging in
 * another room, when someone reads a paper book for an hour before sleeping, and for anyone on
 * shifts. The point is not accuracy in absolute minutes but consistency: if it is biased the same
 * way every night, night-to-night *changes* still carry the signal a correlation needs.
 *
 * A sleep window is attributed to the day it ends, so "last night's sleep" is today's number and
 * "sleep at t-1" in the lagged analysis means the night before yesterday.
 */
class SleepProxy(
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Earliest hour a night is allowed to start, on the evening before the wake day. */
    private val nightStartHour: Int = 20,
    /** Latest hour a night is allowed to end, on the wake day. */
    private val nightEndHour: Int = 12,
    /** Shorter gaps than this are treated as an evening away from the phone, not as sleep. */
    private val minSleepMinutes: Int = 180,
) {

    fun estimateFor(wakeDay: LocalDate, events: List<PhoneEvent>): SleepEstimate? {
        val windowStart = wakeDay.minusDays(1)
            .atTime(LocalTime.of(nightStartHour, 0)).atZone(zone).toInstant().toEpochMilli()
        val windowEnd = wakeDay
            .atTime(LocalTime.of(nightEndHour, 0)).atZone(zone).toInstant().toEpochMilli()

        val interactions = events
            .filter { it.type.indicatesInteraction }
            .map { it.timestampUtc }
            .sorted()

        // Only gaps bounded by real phone interaction on BOTH sides count as sleep.
        //
        // It is tempting to also anchor on the window edges, but a stretch of silence that merely
        // runs to the edge of the observation window is not evidence of sleep: someone who glances
        // at their phone at 07:30 and then leaves it alone until noon would otherwise have that
        // morning reported as four hours of sleep. The last interaction before the window and the
        // first after it are still usable, because those are real interactions.
        val before = interactions.lastOrNull { it <= windowStart }
        val after = interactions.firstOrNull { it >= windowEnd }
        val inside = interactions.filter { it > windowStart && it < windowEnd }
        val anchors = listOfNotNull(before) + inside + listOfNotNull(after)

        var bestStart = 0L
        var bestEnd = 0L
        for (i in 0 until anchors.size - 1) {
            // Clip to the night window so a phone unused for three days does not report 72 hours.
            val gapStart = maxOf(anchors[i], windowStart)
            val gapEnd = minOf(anchors[i + 1], windowEnd)
            if (gapEnd - gapStart > bestEnd - bestStart) {
                bestStart = gapStart
                bestEnd = gapEnd
            }
        }

        val minutes = (bestEnd - bestStart) / 60_000.0
        if (minutes < minSleepMinutes) return null
        return SleepEstimate(wakeDay, bestStart, bestEnd)
    }

    /**
     * Estimates every night whose full window is covered by [events].
     *
     * A night is skipped rather than guessed when the event history does not reach back far enough
     * to cover it: usage events expire after about a week, and a half-covered night would produce a
     * confidently wrong short sleep rather than an honest gap.
     */
    fun estimateRange(
        wakeDays: List<LocalDate>,
        events: List<PhoneEvent>,
        earliestEventUtc: Long,
    ): List<SleepEstimate> = wakeDays.mapNotNull { day ->
        val windowStart = day.minusDays(1)
            .atTime(LocalTime.of(nightStartHour, 0)).atZone(zone).toInstant().toEpochMilli()
        if (windowStart < earliestEventUtc) return@mapNotNull null
        estimateFor(day, events)
    }

    fun instant(utcMillis: Long): Instant = Instant.ofEpochMilli(utcMillis)
}
