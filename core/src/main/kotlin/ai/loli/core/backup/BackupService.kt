package ai.loli.core.backup

import ai.loli.core.data.LoliJson
import ai.loli.core.data.LocalStore
import ai.loli.core.health.HabitEntry
import ai.loli.core.model.SecretNote
import ai.loli.core.sync.SyncRow
import ai.loli.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/** Данные, которые живут вне базы (места, имя, город): их собирает и возвращает приложение. Ключей и сессии здесь быть не должно. */
interface BackupExtras {
    suspend fun export(): Map<String, String>
    suspend fun restore(values: Map<String, String>)
}

data class RestoreReport(val restored: Int, val skippedNewerLocal: Int, val secretNotes: Int, val habits: Int, val extras: Int) {
    val total: Int get() = restored + secretNotes + habits
}

/**
 * Резервная копия всего, что хранится на телефоне. Файл шифруется паролем ([BackupCodec]).
 * Восстановление — слиянием: запись из копии берётся, только если она новее той, что уже есть на телефоне.
 */
class BackupService(
    private val store: LocalStore,
    private val extras: BackupExtras? = null,
    private val appVersion: String = "",
    private val now: () -> Instant = Instant::now,
) {
    /** [includeChat] — переписка с Лоли входит в копию, только если пользователь сам включил её синхронизацию. */
    suspend fun export(password: CharArray, includeChat: Boolean): ByteArray {
        val tables = buildJsonObject {
            for (t in store.syncTables) {
                if (t.remoteTable == CHAT && !includeChat) continue
                put(t.remoteTable, JsonArray(t.allRows().map { it.toJson() }))
            }
        }
        val secrets = JsonArray(store.secrets.all().map { n ->
            buildJsonObject {
                put("id", n.id); put("title", n.title); put("content", n.content)
                put("createdAt", n.createdAt.toEpochMilli()); put("updatedAt", n.updatedAt.toEpochMilli())
            }
        })
        val habits = JsonArray(store.habits.since(Instant.EPOCH).map { h ->
            buildJsonObject { put("id", h.id); put("kind", h.kind); put("name", h.name); put("amount", h.amount); put("at", h.at.toEpochMilli()) }
        })
        val extra = buildJsonObject { extras?.export()?.forEach { (k, v) -> put(k, v) } }
        val doc = buildJsonObject {
            put("format", BackupCodec.VERSION)
            put("app", appVersion)
            put("createdAt", now().toString())
            put("tables", tables)
            put("secretNotes", secrets)
            put("habits", habits)
            put("extras", extra)
        }
        return BackupCodec.encode(LoliJson.encodeToString(JsonElement.serializer(), doc), password)
    }

    suspend fun restore(data: ByteArray, password: CharArray): RestoreReport {
        val doc = try {
            LoliJson.parseToJsonElement(BackupCodec.decode(data, password)).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupCodec.BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupCodec.NotABackup()
        }
        if ((doc["format"]?.jsonPrimitive?.longOrNull ?: 0L) > BackupCodec.VERSION) throw BackupCodec.TooNew()
        var restored = 0
        var skipped = 0
        val tables = doc["tables"] as? JsonObject
        for (t in store.syncTables) {
            val rows = (tables?.get(t.remoteTable) as? JsonArray) ?: continue
            for (el in rows) {
                try {
                    val row = (el as? JsonObject)?.toSyncRow() ?: continue
                    val local = t.row(row.id)
                    if (local != null && local.updatedAt >= row.updatedAt) { skipped++; continue }
                    // dirty = true: после входа в аккаунт восстановленное уйдёт на сервер обычной синхронизацией.
                    t.write(row.payload, dirty = true, syncedUpdatedAt = local?.syncedUpdatedAt, expectedLocalUpdatedAt = local?.updatedAt, guard = true)
                    restored++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Строка ${t.remoteTable} из копии пропущена (${e::class.simpleName})")
                    skipped++
                }
            }
        }
        var secrets = 0
        (doc["secretNotes"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = o.str("id") ?: return@forEach
            val note = SecretNote(id, o.str("title").orEmpty(), o.str("content").orEmpty(),
                Instant.ofEpochMilli(o.long("createdAt") ?: 0L), Instant.ofEpochMilli(o.long("updatedAt") ?: 0L))
            if (store.secrets.restore(note)) secrets++ else skipped++
        }
        var habits = 0
        (doc["habits"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = o.str("id") ?: return@forEach
            val kind = o.str("kind") ?: return@forEach
            store.habits.restore(HabitEntry(id, kind, o.str("name").orEmpty(), o["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0,
                Instant.ofEpochMilli(o.long("at") ?: return@forEach)))
            habits++
        }
        val extraMap = (doc["extras"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        if (extraMap.isNotEmpty()) extras?.restore(extraMap)
        return RestoreReport(restored, skipped, secrets, habits, extraMap.size)
    }

    private fun SyncRow.toJson() = buildJsonObject {
        put("id", id); put("updatedAt", updatedAt); put("deleted", deleted)
        put("payload", payload)
    }

    private fun JsonObject.toSyncRow(): SyncRow? {
        val id = str("id") ?: return null
        val payload = this["payload"] as? JsonObject ?: return null
        return SyncRow(id, long("updatedAt") ?: return null, this["deleted"]?.jsonPrimitive?.booleanOrNull ?: false, dirty = true, syncedUpdatedAt = null, payload = payload)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    companion object {
        private const val TAG = "Backup"
        const val CHAT = "conversation_messages"
    }
}
