package ai.loli.core.assistant

import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.model.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Ссылка на существующую запись: либо конкретный id (из списка кандидатов, который видел AI),
 * либо поисковый запрос, который приложение разрешит само. [types] ограничивает поиск.
 */
data class TargetRef(val id: String? = null, val query: String? = null, val types: Set<RecordType>) {
    fun withId(newId: String) = copy(id = newId, query = null)
    val isEmpty: Boolean get() = id == null && query.isNullOrBlank()
}

/** Структурированное действие. AI возвращает JSON → [ActionParser] валидирует → получаем эти типы. */
sealed interface AssistantAction {
    /** Разрушительные действия выполняются только после явного подтверждения. */
    val isDestructive: Boolean get() = false

    data class CreateNote(val kind: NoteKind, val title: String, val content: String, val tags: List<String> = emptyList()) : AssistantAction

    /**
     * Дополнить существующую запись. Если [splitQueryFromContent] — [content] содержит и описание записи,
     * и добавляемый текст (офлайн-режим: «добавь к идее холодильника сканирование»); приложение разделит их поиском.
     */
    data class AppendNote(
        val target: TargetRef,
        val content: String,
        val titleIfNew: String? = null,
        val kindIfNew: NoteKind = NoteKind.NOTE,
        val splitQueryFromContent: Boolean = false,
    ) : AssistantAction

    data class UpdateNote(val target: TargetRef, val title: String?, val content: String?) : AssistantAction

    data class DeleteNote(val target: TargetRef) : AssistantAction { override val isDestructive = true }

    data class CreateExpense(
        val amountMinor: Long,
        val currency: String,
        val category: String,
        val description: String,
        val date: LocalDate,
    ) : AssistantAction

    data class QueryExpenses(
        val preset: PeriodPreset?,
        val from: LocalDate?,
        val to: LocalDate?,
        val category: String?,
        val mode: ReportMode,
    ) : AssistantAction

    data class DeleteExpenses(
        val ids: List<String>,
        val preset: PeriodPreset?,
        val from: LocalDate?,
        val to: LocalDate?,
        val category: String?,
    ) : AssistantAction { override val isDestructive = true }

    data class CreateTask(val title: String, val details: String, val dueDate: LocalDate?, val dueTime: LocalTime?) : AssistantAction

    data class CompleteTask(val target: TargetRef, val done: Boolean = true) : AssistantAction

    data class QueryTasks(val filter: TaskFilter) : AssistantAction

    data class DeleteTask(val target: TargetRef) : AssistantAction { override val isDestructive = true }

    data class CreateReminder(val text: String, val triggerAt: Instant, val recurrence: Recurrence?) : AssistantAction

    data class CancelReminder(val target: TargetRef) : AssistantAction { override val isDestructive = true }

    data object QueryReminders : AssistantAction

    data class Remember(val content: String, val category: String) : AssistantAction

    data class ForgetMemory(val target: TargetRef) : AssistantAction { override val isDestructive = true }

    data class QueryMemories(val query: String?) : AssistantAction

    data class Search(val query: String, val keywords: List<String>, val types: Set<RecordType>?) : AssistantAction

    data class Clarify(val question: String) : AssistantAction
}

enum class TaskFilter(val wire: String) {
    TODAY("today"), TOMORROW("tomorrow"), WEEK("week"), OVERDUE("overdue"), ACTIVE("active"), COMPLETED("completed"), ALL("all");

    companion object {
        fun fromWire(v: String?): TaskFilter = entries.firstOrNull { it.wire == v?.lowercase() } ?: ACTIVE
    }
}

/** Результат разбора ответа AI или офлайн-парсера. */
data class AssistantPlan(
    val reply: String,
    val actions: List<AssistantAction>,
    val expectFollowUp: Boolean = false,
    val topic: String? = null,
    /** Действия, отброшенные валидатором, с причинами (для честного ответа пользователю). */
    val rejected: List<String> = emptyList(),
)
