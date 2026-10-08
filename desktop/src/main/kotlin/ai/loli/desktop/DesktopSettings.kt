package ai.loli.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/** Настройки клиента для Windows: обычный файл рядом с базой. API-ключи здесь не хранятся — они в [SecretStore]. */
class DesktopSettings(private val file: File) {
    data class Values(
        val assistantName: String = "Лоли",
        val userName: String = "",
        val city: String = "",
        val persona: String = "caring",
        /** null — как в системе. */
        val darkTheme: Boolean? = null,
        // --- AI
        val aiEnabled: Boolean = false,
        val aiProvider: String = "openai",
        val aiModel: String = "",
        val aiEndpoint: String = "",
        // --- Голос
        val voiceReplies: Boolean = true,
        /** Скорость речи Windows: −10…10. */
        val speechRate: Int = 0,
        /** Имя голоса Windows; пусто — первый русский. */
        val voiceName: String = "",
        /** После ответа-вопроса продолжать слушать без нажатия. */
        val dialogMode: Boolean = true,
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Values> = _state
    val value: Values get() = _state.value

    @Synchronized
    fun update(change: (Values) -> Values) {
        val v = change(_state.value)
        if (v == _state.value) return
        _state.value = v
        runCatching {
            val p = Properties()
            p["assistantName"] = v.assistantName
            p["userName"] = v.userName
            p["city"] = v.city
            p["persona"] = v.persona
            v.darkTheme?.let { p["darkTheme"] = it.toString() }
            p["aiEnabled"] = v.aiEnabled.toString()
            p["aiProvider"] = v.aiProvider
            p["aiModel"] = v.aiModel
            p["aiEndpoint"] = v.aiEndpoint
            p["voiceReplies"] = v.voiceReplies.toString()
            p["speechRate"] = v.speechRate.toString()
            p["voiceName"] = v.voiceName
            p["dialogMode"] = v.dialogMode.toString()
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it.writer(Charsets.UTF_8), "Loli for Windows") }
        }
    }

    private fun load(): Values = runCatching {
        if (!file.exists()) return Values()
        val p = Properties()
        file.inputStream().use { p.load(it.reader(Charsets.UTF_8)) }
        val d = Values()
        Values(
            assistantName = p.getProperty("assistantName")?.ifBlank { null } ?: d.assistantName,
            userName = p.getProperty("userName").orEmpty(),
            city = p.getProperty("city").orEmpty(),
            persona = p.getProperty("persona") ?: d.persona,
            darkTheme = p.getProperty("darkTheme")?.toBooleanStrictOrNull(),
            aiEnabled = p.getProperty("aiEnabled")?.toBooleanStrictOrNull() ?: d.aiEnabled,
            aiProvider = p.getProperty("aiProvider") ?: d.aiProvider,
            aiModel = p.getProperty("aiModel").orEmpty(),
            aiEndpoint = p.getProperty("aiEndpoint").orEmpty(),
            voiceReplies = p.getProperty("voiceReplies")?.toBooleanStrictOrNull() ?: d.voiceReplies,
            speechRate = p.getProperty("speechRate")?.toIntOrNull()?.coerceIn(-10, 10) ?: 0,
            voiceName = p.getProperty("voiceName").orEmpty(),
            dialogMode = p.getProperty("dialogMode")?.toBooleanStrictOrNull() ?: d.dialogMode,
        )
    }.getOrDefault(Values())
}
