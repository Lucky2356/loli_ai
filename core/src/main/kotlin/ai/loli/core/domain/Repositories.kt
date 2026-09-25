package ai.loli.core.domain

import ai.loli.core.model.ConversationMessage
import ai.loli.core.model.Expense
import ai.loli.core.model.MemoryItem
import ai.loli.core.model.MessageRole
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.model.Recurrence
import ai.loli.core.model.Reminder
import ai.loli.core.model.TaskItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Доменные интерфейсы хранилищ. Бизнес-логика (ассистент, UI) работает только с ними
 * и не знает, где лежат данные: в SQLite на Android или в другой БД на Windows.
 * Все изменения сразу пишутся локально (offline-first) и помечаются для синхронизации.
 */
interface NoteRepository {
    fun observe(kind: NoteKind? = null): Flow<List<Note>>
    suspend fun all(kind: NoteKind? = null): List<Note>
    suspend fun get(id: String): Note?
    suspend fun create(kind: NoteKind, title: String, content: String, tags: List<String> = emptyList()): Note
    suspend fun update(note: Note): Note
    /** Дописывает текст в конец записи отдельной строкой. */
    suspend fun append(id: String, text: String): Note?
    suspend fun delete(id: String): Boolean
}

interface ExpenseRepository {
    fun observe(): Flow<List<Expense>>
    suspend fun all(): List<Expense>
    suspend fun between(from: LocalDate, to: LocalDate): List<Expense>
    suspend fun get(id: String): Expense?
    suspend fun create(amountMinor: Long, currency: String, category: String, description: String, occurredOn: LocalDate): Expense
    suspend fun update(expense: Expense): Expense
    suspend fun delete(id: String): Boolean
}

interface TaskRepository {
    fun observe(): Flow<List<TaskItem>>
    suspend fun all(): List<TaskItem>
    suspend fun get(id: String): TaskItem?
    suspend fun create(title: String, details: String = "", dueDate: LocalDate? = null, dueTime: LocalTime? = null): TaskItem
    suspend fun update(task: TaskItem): TaskItem
    suspend fun setDone(id: String, done: Boolean): TaskItem?
    suspend fun delete(id: String): Boolean
}

interface ReminderRepository {
    fun observe(): Flow<List<Reminder>>
    suspend fun all(): List<Reminder>
    suspend fun active(): List<Reminder>
    suspend fun get(id: String): Reminder?
    suspend fun create(text: String, triggerAt: Instant, recurrence: Recurrence?, timeZone: String): Reminder
    suspend fun update(reminder: Reminder): Reminder
    /** Отмечает срабатывание: для повторяющихся вычисляет следующее время, разовые деактивирует. */
    suspend fun markFired(id: String, firedAt: Instant): Reminder?
    suspend fun cancel(id: String): Reminder?
    suspend fun delete(id: String): Boolean
}

interface MemoryRepository {
    fun observe(): Flow<List<MemoryItem>>
    suspend fun all(): List<MemoryItem>
    suspend fun get(id: String): MemoryItem?
    suspend fun create(content: String, category: String): MemoryItem
    suspend fun update(item: MemoryItem): MemoryItem
    suspend fun delete(id: String): Boolean
}

interface ConversationRepository {
    fun observeRecent(limit: Int = 100): Flow<List<ConversationMessage>>
    suspend fun recent(limit: Int = 100): List<ConversationMessage>
    suspend fun add(conversationId: String, role: MessageRole, content: String): ConversationMessage
}

/** Получает уведомления о локальных изменениях — например, чтобы запланировать синхронизацию. */
fun interface LocalChangeListener {
    fun onLocalChange(table: String)
}
