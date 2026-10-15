package ai.loli.core.assistant

import ai.loli.core.domain.ReminderRepository
import ai.loli.core.domain.TaskRepository
import ai.loli.core.model.Reminder
import ai.loli.core.model.TaskItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Напоминание к сроку задачи: «созвон сегодня в 19:00» — в 19:00 придёт уведомление «Задача: Созвон».
 * Обычное напоминание с текстом-меткой: работает везде, где работают напоминания (будильники телефона,
 * окно на компьютере), без новых таблиц и синхронизации. Выполненная или удалённая задача не напоминает.
 */
object TaskReminders {
    const val PREFIX = "Задача: "

    fun isTaskReminder(text: String): Boolean = text.startsWith(PREFIX)

    fun title(text: String): String = text.removePrefix(PREFIX)

    /** Ставит напоминание к сроку, если у задачи есть и дата, и время, и они ещё впереди. */
    suspend fun schedule(task: TaskItem, reminders: ReminderRepository, scheduler: ReminderScheduler, zone: ZoneId, now: Instant): Reminder? {
        if (task.done) return null
        val date = task.dueDate ?: return null
        val time = task.dueTime ?: return null
        val at = ZonedDateTime.of(date, time, zone).toInstant()
        if (!at.isAfter(now)) return null
        cancel(task.title, reminders, scheduler)
        return reminders.create(PREFIX + task.title, at, null, zone.id).also { scheduler.schedule(it) }
    }

    /** Снимает напоминания к задаче (выполнена, удалена, перенесена). */
    suspend fun cancel(title: String, reminders: ReminderRepository, scheduler: ReminderScheduler) {
        reminders.active().filter { it.text == PREFIX + title }.forEach {
            scheduler.cancel(it.id)
            reminders.cancel(it.id)
        }
    }

    /** Напоминание о задаче, которой уже нет или которая выполнена, — показывать не нужно. */
    suspend fun obsolete(text: String, tasks: TaskRepository): Boolean {
        if (!isTaskReminder(text)) return false
        val same = tasks.all().filter { it.title == title(text) }
        return same.isEmpty() || same.all { it.done }
    }
}
