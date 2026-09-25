package ai.loli.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import ai.loli.core.assistant.ReminderScheduler
import ai.loli.core.model.Reminder
import ai.loli.core.util.Logger

/**
 * Планирование напоминаний через AlarmManager.
 * Точные будильники (setExactAndAllowWhileIdle) — если пользователь разрешил «Будильники и напоминания»
 * (Android 12+, SCHEDULE_EXACT_ALARM). Иначе — почти точные (setAndAllowWhileIdle, возможна задержка в режиме Doze).
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    override fun schedule(reminder: Reminder) {
        if (!reminder.active) { cancel(reminder.id); return }
        val at = maxOf(reminder.triggerAt.toEpochMilli(), System.currentTimeMillis() + 2_000)
        val pi = pendingIntent(reminder.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Logger.w(TAG, "Точные будильники запрещены — ставлю неточный", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun cancel(reminderId: String) {
        pendingIntent(reminderId, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    fun rescheduleAll(reminders: List<Reminder>) = reminders.forEach { schedule(it) }

    private fun pendingIntent(id: String, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("loli://reminder/$id"))
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object { private const val TAG = "Alarms" }
}
