package dev.sergio.lifeinsights.usage

import dev.sergio.lifeinsights.data.DayBoundary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Per-day screen numbers, keyed by the local day as defined by [DayBoundary]. */
data class UsageDay(
    val date: LocalDate,
    val screenMinutes: Double,
    val socialMediaMinutes: Double,
    val lateNightScreenMinutes: Double,
    val unlockCount: Int,
    val perAppMinutes: Map<String, Double>,
)

/**
 * Turns raw phone events into daily screen metrics.
 *
 * Two things here are deliberate and easy to get wrong:
 *
 *  - **Total screen time is derived from foreground app sessions**, not from screen-on events.
 *    `SCREEN_INTERACTIVE` and `SCREEN_NON_INTERACTIVE` are inconsistently reported across
 *    manufacturers, whereas resumed/paused pairs are dependable. This slightly undercounts (a lock
 *    screen with no app open is not counted) but it undercounts *consistently*, which is what a
 *    correlation needs. Screen time remains the least trustworthy metric in the app.
 *  - **Days end at 04:00, not midnight**, matching the check-in day boundary. Scrolling at 01:00
 *    belongs to the night that is ending, and splitting it onto the next calendar day would put the
 *    behaviour and the mood rating it relates to on different rows.
 */
class UsageAggregator(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun aggregate(
        events: List<PhoneEvent>,
        windowStartUtc: Long,
        windowEndUtc: Long,
        distractingPackages: Set<String> = emptySet(),
        lateNightHour: Int = 23,
    ): List<UsageDay> {
        val sessions = foregroundSessions(events, windowEndUtc)

        val screenByDay = HashMap<LocalDate, Double>()
        val lateNightByDay = HashMap<LocalDate, Double>()
        val socialByDay = HashMap<LocalDate, Double>()
        val perAppByDay = HashMap<LocalDate, HashMap<String, Double>>()

        for (session in sessions) {
            for ((day, slice) in splitAcrossDays(session.startUtc, session.endUtc)) {
                val minutes = (slice.second - slice.first) / 60_000.0
                if (minutes <= 0) continue

                screenByDay.merge(day, minutes, Double::plus)
                perAppByDay.getOrPut(day) { HashMap() }
                    .merge(session.packageName, minutes, Double::plus)
                if (session.packageName in distractingPackages) {
                    socialByDay.merge(day, minutes, Double::plus)
                }
                val lateMinutes = lateNightMinutes(slice.first, slice.second, day, lateNightHour)
                if (lateMinutes > 0) lateNightByDay.merge(day, lateMinutes, Double::plus)
            }
        }

        val unlocksByDay = events
            .filter { it.type == pickupEventType(events) }
            .filter { it.timestampUtc in windowStartUtc..windowEndUtc }
            .groupingBy { dayOf(it.timestampUtc) }
            .eachCount()

        val days = (screenByDay.keys + unlocksByDay.keys).sorted()
        return days.map { day ->
            UsageDay(
                date = day,
                screenMinutes = screenByDay[day] ?: 0.0,
                socialMediaMinutes = socialByDay[day] ?: 0.0,
                lateNightScreenMinutes = lateNightByDay[day] ?: 0.0,
                unlockCount = unlocksByDay[day] ?: 0,
                perAppMinutes = perAppByDay[day].orEmpty(),
            )
        }
    }

    /**
     * Which event counts as picking the phone up.
     *
     * Unlocking is the truest signal, but a device with no PIN or pattern emits no keyguard events
     * at all, and counting them would report zero pickups forever, which reads as a broken feature
     * rather than as an absent lock screen. Where there is no keyguard, the screen turning on is
     * the same gesture.
     */
    fun pickupEventType(events: List<PhoneEvent>): PhoneEventType {
        val hasKeyguard = events.any {
            it.type == PhoneEventType.KEYGUARD_HIDDEN || it.type == PhoneEventType.KEYGUARD_SHOWN
        }
        return if (hasKeyguard) PhoneEventType.KEYGUARD_HIDDEN else PhoneEventType.SCREEN_INTERACTIVE
    }

    /**
     * Reconstructs foreground sessions from resumed/paused pairs.
     *
     * Only one activity is foreground at a time, so a new resume implicitly ends the previous
     * session. Screen-off and lock also end it: without that, a phone put down with an app open
     * would accrue screen time all night and the sleep proxy would be contradicted by its own
     * screen-time figure.
     */
    fun foregroundSessions(events: List<PhoneEvent>, windowEndUtc: Long): List<ForegroundSession> {
        val sessions = ArrayList<ForegroundSession>()
        var openPackage: String? = null
        var openedAt = 0L

        fun close(at: Long) {
            val pkg = openPackage ?: return
            if (at > openedAt) sessions.add(ForegroundSession(pkg, openedAt, at))
            openPackage = null
        }

        for (event in events.sortedBy { it.timestampUtc }) {
            when (event.type) {
                PhoneEventType.ACTIVITY_RESUMED -> {
                    close(event.timestampUtc)
                    if (event.packageName != null) {
                        openPackage = event.packageName
                        openedAt = event.timestampUtc
                    }
                }

                PhoneEventType.ACTIVITY_PAUSED -> {
                    if (event.packageName == openPackage) close(event.timestampUtc)
                }

                PhoneEventType.SCREEN_NON_INTERACTIVE,
                PhoneEventType.KEYGUARD_SHOWN,
                -> close(event.timestampUtc)

                PhoneEventType.KEYGUARD_HIDDEN,
                PhoneEventType.SCREEN_INTERACTIVE,
                PhoneEventType.USER_INTERACTION,
                -> Unit
            }
        }
        // An app still in the foreground when the window ends is closed there rather than left to
        // run to infinity.
        close(windowEndUtc)
        return sessions
    }

    /**
     * Splits an interval at day boundaries, so a session running through 04:00 contributes to both
     * days in the right proportion instead of landing entirely on whichever day it started.
     */
    fun splitAcrossDays(startUtc: Long, endUtc: Long): List<Pair<LocalDate, Pair<Long, Long>>> {
        if (endUtc <= startUtc) return emptyList()
        val result = ArrayList<Pair<LocalDate, Pair<Long, Long>>>()
        var cursor = startUtc
        while (cursor < endUtc) {
            val day = dayOf(cursor)
            val boundary = endOfDayUtc(day)
            val sliceEnd = minOf(boundary, endUtc)
            result.add(day to (cursor to sliceEnd))
            if (sliceEnd <= cursor) break // defensive: never loop forever on a clock oddity
            cursor = sliceEnd
        }
        return result
    }

    /**
     * Minutes of an interval that fall in the late-night stretch: from [lateNightHour] on the
     * evening of [day] through to that day's 04:00 cutoff.
     */
    fun lateNightMinutes(
        startUtc: Long,
        endUtc: Long,
        day: LocalDate,
        lateNightHour: Int,
    ): Double {
        val lateStart = day.atTime(LocalTime.of(lateNightHour, 0)).atZone(zone).toInstant().toEpochMilli()
        val lateEnd = endOfDayUtc(day)
        val overlapStart = maxOf(startUtc, lateStart)
        val overlapEnd = minOf(endUtc, lateEnd)
        return ((overlapEnd - overlapStart).coerceAtLeast(0)) / 60_000.0
    }

    private fun dayOf(utcMillis: Long): LocalDate =
        DayBoundary.dayOf(Instant.ofEpochMilli(utcMillis), zone)

    /** The instant a given tracking day ends: 04:00 on the following calendar morning. */
    private fun endOfDayUtc(day: LocalDate): Long =
        day.plusDays(1)
            .atTime(LocalTime.of(DayBoundary.CUTOFF_HOUR, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
