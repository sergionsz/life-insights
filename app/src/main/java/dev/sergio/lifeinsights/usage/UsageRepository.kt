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

        // A wearable reading or a manual correction beats the proxy; never overwrite one.
        val keepExistingSleep = existing?.sleepSource != null &&
            existing.sleepSource != SleepSource.PROXY.name

        val merged = (existing ?: DailyMetricEntity(localDate = day.toEpochDay())).copy(
            screenMinutes = usage?.screenMinutes ?: existing?.screenMinutes,
            socialMediaMinutes = usage?.socialMediaMinutes ?: existing?.socialMediaMinutes,
            lateNightScreenMinutes = usage?.lateNightScreenMinutes
                ?: existing?.lateNightScreenMinutes,
            unlockCount = usage?.unlockCount ?: existing?.unlockCount,
            sleepMinutes = if (keepExistingSleep) existing?.sleepMinutes
            else sleep?.minutes ?: existing?.sleepMinutes.takeIf { existing?.sleepSource == null },
            sleepSource = if (keepExistingSleep) existing?.sleepSource
            else sleep?.let { SleepSource.PROXY.name } ?: existing?.sleepSource,
            sleepStartUtc = if (keepExistingSleep) existing?.sleepStartUtc
            else sleep?.startUtc ?: existing?.sleepStartUtc,
            sleepEndUtc = if (keepExistingSleep) existing?.sleepEndUtc
            else sleep?.endUtc ?: existing?.sleepEndUtc,
            updatedAtUtc = now,
        )
        dailyMetricDao.upsert(merged)
    }

    /** A user correction to a night the proxy got wrong. Outranks the proxy permanently. */
    suspend fun setManualSleep(day: LocalDate, startUtc: Long, endUtc: Long) {
        val existing = dailyMetricDao.find(day.toEpochDay())
            ?: DailyMetricEntity(localDate = day.toEpochDay())
        dailyMetricDao.upsert(
            existing.copy(
                sleepMinutes = (endUtc - startUtc) / 60_000.0,
                sleepSource = SleepSource.MANUAL.name,
                sleepStartUtc = startUtc,
                sleepEndUtc = endUtc,
                updatedAtUtc = System.currentTimeMillis(),
            ),
        )
    }
}

data class AggregationResult(
    val daysWritten: Int,
    val earliestEventUtc: Long?,
    val permissionMissing: Boolean = false,
)
