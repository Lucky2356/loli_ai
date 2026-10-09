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
class ReminderTicker(private val c: DesktopContainer, private val notify: (title: String, text: String) -> Unit) {
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
            if (r.text.startsWith(Routine.SCHEDULE_PREFIX)) {
                if (late > Duration.ofHours(1)) continue
                val reply = runCatching { c.engine.runScheduledRoutine(r.text) }.onFailure { Logger.w("Desktop", "Сценарий не выполнился", it) }.getOrNull() ?: continue
                notify(r.text, reply.text.substringAfter('\n', reply.text))
                continue
            }
            val tail = if (late > Duration.ofMinutes(2)) "\nБыло в " + RuFormat.time(r.triggerAt.atZone(c.time.zone()).toLocalTime()) else ""
            notify("Напоминание", r.text + tail)
        }
        return c.store.reminders.active().filter { it.triggerAt.isAfter(now) }.minOfOrNull { it.triggerAt }
    }

    private companion object {
        const val MAX_SLEEP_MS = 30_000L
    }
}
