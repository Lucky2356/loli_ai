package ai.loli.app.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.loli.app.R

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_LISTENING = "listening"
    const val CHANNEL_SYSTEM = "system"
    const val CHANNEL_RESULTS = "results"
    /** Таймеры Лоли и «найди телефон»: звук играет сам сервис, у канала — без звука. */
    const val CHANNEL_ALARMS = "alarms"
    /** Радио и отсчёт таймеров. */
    const val CHANNEL_MEDIA = "media"
    const val RING_ID = 1010
    const val RADIO_ID = 1011
    const val TIMER_BASE_ID = 1100
    const val GEO_BASE_ID = 1200
    const val RESULT_ID = 1005
    const val LISTENING_ID = 1001
    const val REACTIVATE_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_reminders_desc)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, context.getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_listening_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, "Таймеры и поиск телефона", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Сигнал таймера Лоли и «Лоли, где ты?»"
                setSound(null, null)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MEDIA, "Радио и отсчёт таймера", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYSTEM, context.getString(R.string.channel_system), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.result_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                // Содержимое на экране блокировки — по системной настройке «скрывать личное».
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Результат голосовой команды, выполненной на заблокированном экране или в фоне. */
    fun showResult(context: Context, text: String) {
        val open = android.app.PendingIntent.getActivity(
            context, RESULT_ID,
            android.content.Intent(context, ai.loli.app.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Публичная версия для экрана блокировки, если пользователь скрывает личное содержимое.
        val public = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.result_hidden))
            .build()
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        notifySafely(context, RESULT_ID, n)
    }
}
