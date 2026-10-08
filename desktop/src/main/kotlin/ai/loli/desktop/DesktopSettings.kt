package ai.loli.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/** Настройки клиента для Windows: обычный файл рядом с базой. Секретов здесь нет. */
class DesktopSettings(private val file: File) {
    data class Values(
        val assistantName: String = "Лоли",
        val userName: String = "",
        val city: String = "",
        val persona: String = "caring",
        val darkTheme: Boolean? = null,
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Values> = _state
    val value: Values get() = _state.value

    @Synchronized
    fun update(change: (Values) -> Values) {
        val v = change(_state.value)
        _state.value = v
        runCatching {
            val p = Properties()
            p["assistantName"] = v.assistantName
            p["userName"] = v.userName
            p["city"] = v.city
            p["persona"] = v.persona
            v.darkTheme?.let { p["darkTheme"] = it.toString() }
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it.writer(Charsets.UTF_8), "Лоли для Windows") }
        }
    }

    private fun load(): Values = runCatching {
        if (!file.exists()) return Values()
        val p = Properties()
        file.inputStream().use { p.load(it.reader(Charsets.UTF_8)) }
        Values(
            assistantName = p.getProperty("assistantName")?.ifBlank { null } ?: "Лоли",
            userName = p.getProperty("userName").orEmpty(),
            city = p.getProperty("city").orEmpty(),
            persona = p.getProperty("persona") ?: "caring",
            darkTheme = p.getProperty("darkTheme")?.toBooleanStrictOrNull(),
        )
    }.getOrDefault(Values())
}
