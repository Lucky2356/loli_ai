package ai.loli.core.data

import ai.loli.core.db.LoliDatabase
import ai.loli.core.sync.SyncableTable
import ai.loli.core.util.UpdateStamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Общая инфраструктура SQL-репозиториев: диспетчер ввода-вывода, метки времени, уведомления. */
abstract class SqlTableBase(
    protected val db: LoliDatabase,
    protected val stamp: UpdateStamp,
    private val bus: LocalChangeBus,
    protected val dispatcher: CoroutineDispatcher,
) : SyncableTable {
    protected suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }
    protected fun changed() = bus.notifyChanged(remoteTable)
    protected fun nowMs(): Long = stamp.next().toEpochMilli()
}

internal fun Boolean.toLong(): Long = if (this) 1L else 0L
