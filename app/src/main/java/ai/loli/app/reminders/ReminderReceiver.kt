package ai.loli.app.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.ui.MainActivity
import ai.loli.core.util.Logger
import kotlinx.coroutines.launch
import java.time.Instant

/** Срабатывание напоминания: уведомление + перенос следующего повтора. Действия «Готово» и «Отложить». */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext as LoliApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                val c = app.container
                when (intent.action) {
                    ACTION_FIRE -> {
                        val reminder = c.store.reminders.get(id)
                        if (reminder != null && reminder.active) {
                            show(context, id, reminder.text)
                            c.store.reminders.markFired(id, Instant.now())?.let { if (it.active) c.reminderScheduler.schedule(it) }
                        }
                    }
                    ACTION_DONE -> Notifications.cancel(context, notificationId(id))
                    ACTION_SNOOZE -> {
                        Notifications.cancel(context, notificationId(id))
                        c.store.reminders.get(id)?.let { r ->
                            val snoozed = c.store.reminders.update(r.copy(triggerAt = Instant.now().plusSeconds(SNOOZE_MINUTES * 60), active = true))
                            c.reminderScheduler.schedule(snoozed)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Ошибка обработки напоминания", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun show(context: Context, id: String, text: String) {
        val nid = notificationId(id)
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("loli://reminder/$id/$action")).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, nid, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.reminder_done), action(ACTION_DONE, 1))
            .addAction(0, context.getString(R.string.reminder_snooze), action(ACTION_SNOOZE, 2))
            .build()
        Notifications.notifySafely(context, nid, notification)
    }

    companion object {
        private const val TAG = "Reminder"
        const val ACTION_FIRE = "ai.loli.action.REMINDER_FIRE"
        const val ACTION_DONE = "ai.loli.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "ai.loli.action.REMINDER_SNOOZE"
        const val EXTRA_ID = "reminder_id"
        private const val SNOOZE_MINUTES = 10L
        fun notificationId(id: String) = 5000 + (id.hashCode() and 0x0fffffff) % 100000
    }
}
