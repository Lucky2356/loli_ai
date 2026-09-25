package ai.loli.app.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * После перезагрузки, обновления приложения, смены времени или разрешения на точные будильники
 * заново планирует напоминания (AlarmManager не переживает перезагрузку).
 * Фоновое прослушивание Android 14+ не разрешает запускать отсюда — показываем уведомление с кнопкой.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LoliApp
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.awaitReady()
                app.container.rescheduleReminders()
                if (intent.action == Intent.ACTION_BOOT_COMPLETED && app.container.settings.current().wakeWordEnabled) {
                    val open = PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    val n = NotificationCompat.Builder(context, Notifications.CHANNEL_SYSTEM)
                        .setSmallIcon(R.drawable.ic_stat_loli)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.wake_reactivate))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                    Notifications.notifySafely(context, Notifications.REACTIVATE_ID, n)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
