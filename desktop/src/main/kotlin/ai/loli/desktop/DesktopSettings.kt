package ai.loli.desktop

import ai.loli.core.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Настройки клиента для Windows: обычный файл рядом с базой. API-ключи здесь не хранятся — они в [SecretStore].
 * Файл пишется в фоне и не чаще раза в полсекунды: ввод имени по буквам не дёргает диск из окна.
 */
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
        /** «loli» — встроенный русский голос (как на телефоне), «windows» — синтезатор Windows. */
        val voiceMode: String = "loli",
        /** Встроенный голос: denis, irina, vera… */
        val loliVoice: String = "denis",
        /** Скорость встроенного голоса в процентах: 70…150. */
        val loliSpeed: Int = 100,
        // --- Оформление
        /** Цвет Лоли: loli, indigo, green… (как на телефоне). */
        val accent: String = "loli",
        // --- Напоминания
        /** Окно напоминания поверх всех программ (не зависит от уведомлений Windows). */
        val reminderPopup: Boolean = true,
        /** Звуковой сигнал при напоминании. */
        val reminderSound: Boolean = true,
        // --- Окно (запоминается между запусками)
        val windowWidth: Int = 1280,
        val windowHeight: Int = 820,
        /** Положение окна; null — пусть решит Windows. */
        val windowX: Int? = null,
        val windowY: Int? = null,
        val windowMaximized: Boolean = false,
        /** Показывали ли подсказку «Лоли свернулась в трей». */
        val trayHintShown: Boolean = false,
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Values> = _state
    val value: Values get() = _state.value

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loli-settings").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)

    @Synchronized
    fun update(change: (Values) -> Values) {
        val v = change(_state.value)
        if (v == _state.value) return
        _state.value = v
        if (pending.compareAndSet(false, true)) writer.schedule({ pending.set(false); save(_state.value) }, 500, TimeUnit.MILLISECONDS)
    }

    /** Записать немедленно (при выходе из программы). */
    fun flush() {
        pending.set(false)
        save(_state.value)
    }

    @Synchronized
    private fun save(v: Values) {
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
            p["voiceMode"] = v.voiceMode
            p["loliVoice"] = v.loliVoice
            p["loliSpeed"] = v.loliSpeed.toString()
            p["accent"] = v.accent
            p["reminderPopup"] = v.reminderPopup.toString()
            p["reminderSound"] = v.reminderSound.toString()
            p["windowWidth"] = v.windowWidth.toString()
            p["windowHeight"] = v.windowHeight.toString()
            v.windowX?.let { p["windowX"] = it.toString() }
            v.windowY?.let { p["windowY"] = it.toString() }
            p["windowMaximized"] = v.windowMaximized.toString()
            p["trayHintShown"] = v.trayHintShown.toString()
            file.parentFile?.mkdirs()
            // Сначала во временный файл, потом подмена: обрыв питания не оставит настройки пустыми.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { out -> out.writer(Charsets.UTF_8).also { p.store(it, "Loli for Windows") }.flush() }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Logger.w("Settings", "Настройки не сохранились", it) }
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
            voiceMode = p.getProperty("voiceMode")?.takeIf { it == "loli" || it == "windows" } ?: d.voiceMode,
            loliVoice = p.getProperty("loliVoice")?.ifBlank { null } ?: d.loliVoice,
            loliSpeed = p.getProperty("loliSpeed")?.toIntOrNull()?.coerceIn(70, 150) ?: d.loliSpeed,
            accent = p.getProperty("accent")?.ifBlank { null } ?: d.accent,
            reminderPopup = p.getProperty("reminderPopup")?.toBooleanStrictOrNull() ?: d.reminderPopup,
            reminderSound = p.getProperty("reminderSound")?.toBooleanStrictOrNull() ?: d.reminderSound,
            windowWidth = p.getProperty("windowWidth")?.toIntOrNull()?.coerceIn(960, 8000) ?: d.windowWidth,
            windowHeight = p.getProperty("windowHeight")?.toIntOrNull()?.coerceIn(640, 8000) ?: d.windowHeight,
            windowX = p.getProperty("windowX")?.toIntOrNull(),
            windowY = p.getProperty("windowY")?.toIntOrNull(),
            windowMaximized = p.getProperty("windowMaximized")?.toBooleanStrictOrNull() ?: false,
            trayHintShown = p.getProperty("trayHintShown")?.toBooleanStrictOrNull() ?: false,
        )
    }.getOrDefault(Values())
}
