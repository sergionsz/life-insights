package dev.sergio.lifeinsights

import android.app.Application
import dev.sergio.lifeinsights.data.SettingsRepository
import dev.sergio.lifeinsights.data.TrackerRepository
import dev.sergio.lifeinsights.data.db.AppDatabase
import dev.sergio.lifeinsights.data.export.DataExporter
import dev.sergio.lifeinsights.insights.InsightsEngine
import dev.sergio.lifeinsights.work.Notifications
import dev.sergio.lifeinsights.work.ReminderScheduler
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
        }
    }
}
