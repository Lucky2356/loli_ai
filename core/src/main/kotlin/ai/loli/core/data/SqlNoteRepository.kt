package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.domain.NoteRepository
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.sync.SyncRow
import ai.loli.core.util.Ids
import ai.loli.core.util.UpdateStamp
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import ai.loli.core.db.Note as NoteRow

class SqlNoteRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher), NoteRepository {

    override val remoteTable = "notes"
    private val q get() = db.noteQueries

    override fun observe(kind: NoteKind?): Flow<List<Note>> =
        (if (kind == null) q.selectActive() else q.selectActiveByKind(kind.wire))
            .asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override suspend fun all(kind: NoteKind?): List<Note> = io {
        (if (kind == null) q.selectActive() else q.selectActiveByKind(kind.wire)).executeAsList().map { it.toDomain() }
    }

    override suspend fun get(id: String): Note? = io { q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDomain() }

    override suspend fun create(kind: NoteKind, title: String, content: String, tags: List<String>): Note {
        val now = nowMs()
        val row = NoteRow(Ids.newId(), kind.wire, title.trim(), content.trim(), encodeTags(tags), 0, now, now, 0, 1, null)
        io { q.upsert(row) }
        changed()
        return row.toDomain()
    }

    override suspend fun update(note: Note): Note {
        val row = io {
            val existing = q.selectById(note.id).executeAsOneOrNull() ?: error("Note ${note.id} not found")
            existing.copy(
                kind = note.kind.wire, title = note.title.trim(), content = note.content.trim(), tags = encodeTags(note.tags),
                pinned = note.pinned.toLong(), updated_at = nowMs(), dirty = 1,
            ).also { q.upsert(it) }
        }
        changed()
        return row.toDomain()
    }

    override suspend fun append(id: String, text: String): Note? {
        val addition = text.trim()
        if (addition.isEmpty()) return get(id)
        val row = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io null
            existing.copy(content = appendLine(existing.content, addition), updated_at = nowMs(), dirty = 1).also { q.upsert(it) }
        } ?: return null
        changed()
        return row.toDomain()
    }

    override suspend fun delete(id: String): Boolean {
        val ok = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io false
            q.upsert(existing.copy(deleted = 1, updated_at = nowMs(), dirty = 1))
            true
        }
        if (ok) changed()
        return ok
    }

    // --- Синхронизация ---

    override suspend fun dirtyRows(): List<SyncRow> = io { q.selectDirty().executeAsList().map { it.toSyncRow() } }
    override suspend fun row(id: String): SyncRow? = io { q.selectById(id).executeAsOneOrNull()?.toSyncRow() }
    override suspend fun markSynced(id: String, updatedAt: Long) { io { q.markSynced(updatedAt, id) } }
    override suspend fun clear() { io { q.deleteAll() } }

    override suspend fun write(payload: JsonObject, dirty: Boolean, syncedUpdatedAt: Long?, expectedLocalUpdatedAt: Long?, guard: Boolean) {
        val created = payload.instant("created_at") ?: Instant.now()
        val updated = payload.instant("updated_at") ?: created
        val row = NoteRow(
            id = payload.str("id") ?: return,
            kind = NoteKind.fromWire(payload.str("kind")).wire,
            title = payload.str("title").orEmpty(),
            content = payload.str("content").orEmpty(),
            tags = encodeTags(payload.strList("tags")),
            pinned = (payload.bool("pinned") ?: false).toLong(),
            created_at = created.toEpochMilli(),
            updated_at = updated.toEpochMilli(),
            deleted = (payload.bool("deleted") ?: false).toLong(),
            dirty = dirty.toLong(),
            synced_updated_at = syncedUpdatedAt,
        )
        io {
            db.transaction {
                if (guard && q.selectById(row.id).executeAsOneOrNull()?.updated_at != expectedLocalUpdatedAt) return@transaction
                q.upsert(row)
            }
        }
    }

    /**
     * Конфликт заметок: ни одно изменение не теряем. Если одна версия — продолжение другой, берём более полную;
     * иначе объединяем строки (сначала серверные, затем недостающие локальные). Удаление побеждает только
     * если запись удалена с обеих сторон — иначе чужое редактирование «воскрешает» заметку.
     */
    override fun merge(local: JsonObject, remote: JsonObject, localUpdatedAt: Long, remoteUpdatedAt: Long): JsonObject {
        val newer = if (localUpdatedAt > remoteUpdatedAt) local else remote
        val lc = local.str("content").orEmpty()
        val rc = remote.str("content").orEmpty()
        val merged = mergeText(lc, rc)
        val tags = (remote.strList("tags") + local.strList("tags")).distinct()
        val deleted = (local.bool("deleted") ?: false) && (remote.bool("deleted") ?: false)
        return jsonOf(
            "id" to newer.str("id"), "kind" to newer.str("kind"), "title" to newer.str("title"),
            "content" to merged, "tags" to tags, "pinned" to (newer.bool("pinned") ?: false),
            "created_at" to (remote.str("created_at") ?: local.str("created_at")),
            "updated_at" to newer.str("updated_at"), "deleted" to deleted,
        )
    }

    private fun NoteRow.toDomain() = Note(
        id = id, kind = NoteKind.fromWire(kind), title = title, content = content, tags = decodeTags(tags),
        pinned = pinned == 1L, createdAt = Instant.ofEpochMilli(created_at), updatedAt = Instant.ofEpochMilli(updated_at),
    )

    private fun NoteRow.toSyncRow() = SyncRow(
        id = id, updatedAt = updated_at, deleted = deleted == 1L, dirty = dirty == 1L, syncedUpdatedAt = synced_updated_at,
        payload = jsonOf(
            "id" to id, "kind" to kind, "title" to title, "content" to content, "tags" to decodeTags(tags),
            "pinned" to (pinned == 1L), "created_at" to Instant.ofEpochMilli(created_at),
            "updated_at" to Instant.ofEpochMilli(updated_at), "deleted" to (deleted == 1L),
        ),
    )

    companion object {
        const val BULLET = "• "

        fun appendLine(content: String, addition: String): String {
            val item = if (addition.startsWith(BULLET)) addition else BULLET + addition
            return if (content.isBlank()) item else content.trimEnd() + "\n" + item
        }

        fun mergeText(local: String, remote: String): String = when {
            local == remote -> local
            local.startsWith(remote) -> local
            remote.startsWith(local) -> remote
            else -> {
                val remoteLines = remote.lines()
                val extra = local.lines().filter { it.isNotBlank() && it !in remoteLines }
                (remoteLines + extra).joinToString("\n").trim()
            }
        }
    }
}
