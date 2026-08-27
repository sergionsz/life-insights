package dev.sergio.lifeinsights.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sergio.lifeinsights.LifeInsightsApp
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

const val AGGREGATION_WORK_NAME = "daily_usage_aggregation"

/**
 * Rebuilds the recent daily metrics in the background.
 *
 * This runs a little after the 04:00 day boundary so the day it is summarising has actually
 * finished. It is a convenience rather than the only path: the same aggregation runs whenever the
 * app is opened, because usage events expire after about a week and a worker silenced by an
 * aggressive battery manager would otherwise lose those days for good.
 */
class AggregationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LifeInsightsApp ?: return Result.success()
        return try {
            val settings = app.settings.settings.first()
            app.usageRepository.aggregateRecent(
                distractingPackages = settings.distractingPackages,
                lateNightHour = settings.lateNightHour,
            )
            Result.success()
        } catch (_: Exception) {
            // Nothing here is worth failing loudly for; the next app open recomputes the same
            // window anyway.
            Result.retry()
        }
    }
}

object AggregationScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AggregationWorker>(Duration.ofDays(1))
            .setInitialDelay(delayUntilAfterDayBoundary())
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AGGREGATION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 05:00 local: an hour past the day boundary, so the day being summarised is complete. */
    private fun delayUntilAfterDayBoundary(): Duration {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        var next = now.toLocalDate().atTime(LocalTime.of(5, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
