package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.Routine
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
import ai.loli.core.db.Routine as RoutineRow

/** Сценарии: фраза-триггер → последовательность команд. */
class SqlRoutineRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher) {

    override val remoteTable = "routines"
    override val optionalRemote = true
    private val q get() = db.routineQueries

    fun observe(): Flow<List<Routine>> = q.selectActive().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    suspend fun all(): List<Routine> = io { q.selectActive().executeAsList().map { it.toDomain() } }

    /** Создаёт сценарий или заменяет команды у сценария с той же фразой. */
    suspend fun save(trigger: String, commands: List<String>): Routine {
        val now = nowMs()
        val row = io {
            val same = q.selectActive().executeAsList().firstOrNull { Routine.normalize(it.trigger_phrase) == Routine.normalize(trigger) }
            val r = same?.copy(commands = encodeTags(commands), updated_at = now, dirty = 1)
                ?: RoutineRow(Ids.newId(), trigger.trim(), encodeTags(commands), now, now, 0, 1, null)
            q.upsert(r); r
        }
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

    override suspend fun dirtyRows(): List<SyncRow> = io { q.selectDirty().executeAsList().map { it.toSyncRow() } }
    override suspend fun row(id: String): SyncRow? = io { q.selectById(id).executeAsOneOrNull()?.toSyncRow() }
    override suspend fun markSynced(id: String, updatedAt: Long) { io { q.markSynced(updatedAt, id) } }
    override suspend fun clear() { io { q.deleteAll() } }

    override suspend fun write(payload: JsonObject, dirty: Boolean, syncedUpdatedAt: Long?, expectedLocalUpdatedAt: Long?, guard: Boolean) {
        val created = payload.instant("created_at") ?: Instant.now()
        val row = RoutineRow(
            id = payload.str("id") ?: return,
            trigger_phrase = payload.str("trigger_phrase").orEmpty(),
            commands = encodeTags(payload.strList("commands")),
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

    private fun RoutineRow.toDomain() = Routine(id, trigger_phrase, decodeTags(commands), Instant.ofEpochMilli(created_at), Instant.ofEpochMilli(updated_at))

    private fun RoutineRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "trigger_phrase" to trigger_phrase, "commands" to decodeTags(commands),
            "created_at" to Instant.ofEpochMilli(created_at), "updated_at" to Instant.ofEpochMilli(updated_at),
            "deleted" to (deleted == 1L),
        ),
    )
}
