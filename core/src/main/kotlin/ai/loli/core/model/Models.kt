package ai.loli.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Тип записи памяти ассистента. Используется в поиске, контексте AI и UI. */
enum class RecordType(val wire: String, val titleRu: String) {
    NOTE("note", "Заметка"),
    IDEA("idea", "Идея"),
    EXPENSE("expense", "Расход"),
    TASK("task", "Задача"),
    REMINDER("reminder", "Напоминание"),
    MEMORY("memory", "Память");

    companion object {
        fun fromWire(value: String?): RecordType? = entries.firstOrNull { it.wire == value?.lowercase() }
    }
}

enum class NoteKind(val wire: String) {
    NOTE("note"), IDEA("idea");

    val recordType: RecordType get() = if (this == IDEA) RecordType.IDEA else RecordType.NOTE

    companion object {
        fun fromWire(value: String?): NoteKind = if (value?.lowercase() == "idea") IDEA else NOTE
    }
}

data class Note(
    val id: String,
    val kind: NoteKind,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Расход. [amountMinor] — сумма в минимальных единицах (копейках). */
data class Expense(
    val id: String,
    val amountMinor: Long,
    val currency: String,
    val category: String,
    val description: String,
    val occurredOn: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TaskItem(
    val id: String,
    val title: String,
    val details: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val done: Boolean = false,
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun isOverdue(today: LocalDate, now: LocalTime): Boolean {
        if (done || dueDate == null) return false
        if (dueDate.isBefore(today)) return true
        return dueDate == today && dueTime != null && dueTime.isBefore(now)
    }
}

data class Reminder(
    val id: String,
    val text: String,
    val triggerAt: Instant,
    val recurrence: Recurrence? = null,
    val timeZone: String,
    val active: Boolean = true,
    val lastFiredAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MemoryItem(
    val id: String,
    val content: String,
    val category: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class MessageRole(val wire: String) {
    USER("user"), ASSISTANT("assistant");

    companion object {
        fun fromWire(value: String?): MessageRole = if (value == "assistant") ASSISTANT else USER
    }
}

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
)
