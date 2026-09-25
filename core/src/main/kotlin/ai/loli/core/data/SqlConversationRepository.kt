package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.domain.ConversationRepository
import ai.loli.core.model.ConversationMessage
import ai.loli.core.model.MessageRole
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
import ai.loli.core.db.Conversation_message as MessageRow

class SqlConversationRepository(
    db: LoliDatabase, stamp: UpdateStamp, bus: LocalChangeBus, dispatcher: CoroutineDispatcher,
) : SqlTableBase(db, stamp, bus, dispatcher), ConversationRepository {

    override val remoteTable = "conversation_messages"
    private val q get() = db.conversationQueries

    override fun observeRecent(limit: Int): Flow<List<ConversationMessage>> =
        q.selectRecent(limit.toLong()).asFlow().mapToList(dispatcher).map { it.map { r -> r.toDomain() } }

    override suspend fun recent(limit: Int): List<ConversationMessage> = io { q.selectRecent(limit.toLong()).executeAsList().map { it.toDomain() } }

    override suspend fun add(conversationId: String, role: MessageRole, content: String): ConversationMessage {
        val now = nowMs()
        val row = MessageRow(Ids.newId(), conversationId, role.wire, content, now, now, 0, 1, null)
        io { q.upsert(row) }
        changed()
        return row.toDomain()
    }

    override suspend fun dirtyRows(): List<SyncRow> = io { q.selectDirty().executeAsList().map { it.toSyncRow() } }
    override suspend fun row(id: String): SyncRow? = io { q.selectById(id).executeAsOneOrNull()?.toSyncRow() }
    override suspend fun markSynced(id: String, updatedAt: Long) { io { q.markSynced(updatedAt, id) } }
    override suspend fun clear() { io { q.deleteAll() } }

    override suspend fun write(payload: JsonObject, dirty: Boolean, syncedUpdatedAt: Long?, expectedLocalUpdatedAt: Long?, guard: Boolean) {
        val created = payload.instant("created_at") ?: Instant.now()
        val row = MessageRow(
            id = payload.str("id") ?: return,
            conversation_id = payload.str("conversation_id").orEmpty(),
            role = MessageRole.fromWire(payload.str("role")).wire,
            content = payload.str("content").orEmpty(),
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

    private fun MessageRow.toDomain() = ConversationMessage(id, conversation_id, MessageRole.fromWire(role), content, Instant.ofEpochMilli(created_at))

    private fun MessageRow.toSyncRow() = SyncRow(
        id, updated_at, deleted == 1L, dirty == 1L, synced_updated_at,
        jsonOf(
            "id" to id, "conversation_id" to conversation_id, "role" to role, "content" to content,
            "created_at" to Instant.ofEpochMilli(created_at), "updated_at" to Instant.ofEpochMilli(updated_at),
            "deleted" to (deleted == 1L),
        ),
    )
}
