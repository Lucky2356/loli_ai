package ai.loli.desktop

import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Routine
import ai.loli.core.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * Напоминания на компьютере: просыпаемся ровно к ближайшему напоминанию (но не реже раза в 30 с —
 * на случай перевода часов или сна компьютера) и сразу — когда напоминания изменились.
 * Работает, пока Лоли запущена (окно можно закрыть — она остаётся в трее). Пропущенное за время
 * выключения покажется при запуске с пометкой «Было в…»; сценарии по расписанию старше часа пропускаются.
 */
class ReminderTicker(private val c: DesktopContainer, private val notify: (Fired) -> Unit) {
    /** Сработавшее напоминание: заголовок, текст, пометка об опоздании, сценарий ли это. */
    data class Fired(val title: String, val text: String, val note: String?, val routine: Boolean) {
        /** Текст для уведомления Windows одной строкой. */
        val full: String get() = text + (note?.let { "\n$it" } ?: "")
    }

    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (job?.isActive == true) return
        job = c.scope.launch {
            launch { c.store.reminders.observe().collect { wake.trySend(Unit) } }
            while (isActive) {
                val next = runCatching { tick() }.onFailure { Logger.w("Desktop", "Проверка напоминаний не удалась", it) }.getOrNull()
                val wait = next?.let { Duration.between(c.time.now(), it).toMillis() }?.coerceIn(200, MAX_SLEEP_MS) ?: MAX_SLEEP_MS
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** Показывает всё, что пора; возвращает время следующего напоминания. */
    suspend fun tick(): Instant? {
        val now = c.time.now()
        val active = c.store.reminders.active()
        for (r in active.filter { !it.triggerAt.isAfter(now.plusMillis(500)) }) {
            c.store.reminders.markFired(r.id, now)
            val late = Duration.between(r.triggerAt, now)
            Logger.i("Reminders", "Сработало напоминание ${r.id.take(8)} (опоздание ${late.seconds} с)")
            if (r.text.startsWith(Routine.SCHEDULE_PREFIX)) {
                if (late > Duration.ofHours(1)) continue
                val reply = runCatching { c.engine.runScheduledRoutine(r.text) }.onFailure { Logger.w("Desktop", "Сценарий не выполнился", it) }.getOrNull() ?: continue
                notify(Fired(r.text.removePrefix(Routine.SCHEDULE_PREFIX), reply.text.substringAfter('\n', reply.text), null, routine = true))
                continue
            }
            val note = if (late > Duration.ofMinutes(2)) "Было в " + RuFormat.time(r.triggerAt.atZone(c.time.zone()).toLocalTime()) else null
            runCatching { notify(Fired("Напоминание", r.text, note, routine = false)) }.onFailure { Logger.w("Reminders", "Не удалось показать напоминание", it) }
        }
        return c.store.reminders.active().filter { it.triggerAt.isAfter(now) }.minOfOrNull { it.triggerAt }
    }

    private companion object {
        const val MAX_SLEEP_MS = 30_000L
    }
}
