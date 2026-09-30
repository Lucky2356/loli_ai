package ai.loli.app.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.loli.app.LoliApp
import ai.loli.core.util.Logger
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Страховка напоминаний. Оболочки телефонов (Xiaomi, Huawei, Oppo…) иногда стирают будильники приложения, и тогда
 * напоминание молчит до следующего запуска. Работа раз в 15 минут не зависит от AlarmManager: находит просроченные
 * активные напоминания, показывает их (с пометкой «Было в …») и заново ставит будильники остальным.
 */
object ReminderWatchdog {
    private const val WORK = "loli-reminder-watchdog"
    private const val TAG = "Watchdog"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = (applicationContext as LoliApp).container
            return try {
                c.awaitReady()
                val now = Instant.now()
                val (missed, upcoming) = c.store.reminders.active().partition { it.isMissed(now) }
                missed.forEach { ReminderReceiver.fire(applicationContext, c, it.id) }
                upcoming.forEach { c.reminderScheduler.schedule(it) }
                Result.success()
            } catch (e: Exception) {
                Logger.e(TAG, "Проверка напоминаний не удалась", e)
                Result.retry()
            }
        }
    }
}
