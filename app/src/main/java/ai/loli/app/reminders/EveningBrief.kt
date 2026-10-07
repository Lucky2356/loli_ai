package ai.loli.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.ui.MainActivity
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Вечерняя сводка в 21:00: настроение, закрытые задачи, траты, вода и привычки за день, и приглашение
 * записать, как прошёл день. По умолчанию выключена; только на телефоне.
 */
object EveningBrief {
    private const val WORK = "loli-evening-brief"
    private val AT: LocalTime = LocalTime.of(21, 0)

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now()
        var next = now.with(AT)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = (applicationContext as LoliApp).container
            if (!c.settings.current().eveningBrief) return Result.success()
            c.awaitReady()
            val text = c.eveningSummary() + "\nСкажите «как прошёл день», чтобы записать настроение."
            val open = PendingIntent.getActivity(
                applicationContext, Notifications.EVENING_ID, Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_loli)
                .setContentTitle("Итоги дня")
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // Настроение и траты — личное: на экране блокировки только заголовок.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_stat_loli).setContentTitle("Итоги дня готовы").build(),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            Notifications.notifySafely(applicationContext, Notifications.EVENING_ID, n)
            return Result.success()
        }
    }
}
