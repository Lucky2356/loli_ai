package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.domain.TaskRepository
import ai.loli.core.model.TaskItem
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
import java.time.LocalDate
import java.time.LocalTime
import ai.loli.core.db.Task as TaskRow

class SqlTaskRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher), TaskRepository {

    override val remoteTable = "tasks"
    private val q get() = db.taskQueries

    override fun observe(): Flow<List<TaskItem>> = q.selectActive().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    override suspend fun all(): List<TaskItem> = io { q.selectActive().executeAsList().map { it.toDomain() } }
    override suspend fun get(id: String): TaskItem? = io { q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDomain() }

    override suspend fun create(title: String, details: String, dueDate: LocalDate?, dueTime: LocalTime?): TaskItem {
        val now = nowMs()
        val row = TaskRow(
            Ids.newId(), title.trim(), details.trim(), dueDate?.toString(), dueTime?.let(::formatTime),
            0, null, now, now, 0, 1, null,
        )
        io { q.upsert(row) }
        changed()
        return row.toDomain()
    }

    override suspend fun update(task: TaskItem): TaskItem {
        val row = io {
            val existing = q.selectById(task.id).executeAsOneOrNull() ?: error("Task not found")
            existing.copy(
                title = task.title.trim(), details = task.details.trim(), due_date = task.dueDate?.toString(),
                due_time = task.dueTime?.let(::formatTime), done = task.done.toLong(),
                completed_at = task.completedAt?.toEpochMilli(), updated_at = nowMs(), dirty = 1,
            ).also { q.upsert(it) }
        }
        changed()
        return row.toDomain()
    }

    override suspend fun setDone(id: String, done: Boolean): TaskItem? {
        val row = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io null
            val now = nowMs()
            existing.copy(done = done.toLong(), completed_at = if (done) now else null, updated_at = now, dirty = 1).also { q.upsert(it) }
        } ?: return null
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
        val row = TaskRow(
            id = payload.str("id") ?: return,
            title = payload.str("title").orEmpty(),
            details = payload.str("details").orEmpty(),
            due_date = payload.str("due_date")?.take(10),
            due_time = payload.str("due_time")?.let { runCatching { formatTime(LocalTime.parse(it)) }.getOrNull() },
            done = (payload.bool("done") ?: false).toLong(),
            completed_at = payload.instant("completed_at")?.toEpochMilli(),
            created_at = created.toEpochMilli(),
            updated_at = (payload.instant("updated_at") ?: created).toEpochMilli(),
            deleted = (payload.bool("deleted") ?: false).toLong(),
            dirty = dirty.toLong(),
            synced_updated_at = syncedUpdatedAt,
        )
        io { q.upsert(row) }
    }

    private fun TaskRow.toDomain() = TaskItem(
        id = id, title = title, details = details,
        dueDate = due_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        dueTime = due_time?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        done = done == 1L, completedAt = completed_at?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(created_at), updatedAt = Instant.ofEpochMilli(updated_at),
    )

    private fun TaskRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "title" to title, "details" to details, "due_date" to due_date, "due_time" to due_time,
            "done" to (done == 1L), "completed_at" to completed_at?.let(Instant::ofEpochMilli),
            "created_at" to Instant.ofEpochMilli(created_at), "updated_at" to Instant.ofEpochMilli(updated_at),
            "deleted" to (deleted == 1L),
        ),
    )

    private fun formatTime(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
