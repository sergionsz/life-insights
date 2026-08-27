package dev.sergio.lifeinsights.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sergio.lifeinsights.MainActivity
import dev.sergio.lifeinsights.R
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private const val CHANNEL_ID = "check_in_reminder"
private const val NOTIFICATION_ID = 1001
const val REMINDER_WORK_NAME = "daily_check_in_reminder"

class ReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Notifications.ensureChannel(applicationContext)
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return Result.success()

        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("How was today?")
            .setContentText("Twenty seconds: mood, energy, anything worth tagging.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and here; nothing useful to do.
        }
        return Result.success()
    }
}

object Notifications {

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Check-in reminder",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "A daily nudge to log mood and energy." }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

object ReminderScheduler {

    fun schedule(context: Context, time: LocalTime) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(Duration.ofDays(1))
            .setInitialDelay(delayUntil(time))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            REMINDER_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REMINDER_WORK_NAME)
    }

    private fun delayUntil(time: LocalTime): Duration {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
