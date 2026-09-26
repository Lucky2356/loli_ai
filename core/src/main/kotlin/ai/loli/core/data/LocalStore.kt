package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.sync.SyncableTable
import ai.loli.core.util.TimeSource
import ai.loli.core.util.UpdateStamp
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Точка сборки локального хранилища. Драйвер SQLite передаётся снаружи:
 * на Android — зашифрованный SQLCipher, в тестах и на десктопе — JDBC.
 */
class LocalStore(
    driver: SqlDriver,
    time: TimeSource,
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val db: LoliDatabase = LoliDatabase(driver)
    val changes = LocalChangeBus()
    private val stamp = UpdateStamp(time)

    val notes = SqlNoteRepository(db, stamp, changes, dispatcher)
    val expenses = SqlExpenseRepository(db, stamp, changes, dispatcher)
    val tasks = SqlTaskRepository(db, stamp, changes, dispatcher)
    val reminders = SqlReminderRepository(db, stamp, changes, dispatcher)
    val memories = SqlMemoryRepository(db, stamp, changes, dispatcher)
    val conversations = SqlConversationRepository(db, stamp, changes, dispatcher)
    val embeddings = EmbeddingStore(db, dispatcher)
    val shopping = SqlShoppingRepository(db, stamp, changes, dispatcher)
    val routines = SqlRoutineRepository(db, stamp, changes, dispatcher)
    val secrets = SecretNoteStore(db, dispatcher) { time.now() }

    /** Порядок важен только для удобства отладки; таблицы независимы. */
    val syncTables: List<SyncableTable> = listOf(notes, expenses, tasks, reminders, memories, conversations, shopping, routines)

    suspend fun cursor(table: String): String? = withContext(dispatcher) { db.syncStateQueries.selectCursor(table).executeAsOneOrNull() }
    suspend fun setCursor(table: String, value: String) = withContext(dispatcher) { db.syncStateQueries.upsertCursor(table, value); Unit }

    /** Полная очистка локальных данных (выход из аккаунта). */
    suspend fun wipe() = withContext(dispatcher) {
        db.transaction {
            db.noteQueries.deleteAll(); db.expenseQueries.deleteAll(); db.taskQueries.deleteAll()
            db.reminderQueries.deleteAll(); db.memoryQueries.deleteAll(); db.conversationQueries.deleteAll()
            db.embeddingQueries.deleteAll(); db.syncStateQueries.deleteAll()
            db.shoppingItemQueries.deleteAll(); db.routineQueries.deleteAll()
        }
    }

    /** Есть ли неотправленные изменения. */
    suspend fun pendingChanges(): Int = syncTables.sumOf { it.dirtyRows().size }
}
