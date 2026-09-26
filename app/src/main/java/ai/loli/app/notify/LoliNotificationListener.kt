package ai.loli.app.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import ai.loli.core.skills.IncomingMessage
import ai.loli.core.util.Logger
import java.time.Instant

/**
 * «Прочитай сообщения» и «ответь Маше…»: Лоли видит уведомления мессенджеров.
 * Сообщения хранятся только в памяти, пока уведомление висит в шторке, и никуда не отправляются.
 * Ответ уходит через кнопку «Ответить» самого уведомления — в тот же чат.
 */
class LoliNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications?.forEach { add(it) } }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = add(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(entries) { entries.removeAll { it.message.key == sbn.key } }
    }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val isMessaging = n.category == Notification.CATEGORY_MESSAGE || extras.containsKey(Notification.EXTRA_MESSAGES) ||
            sbn.packageName in MESSENGERS
        if (!isMessaging) return
        val (sender, text) = parse(extras) ?: return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val msg = IncomingMessage(sbn.key, app, sender, text, Instant.ofEpochMilli(sbn.postTime), canReply = reply != null)
        synchronized(entries) {
            entries.removeAll { it.message.key == sbn.key }
            entries.addFirst(Entry(msg, reply))
            while (entries.size > 40) entries.removeLast()
        }
    }

    /** Отправитель и текст: из MessagingStyle (последние сообщения чата) или из заголовка/текста. */
    private fun parse(extras: Bundle): Pair<String, String>? {
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return null
        val messages = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
        }.getOrNull().orEmpty()
        val text = messages.takeLast(3).joinToString(" · ").ifBlank {
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        }.trim()
        if (text.isBlank()) return null
        // «2 новых сообщения» — сводка без текста, не читаем.
        if (Regex("""^\d+\s+(?:новых?|new)\s""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return title.trim() to text
    }

    private data class Entry(val message: IncomingMessage, val reply: Notification.Action?)

    companion object {
        private const val TAG = "Messages"
        private val entries = ArrayDeque<Entry>()
        @Volatile private var connected = false

        private val MESSENGERS = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "com.whatsapp", "com.whatsapp.w4b",
            "com.vkontakte.android", "com.viber.voip", "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
            "ru.oneme.app", "com.discord", "com.facebook.orca", "com.instagram.android", "com.skype.raider", "ru.mail.mailapp",
        )

        fun enabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun messages(): List<IncomingMessage> = synchronized(entries) { entries.map { it.message } }

        /** Ответ в чат через «Ответить» уведомления. */
        fun reply(context: Context, key: String, text: String): Boolean {
            val action = synchronized(entries) { entries.firstOrNull { it.message.key == key }?.reply } ?: return false
            val inputs = action.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(context, 0, intent)
                synchronized(entries) { entries.removeAll { it.message.key == key } }
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Ответ не отправлен", e)
                false
            }
        }

        /** Экран «Доступ к уведомлениям», сразу на пункте Лоли (Android 11+). */
        fun settingsIntent(context: Context): Intent {
            val cn = ComponentName(context, LoliNotificationListener::class.java)
            return if (android.os.Build.VERSION.SDK_INT >= 30) {
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
