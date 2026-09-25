package ai.loli.core.assistant

import ai.loli.core.domain.MemoryRepository
import ai.loli.core.domain.NoteRepository
import ai.loli.core.domain.ReminderRepository
import ai.loli.core.domain.TaskRepository
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.search.SearchService

sealed interface Resolution {
    data class Found(val ref: RecordRef) : Resolution
    data class Ambiguous(val options: List<RecordRef>) : Resolution
    data object NotFound : Resolution
}

/**
 * Находит запись, о которой говорит пользователь: по id, по смыслу запроса или по контексту разговора.
 * При неоднозначности НЕ выбирает случайно — возвращает варианты для уточняющего вопроса.
 */
class TargetResolver(
    private val search: SearchService,
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val memories: MemoryRepository,
) {
    suspend fun resolve(target: TargetRef, focus: RecordRef?, recent: List<RecordRef>): Resolution {
        target.id?.let { id -> lookup(id, target.types)?.let { return Resolution.Found(it) } }
        val query = target.query?.trim().orEmpty()
        if (query.isEmpty()) {
            val fromContext = listOfNotNull(focus).plus(recent).firstOrNull { it.type in target.types }
            return fromContext?.let { ref -> lookup(ref.id, target.types)?.let { Resolution.Found(it) } } ?: Resolution.NotFound
        }
        val hits = search.search(query, target.types, limit = 5, minScore = MIN_SCORE)
        if (hits.isEmpty()) return Resolution.NotFound
        val top = hits.first()
        val second = hits.getOrNull(1)
        val clearWinner = second == null || top.score - second.score >= MARGIN || (top.score >= 0.75 && second.score < 0.5)
        if (clearWinner) return Resolution.Found(top.doc.toRef())
        val close = hits.filter { top.score - it.score < MARGIN }.take(4)
        // Если среди близких вариантов есть запись из текущего разговора — речь почти наверняка о ней.
        close.firstOrNull { it.doc.id == focus?.id }?.let { return Resolution.Found(it.doc.toRef()) }
        return Resolution.Ambiguous(close.map { it.doc.toRef() })
    }

    suspend fun lookup(id: String, types: Set<RecordType>): RecordRef? {
        if (RecordType.NOTE in types || RecordType.IDEA in types) {
            notes.get(id)?.let { n ->
                val t = if (n.kind == NoteKind.IDEA) RecordType.IDEA else RecordType.NOTE
                if (t in types) return RecordRef(t, n.id, n.title)
            }
        }
        if (RecordType.TASK in types) tasks.get(id)?.let { return RecordRef(RecordType.TASK, it.id, it.title) }
        if (RecordType.REMINDER in types) reminders.get(id)?.let { return RecordRef(RecordType.REMINDER, it.id, it.text) }
        if (RecordType.MEMORY in types) memories.get(id)?.let { return RecordRef(RecordType.MEMORY, it.id, it.content) }
        return null
    }

    private fun ai.loli.core.search.SearchDoc.toRef() = RecordRef(type, id, title)

    companion object {
        const val MIN_SCORE = 0.3
        const val MARGIN = 0.15
    }
}
