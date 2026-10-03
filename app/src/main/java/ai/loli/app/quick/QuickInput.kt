package ai.loli.app.quick

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.core.assistant.InputSource
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Быстрый ввод из шторки: постоянное уведомление с полем «Что записать?». Набрали «кофе 250» — Лоли записала расход,
 * не открывая приложение. Включается в настройках, по умолчанию выключено. На экране блокировки уведомление скрыто.
 */
object QuickInput {
    internal const val KEY = "text"
    const val ACTION = "ai.loli.action.QUICK_INPUT"

    /** Показать или убрать уведомление в соответствии с настройкой. */
    fun sync(context: Context, enabled: Boolean) {
        if (!enabled) { Notifications.cancel(context, Notifications.QUICK_ID); return }
        show(context)
    }

    internal fun show(context: Context) {
        val remote = RemoteInput.Builder(KEY).setLabel("Что записать? Например: кофе 250").build()
        val send = PendingIntent.getBroadcast(
            context, 0, Intent(context, QuickInputReceiver::class.java).setAction(ACTION),
            // RemoteInput дописывает введённый текст в интент: он обязан быть изменяемым.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        val action = NotificationCompat.Action.Builder(0, "Записать", send).addRemoteInput(remote).setAllowGeneratedReplies(false).build()
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_QUICK)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle("Быстрая запись")
            .setContentText("Трата, задача, заметка — одной строкой")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(action)
            .build()
        Notifications.notifySafely(context, Notifications.QUICK_ID, n)
    }
}

/** Принимает введённый в шторке текст, разбирает как обычную команду и показывает результат. */
class QuickInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickInput.ACTION) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(QuickInput.KEY)?.toString()?.trim().orEmpty()
        val app = context.applicationContext as LoliApp
        val pending = goAsync()
        app.container.appScope.launch(Dispatchers.IO) {
            try {
                if (text.isNotEmpty()) {
                    app.container.awaitReady()
                    val reply = app.container.engine.handle(text, InputSource.TEXT)
                    Notifications.showResult(context, reply.text.ifBlank { "Готово." })
                }
            } catch (e: Exception) {
                Logger.w("QuickInput", "Быстрая запись не удалась", e)
                Notifications.showResult(context, "Не получилось записать. Откройте Лоли и повторите.")
            } finally {
                // После ответа уведомление обновляется, иначе система продолжает показывать «отправка».
                if (app.container.settings.current().quickInput) QuickInput.show(context)
                pending.finish()
            }
        }
    }
}
