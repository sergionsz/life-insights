package dev.sergio.lifeinsights.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * The only place that touches `UsageStatsManager`.
 *
 * Everything that decides what the numbers mean lives in [UsageAggregator] and [SleepProxy], which
 * work on plain data and are unit-tested. This class just reads and translates.
 */
class UsageStatsSource(private val context: Context) {

    private val usageStatsManager: UsageStatsManager?
        get() = context.getSystemService(UsageStatsManager::class.java)

    /**
     * Usage access is a *special* permission: it cannot be requested with a runtime dialog, only
     * granted by the user in Settings, and the only way to know is to ask AppOps.
     */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Deep-links to the Usage access screen, since there is no in-app way to grant this. */
    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun queryEvents(startUtc: Long, endUtc: Long): List<PhoneEvent> {
        val manager = usageStatsManager ?: return emptyList()
        if (!hasPermission()) return emptyList()

        val result = ArrayList<PhoneEvent>()
        val events = manager.queryEvents(startUtc, endUtc)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> PhoneEventType.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> PhoneEventType.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> PhoneEventType.ACTIVITY_PAUSED
                UsageEvents.Event.KEYGUARD_HIDDEN -> PhoneEventType.KEYGUARD_HIDDEN
                UsageEvents.Event.KEYGUARD_SHOWN -> PhoneEventType.KEYGUARD_SHOWN
                UsageEvents.Event.SCREEN_INTERACTIVE -> PhoneEventType.SCREEN_INTERACTIVE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> PhoneEventType.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.USER_INTERACTION -> PhoneEventType.USER_INTERACTION
                else -> null
            }
            if (type != null) {
                result.add(PhoneEvent(event.timeStamp, type, event.packageName))
            }
        }
        return result.sortedBy { it.timestampUtc }
    }

    /**
     * How far back readable events actually go.
     *
     * Android trims usage events after roughly a week, and there is no way to backfill. The
     * aggregation window is chosen from this rather than from a hopeful constant, and the sleep
     * proxy uses it to skip nights it cannot actually see.
     */
    fun earliestAvailableEventUtc(probeDays: Long = 30): Long? {
        val now = System.currentTimeMillis()
        val from = now - probeDays * 24 * 60 * 60 * 1000
        return queryEvents(from, now).firstOrNull()?.timestampUtc
    }

    /** Launchable apps, for the "which apps distract you" picker. */
    fun launchableApps(): List<InstalledApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                InstalledApp(pkg, info.loadLabel(packageManager).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun labelFor(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}
