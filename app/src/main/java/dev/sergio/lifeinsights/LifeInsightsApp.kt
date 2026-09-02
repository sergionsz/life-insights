package dev.sergio.lifeinsights

import android.app.Application
import dev.sergio.lifeinsights.data.SettingsRepository
import dev.sergio.lifeinsights.data.TrackerRepository
import dev.sergio.lifeinsights.data.db.AppDatabase
import dev.sergio.lifeinsights.data.export.DataExporter
import dev.sergio.lifeinsights.insights.InsightsEngine
import dev.sergio.lifeinsights.sync.SyncEngine
import dev.sergio.lifeinsights.sync.SyncTarget
import dev.sergio.lifeinsights.usage.UsageRepository
import dev.sergio.lifeinsights.usage.UsageStatsSource
import dev.sergio.lifeinsights.work.AggregationScheduler
import dev.sergio.lifeinsights.work.Notifications
import dev.sergio.lifeinsights.work.ReminderScheduler
import dev.sergio.lifeinsights.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring. The graph is four objects deep; a DI framework would be more moving
 * parts than the app has dependencies.
 */
class LifeInsightsApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val repository: TrackerRepository by lazy { TrackerRepository(database) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val exporter: DataExporter by lazy { DataExporter(database) }
    val insightsEngine: InsightsEngine by lazy { InsightsEngine() }
    val usageStatsSource: UsageStatsSource by lazy { UsageStatsSource(this) }
    val usageRepository: UsageRepository by lazy {
        UsageRepository(usageStatsSource, database.dailyMetricDao())
    }
    val syncEngine: SyncEngine by lazy { SyncEngine(database) }

    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        scope.launch {
            repository.ensureDefaultTags()
            val current = settings.settings.first()
            if (current.reminderEnabled) {
                ReminderScheduler.schedule(this@LifeInsightsApp, current.reminderTime)
            }
            AggregationScheduler.schedule(this@LifeInsightsApp)

            // Recompute on every launch, not only from the worker. Usage events expire after about
            // a week, so a missed run is permanent data loss rather than a delay.
            runCatching {
                usageRepository.aggregateRecent(
                    distractingPackages = current.distractingPackages,
                    lateNightHour = current.lateNightHour,
                )
            }

            if (current.syncEnabled) {
                SyncScheduler.schedule(this@LifeInsightsApp)
                // Sync on launch as well as on the worker's schedule, so opening the app after
                // making changes on another device shows them rather than yesterday's picture.
                // Aggregation runs first, so anything it just computed goes up in the same pass.
                runCatching {
                    syncEngine.syncNow(SyncTarget(current.syncServerUrl, current.syncToken))
                }
            }
        }
    }
}
