package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.ShoppingItem
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
import ai.loli.core.db.Shopping_item as ItemRow

/** Списки покупок и чек-листы. */
class SqlShoppingRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher) {

    override val remoteTable = "shopping_items"
    /** Таблица появилась в 1.7.0: если на сервере её ещё нет, остальная синхронизация не ломается. */
    override val optionalRemote = true
    private val q get() = db.shoppingItemQueries

    fun observe(): Flow<List<ShoppingItem>> = q.selectActive().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    suspend fun all(): List<ShoppingItem> = io { q.selectActive().executeAsList().map { it.toDomain() } }

    /** Добавляет пункты; уже имеющиеся невычеркнутые не дублируются, вычеркнутые — возвращаются в список. */
    suspend fun add(listName: String, texts: List<String>): List<ShoppingItem> {
        val now = nowMs()
        val result = io {
            val existing = q.selectActive().executeAsList().filter { it.list_name.equals(listName, true) }
            texts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.map { text ->
                val same = existing.firstOrNull { it.text.equals(text, true) }
                val row = same?.copy(done = 0, updated_at = now, dirty = 1)
                    ?: ItemRow(Ids.newId(), listName, text.replaceFirstChar { it.uppercase() }, 0, now, now, 0, 1, null)
                q.upsert(row)
                row.toDomain()
            }
        }
        if (result.isNotEmpty()) changed()
        return result
    }

    suspend fun setDone(id: String, done: Boolean): ShoppingItem? {
        val row = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io null
            existing.copy(done = if (done) 1 else 0, updated_at = nowMs(), dirty = 1).also { q.upsert(it) }
        } ?: return null
        changed()
        return row.toDomain()
    }

    suspend fun delete(id: String): Boolean {
        val ok = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io false
            q.upsert(existing.copy(deleted = 1, updated_at = nowMs(), dirty = 1)); true
        }
        if (ok) changed()
        return ok
    }

    /** Удаляет весь список или только купленное. Возвращает число удалённых пунктов. */
    suspend fun clear(listName: String, onlyDone: Boolean): Int {
        val n = io {
            val now = nowMs()
            val rows = q.selectActive().executeAsList().filter { it.list_name.equals(listName, true) && (!onlyDone || it.done == 1L) }
            db.transaction { rows.forEach { q.upsert(it.copy(deleted = 1, updated_at = now, dirty = 1)) } }
            rows.size
        }
        if (n > 0) changed()
        return n
    }

    override suspend fun dirtyRows(): List<SyncRow> = io { q.selectDirty().executeAsList().map { it.toSyncRow() } }
    override suspend fun row(id: String): SyncRow? = io { q.selectById(id).executeAsOneOrNull()?.toSyncRow() }
    override suspend fun markSynced(id: String, updatedAt: Long) { io { q.markSynced(updatedAt, id) } }
    override suspend fun clear() { io { q.deleteAll() } }

    override suspend fun write(payload: JsonObject, dirty: Boolean, syncedUpdatedAt: Long?, expectedLocalUpdatedAt: Long?, guard: Boolean) {
        val created = payload.instant("created_at") ?: Instant.now()
        val row = ItemRow(
            id = payload.str("id") ?: return,
            list_name = payload.str("list_name") ?: ShoppingItem.DEFAULT_LIST,
            text = payload.str("text").orEmpty(),
            done = if (payload.bool("done") == true) 1 else 0,
            created_at = created.toEpochMilli(),
            updated_at = (payload.instant("updated_at") ?: created).toEpochMilli(),
            deleted = if (payload.bool("deleted") == true) 1 else 0,
            dirty = if (dirty) 1 else 0,
            synced_updated_at = syncedUpdatedAt,
        )
        io {
            db.transaction {
                if (guard && q.selectById(row.id).executeAsOneOrNull()?.updated_at != expectedLocalUpdatedAt) return@transaction
                q.upsert(row)
            }
        }
    }

    private fun ItemRow.toDomain() = ShoppingItem(id, list_name, text, done == 1L, Instant.ofEpochMilli(created_at), Instant.ofEpochMilli(updated_at))

    private fun ItemRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "list_name" to list_name, "text" to text, "done" to (done == 1L),
            "created_at" to Instant.ofEpochMilli(created_at), "updated_at" to Instant.ofEpochMilli(updated_at),
            "deleted" to (deleted == 1L),
        ),
    )
}
