package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.SecretNote
import ai.loli.core.util.Ids
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Секретные заметки. Хранятся только в зашифрованной локальной БД: не синхронизируются,
 * не попадают в поиск, в эмбеддинги и в контекст AI. Открываются в интерфейсе только после биометрии.
 */
class SecretNoteStore(private val db: LoliDatabase, private val dispatcher: CoroutineDispatcher, private val now: () -> Instant) {
    private val q get() = db.secretNoteQueries

    fun observe(): Flow<List<SecretNote>> = q.selectAll().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    suspend fun all(): List<SecretNote> = withContext(dispatcher) { q.selectAll().executeAsList().map { it.toDomain() } }
    suspend fun count(): Long = withContext(dispatcher) { q.countAll().executeAsOne() }

    suspend fun save(title: String, content: String, id: String? = null): SecretNote = withContext(dispatcher) {
        val t = now().toEpochMilli()
        val existing = id?.let { key -> q.selectAll().executeAsList().firstOrNull { it.id == key } }
        val row = existing?.copy(title = title.trim(), content = content.trim(), updated_at = t)
            ?: ai.loli.core.db.Secret_note(Ids.newId(), title.trim(), content.trim(), t, t)
        q.insertOrReplace(row)
        row.toDomain()
    }

    suspend fun delete(id: String) = withContext(dispatcher) { q.deleteById(id); Unit }

    /** Восстановление из резервной копии с прежними временами; более свежая локальная версия не затирается. */
    suspend fun restore(note: SecretNote): Boolean = withContext(dispatcher) {
        val local = q.selectAll().executeAsList().firstOrNull { it.id == note.id }
        if (local != null && local.updated_at >= note.updatedAt.toEpochMilli()) return@withContext false
        q.insertOrReplace(ai.loli.core.db.Secret_note(note.id, note.title, note.content, note.createdAt.toEpochMilli(), note.updatedAt.toEpochMilli()))
        true
    }

    private fun ai.loli.core.db.Secret_note.toDomain() = SecretNote(id, title, content, Instant.ofEpochMilli(created_at), Instant.ofEpochMilli(updated_at))
}
