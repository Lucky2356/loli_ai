package ai.loli.core.sync

import ai.loli.core.data.LocalStore
import ai.loli.core.data.parseInstant
import ai.loli.core.remote.RemoteDataSource
import ai.loli.core.remote.RemoteException
import ai.loli.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

data class SyncReport(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val error: String? = null,
    val offline: Boolean = false,
    val finishedAt: Instant? = null,
) {
    val ok: Boolean get() = error == null
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val report: SyncReport) : SyncStatus
}

/** Синхронизация профиля (имя ассистента и настройки AI без ключа) — общая для Android и будущего Windows-клиента. */
interface ProfileSync {
    suspend fun local(): JsonObject?
    suspend fun localUpdatedAt(): Instant?
    suspend fun isDirty(): Boolean
    suspend fun applyRemote(profile: JsonObject)
    suspend fun markSynced(updatedAt: Instant)
}

/**
 * Local-first синхронизация с Supabase.
 *
 * Для каждой таблицы:
 *  1. PULL — скачиваем строки, изменённые на сервере после курсора (с перекрытием на случай гонок транзакций).
 *     - локальной записи нет или она не менялась → принимаем серверную;
 *     - локальная изменена, а сервер содержит ту же базовую версию → оставляем локальную (её отправим);
 *     - изменены обе → настоящий конфликт → [SyncableTable.merge] (для заметок — слияние текста без потерь).
 *  2. PUSH — отправляем все локальные изменения (dirty). Сервер дополнительно защищён триггером last-write-wins.
 *  3. Снимаем флаг dirty, только если запись не менялась во время отправки.
 */
class SyncEngine(
    private val local: LocalStore,
    private val remote: RemoteDataSource,
    private val profile: ProfileSync? = null,
    private val userId: () -> String?,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun sync(): SyncReport = mutex.withLock {
        _status.value = SyncStatus.Running
        var pulled = 0
        var pushed = 0
        var conflicts = 0
        val report = try {
            for (table in local.syncTables) {
                try {
                    val p = pull(table)
                    pulled += p.first
                    conflicts += p.second
                    pushed += push(table)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RemoteException.Offline) {
                    throw e
                } catch (e: Exception) {
                    if (!table.optionalRemote) throw e
                    Logger.w(TAG, "Таблица ${table.remoteTable} пока не синхронизируется (нет на сервере?)", e)
                }
            }
            syncProfile()
            SyncReport(pulled, pushed, conflicts, finishedAt = clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException.Offline) {
            SyncReport(pulled, pushed, conflicts, error = e.message, offline = true, finishedAt = clock())
        } catch (e: Exception) {
            Logger.w(TAG, "Синхронизация прервана", e)
            SyncReport(pulled, pushed, conflicts, error = e.message ?: e::class.simpleName, finishedAt = clock())
        }
        _status.value = SyncStatus.Done(report)
        report
    }

    private suspend fun pull(table: SyncableTable): Pair<Int, Int> {
        var count = 0
        var conflicts = 0
        val cursorRaw = local.cursor(table.remoteTable)
        val cursor = cursorRaw?.let { parseInstant(it) }
        var since = cursor?.minusSeconds(OVERLAP_SECONDS)
        var maxSeen = cursor
        while (true) {
            val rows = remote.fetchChanges(table.remoteTable, since, PAGE)
            for (row in rows) {
                if (applyRemote(table, row)) conflicts++
                count++
                val serverTime = (row["server_updated_at"] as? JsonPrimitive)?.contentOrNull?.let { parseInstant(it) }
                if (serverTime != null && (maxSeen == null || serverTime.isAfter(maxSeen))) maxSeen = serverTime
            }
            if (rows.size < PAGE) break
            since = rows.last()["server_updated_at"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { parseInstant(it) } ?: break
        }
        if (maxSeen != null && maxSeen != cursor) local.setCursor(table.remoteTable, maxSeen.toString())
        return count to conflicts
    }

    /** @return true, если был настоящий конфликт. */
    private suspend fun applyRemote(table: SyncableTable, remoteRow: JsonObject): Boolean {
        val id = (remoteRow["id"] as? JsonPrimitive)?.contentOrNull ?: return false
        val remoteUpdated = (remoteRow["updated_at"] as? JsonPrimitive)?.contentOrNull?.let { parseInstant(it) }?.toEpochMilli() ?: return false
        val payload = JsonObject(remoteRow.filterKeys { it !in SERVER_ONLY_COLUMNS })
        val localRow = table.row(id)
        val expected = localRow?.updatedAt
        when {
            localRow == null -> table.write(payload, dirty = false, syncedUpdatedAt = remoteUpdated, expectedLocalUpdatedAt = null, guard = true)
            !localRow.dirty -> if (remoteUpdated >= localRow.updatedAt) table.write(payload, dirty = false, syncedUpdatedAt = remoteUpdated, expectedLocalUpdatedAt = expected, guard = true)
            localRow.syncedUpdatedAt == remoteUpdated -> Unit // сервер не менялся с нашей базовой версии — отправим локальную
            remoteUpdated == localRow.updatedAt -> table.write(payload, dirty = false, syncedUpdatedAt = remoteUpdated, expectedLocalUpdatedAt = expected, guard = true) // наша же версия
            else -> {
                val merged = table.merge(localRow.payload, payload, localRow.updatedAt, remoteUpdated)
                val newUpdated = maxOf(localRow.updatedAt, remoteUpdated) + 1
                val stamped = JsonObject(merged + ("updated_at" to JsonPrimitive(Instant.ofEpochMilli(newUpdated).toString())))
                table.write(stamped, dirty = true, syncedUpdatedAt = remoteUpdated, expectedLocalUpdatedAt = expected, guard = true)
                Logger.i(TAG, "Конфликт в ${table.remoteTable} разрешён слиянием")
                return true
            }
        }
        return false
    }

    private suspend fun push(table: SyncableTable): Int {
        val dirty = table.dirtyRows()
        if (dirty.isEmpty()) return 0
        remote.upsert(table.remoteTable, dirty.map { it.payload })
        dirty.forEach { table.markSynced(it.id, it.updatedAt) }
        return dirty.size
    }

    private suspend fun syncProfile() {
        val p = profile ?: return
        val uid = userId() ?: return
        val remoteProfile = remote.fetchSingle(PROFILE_TABLE)
        val remoteUpdated = remoteProfile?.get("updated_at")?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { parseInstant(it) }
        val localUpdated = p.localUpdatedAt()
        if (remoteProfile != null && remoteUpdated != null && (localUpdated == null || (remoteUpdated.isAfter(localUpdated) && !p.isDirty()))) {
            p.applyRemote(remoteProfile)
            p.markSynced(remoteUpdated)
            return
        }
        if (p.isDirty()) {
            val body = p.local() ?: return
            remote.upsert(PROFILE_TABLE, listOf(JsonObject(body + ("id" to JsonPrimitive(uid)))))
            localUpdated?.let { p.markSynced(it) }
        }
    }

    companion object {
        private const val TAG = "Sync"
        const val PAGE = 500
        const val PROFILE_TABLE = "profiles"
        /** Перекрытие окна скачивания: транзакции могут фиксироваться не в порядке своих меток времени. */
        const val OVERLAP_SECONDS = 120L
        private val SERVER_ONLY_COLUMNS = setOf("user_id", "server_updated_at")
    }
}
