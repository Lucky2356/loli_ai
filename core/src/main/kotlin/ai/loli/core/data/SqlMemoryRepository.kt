package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.domain.MemoryRepository
import ai.loli.core.model.MemoryItem
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
import ai.loli.core.db.Memory as MemoryRow

class SqlMemoryRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher), MemoryRepository {

    override val remoteTable = "memories"
    private val q get() = db.memoryQueries

    override fun observe(): Flow<List<MemoryItem>> = q.selectActive().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    override suspend fun all(): List<MemoryItem> = io { q.selectActive().executeAsList().map { it.toDomain() } }
    override suspend fun get(id: String): MemoryItem? = io { q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDomain() }

    override suspend fun create(content: String, category: String): MemoryItem {
        val now = nowMs()
        val row = MemoryRow(Ids.newId(), content.trim(), category.trim().ifEmpty { "other" }, now, now, 0, 1, null)
        io { q.upsert(row) }
        changed()
        return row.toDomain()
    }

    override suspend fun update(item: MemoryItem): MemoryItem {
        val row = io {
            val existing = q.selectById(item.id).executeAsOneOrNull() ?: error("Memory not found")
            existing.copy(content = item.content.trim(), category = item.category, updated_at = nowMs(), dirty = 1).also { q.upsert(it) }
        }
        changed()
        return row.toDomain()
    }

    override suspend fun delete(id: String): Boolean {
        val ok = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io false
            q.upsert(existing.copy(deleted = 1, updated_at = nowMs(), dirty = 1)); true
        }
        if (ok) changed()
        return ok
    }

    override suspend fun dirtyRows(): List<SyncRow> = io { q.selectDirty().executeAsList().map { it.toSyncRow() } }
    override suspend fun row(id: String): SyncRow? = io { q.selectById(id).executeAsOneOrNull()?.toSyncRow() }
    override suspend fun markSynced(id: String, updatedAt: Long) { io { q.markSynced(updatedAt, id) } }
    override suspend fun clear() { io { q.deleteAll() } }

    override suspend fun write(payload: JsonObject, dirty: Boolean, syncedUpdatedAt: Long?) {
        val created = payload.instant("created_at") ?: Instant.now()
        val row = MemoryRow(
            id = payload.str("id") ?: return,
            content = payload.str("content").orEmpty(),
            category = payload.str("category") ?: "other",
            created_at = created.toEpochMilli(),
            updated_at = (payload.instant("updated_at") ?: created).toEpochMilli(),
            deleted = (payload.bool("deleted") ?: false).toLong(),
            dirty = dirty.toLong(),
            synced_updated_at = syncedUpdatedAt,
        )
        io { q.upsert(row) }
    }

    private fun MemoryRow.toDomain() = MemoryItem(id, content, category, Instant.ofEpochMilli(created_at), Instant.ofEpochMilli(updated_at))

    private fun MemoryRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "content" to content, "category" to category, "created_at" to Instant.ofEpochMilli(created_at),
            "updated_at" to Instant.ofEpochMilli(updated_at), "deleted" to (deleted == 1L),
        ),
    )
}
