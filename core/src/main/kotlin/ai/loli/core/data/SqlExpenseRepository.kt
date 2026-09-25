package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.domain.ExpenseRepository
import ai.loli.core.model.Expense
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
import ai.loli.core.db.Expense as ExpenseRow

class SqlExpenseRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher), ExpenseRepository {

    override val remoteTable = "expenses"
    private val q get() = db.expenseQueries

    override fun observe(): Flow<List<Expense>> = q.selectActive().asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }
    override suspend fun all(): List<Expense> = io { q.selectActive().executeAsList().map { it.toDomain() } }
    override suspend fun between(from: LocalDate, to: LocalDate): List<Expense> =
        io { q.selectBetween(from.toString(), to.toString()).executeAsList().map { it.toDomain() } }
    override suspend fun get(id: String): Expense? = io { q.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDomain() }

    override suspend fun create(amountMinor: Long, currency: String, category: String, description: String, occurredOn: LocalDate): Expense {
        require(amountMinor > 0) { "amount must be positive" }
        val now = nowMs()
        val row = ExpenseRow(Ids.newId(), amountMinor, currency.uppercase(), category.trim(), description.trim(), occurredOn.toString(), now, now, 0, 1, null)
        io { q.upsert(row) }
        changed()
        return row.toDomain()
    }

    override suspend fun update(expense: Expense): Expense {
        val row = io {
            val existing = q.selectById(expense.id).executeAsOneOrNull() ?: error("Expense not found")
            existing.copy(
                amount_minor = expense.amountMinor, currency = expense.currency, category = expense.category,
                description = expense.description, occurred_on = expense.occurredOn.toString(), updated_at = nowMs(), dirty = 1,
            ).also { q.upsert(it) }
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
        val row = ExpenseRow(
            id = payload.str("id") ?: return,
            amount_minor = payload.long("amount_minor") ?: 0,
            currency = payload.str("currency") ?: "RUB",
            category = payload.str("category").orEmpty(),
            description = payload.str("description").orEmpty(),
            occurred_on = payload.str("occurred_on")?.take(10) ?: LocalDate.now().toString(),
            created_at = created.toEpochMilli(),
            updated_at = (payload.instant("updated_at") ?: created).toEpochMilli(),
            deleted = (payload.bool("deleted") ?: false).toLong(),
            dirty = dirty.toLong(),
            synced_updated_at = syncedUpdatedAt,
        )
        io { q.upsert(row) }
    }

    private fun ExpenseRow.toDomain() = Expense(
        id, amount_minor, currency, category, description, LocalDate.parse(occurred_on),
        Instant.ofEpochMilli(created_at), Instant.ofEpochMilli(updated_at),
    )

    private fun ExpenseRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "amount_minor" to amount_minor, "currency" to currency, "category" to category,
            "description" to description, "occurred_on" to occurred_on, "created_at" to Instant.ofEpochMilli(created_at),
            "updated_at" to Instant.ofEpochMilli(updated_at), "deleted" to (deleted == 1L),
        ),
    )
}
