package ai.loli.app.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import ai.loli.app.AppContainer
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.ui.MainActivity
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Routine
import ai.loli.core.voice.SpeechText
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Срабатывание напоминания: уведомление + перенос следующего повтора. Действия «Готово» и «Отложить». */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext as LoliApp
        val pending = goAsync()
        app.container.appScope.launch(Dispatchers.IO) {
            try {
                val c = app.container
                when (intent.action) {
                    ACTION_FIRE -> fire(context, c, id)
                    ACTION_DONE -> {
                        Notifications.cancel(context, notificationId(id))
                        // «Готово» останавливает и настойчивые повторы.
                        c.nags.clear(id)
                        c.store.reminders.get(id)?.let { r ->
                            if (r.recurrence == null && r.active) {
                                c.store.reminders.update(r.copy(active = false))
                                c.reminderScheduler.cancel(id)
                            }
                        }
                    }
                    ACTION_SNOOZE, ACTION_SNOOZE_HOUR -> {
                        Notifications.cancel(context, notificationId(id))
                        val minutes = if (intent.action == ACTION_SNOOZE_HOUR) 60L else SNOOZE_MINUTES
                        c.store.reminders.get(id)?.let { r ->
                            val snoozed = c.store.reminders.update(r.copy(triggerAt = Instant.now().plusSeconds(minutes * 60), active = true))
                            c.reminderScheduler.schedule(snoozed)
                        }
                    }
                    ACTION_TOMORROW -> {
                        Notifications.cancel(context, notificationId(id))
                        c.nags.clear(id)
                        c.store.reminders.get(id)?.let { r ->
                            val zone = runCatching { ZoneId.of(r.timeZone) }.getOrDefault(ZoneId.systemDefault())
                            var next = r.triggerAt.atZone(zone).plusDays(1)
                            while (!next.toInstant().isAfter(Instant.now())) next = next.plusDays(1)
                            c.reminderScheduler.schedule(c.store.reminders.update(r.copy(triggerAt = next.toInstant(), active = true)))
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

    companion object {
        private const val TAG = "Reminder"
        const val ACTION_FIRE = "ai.loli.action.REMINDER_FIRE"
        const val ACTION_DONE = "ai.loli.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "ai.loli.action.REMINDER_SNOOZE"
        const val ACTION_SNOOZE_HOUR = "ai.loli.action.REMINDER_SNOOZE_HOUR"
        const val ACTION_TOMORROW = "ai.loli.action.REMINDER_TOMORROW"
        const val EXTRA_ID = "reminder_id"
        private const val SNOOZE_MINUTES = 10L
        /** Опоздание меньше этого порога не показываем: будильники системы и так плавают на минуту. */
        private val LATE_NOTE_AFTER = Duration.ofMinutes(2)
        private const val EARLY_TOLERANCE_SECONDS = 5L
        private val ROUTINE_LATE_LIMIT: Duration = Duration.ofHours(1)
        /** Приёмник живёт около минуты: команды и озвучка должны уложиться. */
        private const val ROUTINE_TIMEOUT_MS = 25_000L
        private const val SPEAK_TIMEOUT_MS = 30_000L
        fun notificationId(id: String) = 5000 + (id.hashCode() and 0x0fffffff) % 100000

        /** Будильник и страховка ([ReminderWatchdog]) могут прийти одновременно: срабатывание одно за другим, не вместе. */
        private val fireLock = Mutex()

        /**
         * Срабатывание: уведомление, отметка «сработало» и перенос повторяющегося. Общее для будильника и страховки:
         * что бы ни сработало первым, второй раз уведомление не покажется (напоминание уже неактивно или перенесено вперёд).
         */
        suspend fun fire(context: Context, c: AppContainer, id: String) {
            val routine = fireLock.withLock {
                val reminder = c.store.reminders.get(id) ?: return
                val now = Instant.now()
                // Уже сработавшее соседом: разовое неактивно, повторяющееся перенесено на следующий раз (часы и дни вперёд).
                if (!reminder.active || reminder.triggerAt.isAfter(now.plusSeconds(EARLY_TOLERANCE_SECONDS))) return
                val late = reminder.lateBy(now)
                // Сценарий по расписанию: не уведомление, а выполнение команд (вне блокировки — он может идти долго).
                if (reminder.text.startsWith(Routine.SCHEDULE_PREFIX)) {
                    c.store.reminders.markFired(id, now)?.let { if (it.active) c.reminderScheduler.schedule(it) }
                    // Телефон был выключен и проснулся через час — утреннее радио в полдень не нужно.
                    if (late > ROUTINE_LATE_LIMIT) return
                    return@withLock reminder.text
                }
                val zone = runCatching { ZoneId.of(reminder.timeZone) }.getOrDefault(ZoneId.systemDefault())
                var text = if (late > LATE_NOTE_AFTER) {
                    reminder.text + "\nБыло в " + RuFormat.time(reminder.triggerAt.atZone(zone).toLocalTime())
                } else reminder.text
                val repeatsBefore = c.nags.repeats(id)
                if (repeatsBefore != null && repeatsBefore > 0) text += "\nНапоминаю снова ($repeatsBefore из ${NagStore.MAX_REPEATS})"
                show(context, id, text, recurring = reminder.recurrence != null)
                val fired = c.store.reminders.markFired(id, now)
                fired?.let { if (it.active) c.reminderScheduler.schedule(it) }
                // Настойчивое: пока не нажали «Готово», ещё раз через 10 минут (не больше MAX_REPEATS раз).
                if (repeatsBefore != null) {
                    if (repeatsBefore < NagStore.MAX_REPEATS) {
                        c.nags.bump(id)
                        val again = c.store.reminders.update((fired ?: reminder).copy(active = true, triggerAt = now.plusSeconds(NagStore.INTERVAL_MINUTES * 60)))
                        c.reminderScheduler.schedule(again)
                    } else c.nags.clear(id)
                }
                null
            } ?: return
            runRoutine(context, c, id, routine)
        }

        /** Выполняет сценарий: результат — уведомлением (личное скрыто на экране блокировки) и вслух, если ответы голосом включены. */
        private suspend fun runRoutine(context: Context, c: AppContainer, id: String, text: String) {
            c.awaitReady()
            val reply = withTimeoutOrNull(ROUTINE_TIMEOUT_MS) { c.engine.runScheduledRoutine(text) } ?: return
            val title = text.removePrefix(Routine.SCHEDULE_PREFIX)
            val open = PendingIntent.getActivity(
                context, notificationId(id), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val body = reply.text.substringAfter('\n', reply.text)
            val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_loli)
                .setContentTitle("Сценарий: $title")
                .setContentText(body.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_stat_loli).setContentTitle("Сценарий выполнен").build(),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            Notifications.notifySafely(context, notificationId(id), n)
            if (c.settings.current().ttsEnabled) {
                withTimeoutOrNull(SPEAK_TIMEOUT_MS) { runCatching { c.speech.speak(SpeechText.forSpeech(body)) } }
            }
        }

        private fun show(context: Context, id: String, text: String, recurring: Boolean) {
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
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.reminder_done), action(ACTION_DONE, 1))
                .addAction(0, context.getString(R.string.reminder_snooze), action(ACTION_SNOOZE, 2))
                // Разовое можно перенести на завтра; повторяющееся — на час (его следующий срок и так известен).
                .apply {
                    if (recurring) addAction(0, context.getString(R.string.reminder_hour), action(ACTION_SNOOZE_HOUR, 3))
                    else addAction(0, context.getString(R.string.reminder_tomorrow), action(ACTION_TOMORROW, 3))
                }
                .build()
            Notifications.notifySafely(context, nid, notification)
        }
    }
}
