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
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Итоги недели по воскресеньям в 19:00: траты, задачи, настроение и привычки в сравнении с прошлой неделей.
 * По умолчанию выключены; только на телефоне.
 */
object WeeklyReview {
    private const val WORK = "loli-weekly-review"
    private val AT: LocalTime = LocalTime.of(19, 0)

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now()
        var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(AT).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val request = PeriodicWorkRequestBuilder<Worker>(7, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = (applicationContext as LoliApp).container
            if (!c.settings.current().weeklyReview) return Result.success()
            c.awaitReady()
            val text = c.engine.weeklySummary().removePrefix("Итоги недели. ")
            val open = PendingIntent.getActivity(
                applicationContext, Notifications.WEEKLY_ID, Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_loli)
                .setContentTitle("Итоги недели")
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // Траты и настроение — личное: на экране блокировки только заголовок.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_stat_loli).setContentTitle("Итоги недели готовы").build(),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            Notifications.notifySafely(applicationContext, Notifications.WEEKLY_ID, n)
            return Result.success()
        }
    }
}
