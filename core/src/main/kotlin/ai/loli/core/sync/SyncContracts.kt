package ai.loli.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * Строка таблицы в «серверном» формате (имена колонок как в Supabase) + служебные поля.
 * Единый формат позволяет движку синхронизации работать с любой таблицей одинаково.
 */
data class SyncRow(
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val dirty: Boolean,
    val syncedUpdatedAt: Long?,
    val payload: JsonObject,
)

/** Локальная таблица, участвующая в синхронизации. */
interface SyncableTable {
    /** Имя таблицы в Supabase. */
    val remoteTable: String
    /** Новая таблица: если на сервере её нет (старая схема Supabase), ошибка не прерывает синхронизацию остальных. */
    val optionalRemote: Boolean get() = false
    suspend fun dirtyRows(): List<SyncRow>
    suspend fun row(id: String): SyncRow?
    /**
     * Записать строку из серверного формата. [dirty] — нужно ли её потом отправить.
     * Если [guard] — запись выполняется, только если локальная версия не изменилась с момента чтения
     * ([expectedLocalUpdatedAt], null — строки не было): правка пользователя во время синхронизации не затирается.
     */
    suspend fun write(payload: JsonObject, dirty: Boolean, syncedUpdatedAt: Long?, expectedLocalUpdatedAt: Long? = null, guard: Boolean = false)
    /** Снять флаг dirty, если запись не менялась после отправки. */
    suspend fun markSynced(id: String, updatedAt: Long)
    /**
     * Слияние при настоящем конфликте (запись изменена и локально, и на сервере с момента последней синхронизации).
     * По умолчанию — last-write-wins по updated_at.
     */
    fun merge(local: JsonObject, remote: JsonObject, localUpdatedAt: Long, remoteUpdatedAt: Long): JsonObject =
        if (localUpdatedAt > remoteUpdatedAt) local else remote
    suspend fun clear()
}
