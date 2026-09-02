package dev.sergio.lifeinsights.usage

import dev.sergio.lifeinsights.data.DayBoundary
import dev.sergio.lifeinsights.data.db.DailyMetricDao
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import dev.sergio.lifeinsights.data.db.SleepSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Recomputes daily screen and sleep-proxy metrics from phone events and writes them to the
 * `daily_metric` table.
 *
 * Two properties this has to have:
 *
 *  - **Idempotent.** It runs from a daily worker *and* on every app open, and re-running it must
 *    produce the same row rather than doubling anything.
 *  - **Non-destructive.** It only overwrites the fields it computes. A sleep figure that came from
 *    a wearable, or one the user corrected by hand, outranks the proxy and must survive a
 *    re-aggregation.
 */
class UsageRepository(
    private val source: UsageStatsSource,
    private val dailyMetricDao: DailyMetricDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun hasPermission(): Boolean = source.hasPermission()

    /**
     * Re-aggregates the trailing [days] days.
     *
     * A trailing window rather than "yesterday only" is deliberate: usage events expire after about
     * a week and cannot be recovered, so a worker that misses a few runs (Doze, or an aggressive
     * battery manager) would otherwise lose those days permanently. Re-running the whole window on
     * every app open costs little and makes the daily worker a convenience rather than a
     * single point of failure.
     */
    suspend fun aggregateRecent(
        days: Int = 7,
        distractingPackages: Set<String> = emptySet(),
        lateNightHour: Int = 23,
    ): AggregationResult {
        if (!source.hasPermission()) return AggregationResult(0, null, permissionMissing = true)

        val now = System.currentTimeMillis()
        val today = DayBoundary.dayOf(Instant.ofEpochMilli(now), zone)
        // Reach back one extra day so the earliest night's window is fully covered.
        val windowStart = today.minusDays((days + 1).toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val events = source.queryEvents(windowStart, now)
        if (events.isEmpty()) return AggregationResult(0, null)

        val earliestEvent = events.first().timestampUtc

        val usageDays = UsageAggregator(zone).aggregate(
            events = events,
            windowStartUtc = windowStart,
            windowEndUtc = now,
            distractingPackages = distractingPackages,
            lateNightHour = lateNightHour,
        )

        val wakeDays = (0..days).map { today.minusDays(it.toLong()) }.sorted()
        val sleepByDay = SleepProxy(zone)
            .estimateRange(wakeDays, events, earliestEvent)
            .associateBy { it.wakeDay }

        val touched = (usageDays.map { it.date } + sleepByDay.keys).distinct().sorted()
        for (day in touched) {
            writeDay(day, usageDays.firstOrNull { it.date == day }, sleepByDay[day], now)
        }

        return AggregationResult(touched.size, earliestEvent)
    }

    private suspend fun writeDay(
        day: LocalDate,
        usage: UsageDay?,
        sleep: SleepEstimate?,
        now: Long,
    ) {
        val existing = dailyMetricDao.find(day.toEpochDay())
        val base = existing ?: DailyMetricEntity(localDate = day.toEpochDay())

        // A wearable reading or a manual correction beats the proxy; never overwrite one.
        val keepExistingSleep = existing?.sleepSource != null &&
            existing.sleepSource != SleepSource.PROXY.name
        val takeNewSleep = sleep != null && !keepExistingSleep

        val merged = base.copy(
            screenMinutes = usage?.screenMinutes ?: base.screenMinutes,
            socialMediaMinutes = usage?.socialMediaMinutes ?: base.socialMediaMinutes,
            lateNightScreenMinutes = usage?.lateNightScreenMinutes ?: base.lateNightScreenMinutes,
            unlockCount = usage?.unlockCount ?: base.unlockCount,
            sleepMinutes = if (takeNewSleep) sleep!!.minutes else base.sleepMinutes,
            sleepSource = if (takeNewSleep) SleepSource.PROXY.name else base.sleepSource,
            sleepStartUtc = if (takeNewSleep) sleep!!.startUtc else base.sleepStartUtc,
            sleepEndUtc = if (takeNewSleep) sleep!!.endUtc else base.sleepEndUtc,
        )

        // Nothing new to record. Writing anyway would be harmless on its own, but it would bump the
        // timestamps and flag the row for upload, and since this runs on every app open it would
        // hand the server the same week of unchanged rows several times a day.
        if (existing != null && merged.sameValuesAs(existing)) return

        dailyMetricDao.upsert(
            merged.copy(
                // Each group carries its own timestamp so that sync can merge them independently;
                // stamping all three here would claim this run produced readings it never saw.
                usageUpdatedAtUtc = if (usage != null) now else base.usageUpdatedAtUtc,
                sleepUpdatedAtUtc = if (takeNewSleep) now else base.sleepUpdatedAtUtc,
                updatedAtUtc = now,
                dirty = true,
            ),
        )
    }

    /** A user correction to a night the proxy got wrong. Outranks the proxy permanently. */
    suspend fun setManualSleep(day: LocalDate, startUtc: Long, endUtc: Long) {
        val existing = dailyMetricDao.find(day.toEpochDay())
            ?: DailyMetricEntity(localDate = day.toEpochDay())
        val now = System.currentTimeMillis()
        dailyMetricDao.upsert(
            existing.copy(
                sleepMinutes = (endUtc - startUtc) / 60_000.0,
                sleepSource = SleepSource.MANUAL.name,
                sleepStartUtc = startUtc,
                sleepEndUtc = endUtc,
                sleepUpdatedAtUtc = now,
                updatedAtUtc = now,
                dirty = true,
            ),
        )
    }
}

/**
 * Compares only the measurements, ignoring the bookkeeping columns.
 *
 * Timestamps and the pending flag change on every write by definition, so including them would make
 * every row look different from itself and defeat the check that uses this.
 */
private fun DailyMetricEntity.sameValuesAs(other: DailyMetricEntity): Boolean =
    copy(usageUpdatedAtUtc = 0, sleepUpdatedAtUtc = 0, healthUpdatedAtUtc = 0, updatedAtUtc = 0, dirty = false) ==
        other.copy(usageUpdatedAtUtc = 0, sleepUpdatedAtUtc = 0, healthUpdatedAtUtc = 0, updatedAtUtc = 0, dirty = false)

data class AggregationResult(
    val daysWritten: Int,
    val earliestEventUtc: Long?,
    val permissionMissing: Boolean = false,
)
