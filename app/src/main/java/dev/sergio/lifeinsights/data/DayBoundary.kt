package dev.sergio.lifeinsights.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Maps an instant to the day it belongs to.
 *
 * A check-in at 01:30 belongs to the night that just happened, not to the new calendar day, so the
 * day boundary sits at [CUTOFF_HOUR] rather than at midnight. Getting this wrong shifts a portion
 * of entries onto the following day and quietly corrupts every lagged correlation downstream.
 *
 * Instants are always stored as UTC millis alongside the zone they were recorded in, so travel and
 * daylight saving do not retroactively move existing entries.
 */
object DayBoundary {

    /** Entries before this local hour count towards the previous day. */
    const val CUTOFF_HOUR = 4

    fun dayOf(instant: Instant, zone: ZoneId): LocalDate =
        instant.atZone(zone).minusHours(CUTOFF_HOUR.toLong()).toLocalDate()

    fun dayOf(epochMillis: Long, zoneId: String): LocalDate =
        dayOf(Instant.ofEpochMilli(epochMillis), ZoneId.of(zoneId))

    /**
     * The day a sleep session belongs to is the day it ENDS -- the morning you woke up. Last
     * night's sleep is therefore today's sleep, which is what "sleep at t-1" needs to mean for the
     * lagged analysis to be interpretable.
     */
    fun sleepDayOf(endInstant: Instant, zone: ZoneId): LocalDate =
        dayOf(endInstant, zone)
}
