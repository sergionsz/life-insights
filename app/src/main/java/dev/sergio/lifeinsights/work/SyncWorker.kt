package dev.sergio.lifeinsights.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sergio.lifeinsights.LifeInsightsApp
import dev.sergio.lifeinsights.sync.SyncResult
import dev.sergio.lifeinsights.sync.SyncTarget
import kotlinx.coroutines.flow.first
import java.time.Duration

const val SYNC_WORK_NAME = "server_sync"

/**
 * Periodic sync in the background.
 *
 * Unlike the usage aggregation, missing a run here loses nothing: local changes stay flagged as
 * pending and go up whenever the next sync happens, whether that is this worker or the app being
 * opened. So this asks only for a network connection and is content to be retried.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LifeInsightsApp ?: return Result.success()
        val settings = app.settings.settings.first()
        if (!settings.syncEnabled) return Result.success()

        val target = SyncTarget(settings.syncServerUrl, settings.syncToken)
        return when (app.syncEngine.syncNow(target)) {
            is SyncResult.Success, SyncResult.NotConfigured -> Result.success()
            // The engine has already recorded why, and Settings shows it. Retrying with backoff
            // covers the ordinary cases: no signal right now, or a server still starting up.
            is SyncResult.Failure -> Result.retry()
        }
    }
}

object SyncScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(6))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            // REPLACE rather than KEEP: the schedule is switched on and off from Settings, so a
            // request enqueued under an older configuration should not outlive it.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
    }
}
