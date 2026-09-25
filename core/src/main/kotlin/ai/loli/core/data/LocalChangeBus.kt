package ai.loli.core.data

import ai.loli.core.domain.LocalChangeListener
import java.util.concurrent.CopyOnWriteArrayList

/** Шина локальных изменений: репозитории сообщают о записи, слушатели (планировщик синхронизации) реагируют. */
class LocalChangeBus {
    private val listeners = CopyOnWriteArrayList<LocalChangeListener>()

    fun addListener(listener: LocalChangeListener) { listeners += listener }
    fun removeListener(listener: LocalChangeListener) { listeners -= listener }
    fun notifyChanged(table: String) = listeners.forEach { runCatching { it.onLocalChange(table) } }
}
