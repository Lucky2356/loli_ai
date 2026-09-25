package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.domain.ReminderRepository
import ai.loli.core.model.Recurrence
import ai.loli.core.model.Reminder
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
import java.time.ZoneId
import ai.loli.core.db.Reminder as ReminderRow

class SqlReminderRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher), ReminderRepository {

    override val remoteTable = "reminders"
    private val q get() = db.reminderQueries

    override fun observe(): Flow<List<Reminder>> = q.selectAll().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    override suspend fun all(): List<Reminder> = io { q.selectAll().executeAsList().map { it.toDomain() } }
    override suspend fun active(): List<Reminder> = io { q.selectActive().executeAsList().map { it.toDomain() } }
    override suspend fun get(id: String): Reminder? = io { q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDomain() }

    override suspend fun create(text: String, triggerAt: Instant, recurrence: Recurrence?, timeZone: String): Reminder {
        val now = nowMs()
        // Ежемесячное правило помнит исходное число, иначе после февраля оно «съедет» с 31-го на 28-е.
        val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())
        @Suppress("NAME_SHADOWING")
        val recurrence = if (recurrence?.frequency == Recurrence.Frequency.MONTHLY && recurrence.dayOfMonth == null) {
            recurrence.copy(dayOfMonth = triggerAt.atZone(zone).dayOfMonth, time = recurrence.time ?: triggerAt.atZone(zone).toLocalTime())
        } else recurrence
        val row = ReminderRow(Ids.newId(), text.trim(), triggerAt.toEpochMilli(), recurrence?.encode(), timeZone, 1, null, now, now, 0, 1, null)
        io { q.upsert(row) }
        changed()
        return row.toDomain()
    }

    override suspend fun update(reminder: Reminder): Reminder {
        val row = io {
            val existing = q.selectById(reminder.id).executeAsOneOrNull() ?: error("Reminder not found")
            existing.copy(
                text = reminder.text.trim(), trigger_at = reminder.triggerAt.toEpochMilli(), recurrence = reminder.recurrence?.encode(),
                time_zone = reminder.timeZone, active = reminder.active.toLong(), last_fired_at = reminder.lastFiredAt?.toEpochMilli(),
                updated_at = nowMs(), dirty = 1,
            ).also { q.upsert(it) }
        }
        changed()
        return row.toDomain()
    }

    override suspend fun markFired(id: String, firedAt: Instant): Reminder? {
        val current = get(id) ?: return null
        val rule = current.recurrence
        val updated = if (rule != null && current.active) {
            val zone = runCatching { ZoneId.of(current.timeZone) }.getOrDefault(ZoneId.systemDefault())
            val base = maxOf(firedAt, current.triggerAt)
            current.copy(triggerAt = rule.nextAfter(base, zone, anchor = current.triggerAt), lastFiredAt = firedAt)
        } else {
            current.copy(active = false, lastFiredAt = firedAt)
        }
        return update(updated)
    }

    override suspend fun cancel(id: String): Reminder? {
        val current = get(id) ?: return null
        return update(current.copy(active = false))
    }

    override suspend fun delete(id: String): Boolean {
        val ok = io {
            val existing = q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L } ?: return@io false
            q.upsert(existing.copy(deleted = 1, active = 0, updated_at = nowMs(), dirty = 1)); true
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
        val row = ReminderRow(
            id = payload.str("id") ?: return,
            text = payload.str("text").orEmpty(),
            trigger_at = (payload.instant("trigger_at") ?: created).toEpochMilli(),
            recurrence = payload.str("recurrence")?.takeIf { Recurrence.decode(it) != null },
            time_zone = payload.str("time_zone") ?: ZoneId.systemDefault().id,
            active = (payload.bool("active") ?: true).toLong(),
            last_fired_at = payload.instant("last_fired_at")?.toEpochMilli(),
            created_at = created.toEpochMilli(),
            updated_at = (payload.instant("updated_at") ?: created).toEpochMilli(),
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

    private fun ReminderRow.toDomain() = Reminder(
        id = id, text = text, triggerAt = Instant.ofEpochMilli(trigger_at), recurrence = Recurrence.decode(recurrence),
        timeZone = time_zone, active = active == 1L, lastFiredAt = last_fired_at?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(created_at), updatedAt = Instant.ofEpochMilli(updated_at),
    )

    private fun ReminderRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "text" to text, "trigger_at" to Instant.ofEpochMilli(trigger_at), "recurrence" to recurrence,
            "time_zone" to time_zone, "active" to (active == 1L), "last_fired_at" to last_fired_at?.let(Instant::ofEpochMilli),
            "created_at" to Instant.ofEpochMilli(created_at), "updated_at" to Instant.ofEpochMilli(updated_at),
            "deleted" to (deleted == 1L),
        ),
    )
}
