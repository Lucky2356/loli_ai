package ai.loli.desktop

import ai.loli.core.model.Routine
import ai.loli.core.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Напоминания на компьютере: раз в 20 секунд смотрим, что пора, и показываем уведомлением в трее.
 * Работает, пока Лоли запущена (окно можно закрыть — она остаётся в трее). Пропущенное за время
 * выключения покажется при запуске с пометкой «Было в…»; сценарии по расписанию старше часа пропускаются.
 */
class ReminderTicker(private val c: DesktopContainer, private val notify: (title: String, text: String) -> Unit) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = c.scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Logger.w("Desktop", "Проверка напоминаний не удалась", it) }
                delay(20_000)
            }
        }
    }

    suspend fun tick() {
        val now = Instant.now()
        val due = c.store.reminders.active().filter { !it.triggerAt.isAfter(now.plusSeconds(5)) }
        for (r in due) {
            c.store.reminders.markFired(r.id, now)
            val late = Duration.between(r.triggerAt, now)
            if (r.text.startsWith(Routine.SCHEDULE_PREFIX)) {
                if (late > Duration.ofHours(1)) continue
                val reply = c.engine.runScheduledRoutine(r.text) ?: continue
                notify(r.text, reply.text.substringAfter('\n', reply.text))
                continue
            }
            val tail = if (late > Duration.ofMinutes(2)) "\nБыло в " + ai.loli.core.assistant.RuFormat.time(r.triggerAt.atZone(c.time.zone()).toLocalTime()) else ""
            notify("Напоминание", r.text + tail)
        }
    }
}
