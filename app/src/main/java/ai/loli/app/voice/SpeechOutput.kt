package ai.loli.app.voice

import ai.loli.core.util.Logger
import ai.loli.core.voice.TextToSpeechProvider

/**
 * Кто озвучивает ответы Лоли.
 *  - «Голос Лоли» (встроенный) — одинаково по-русски на любом телефоне;
 *  - синтезатор телефона — только проверенный (Google, Samsung, RHVoice) и только если в нём есть русский.
 * Непроверенным синтезаторам (Vivo/iFlytek, Huawei, Xiaomi, Oppo…) не верим: они отвечают «русский есть»,
 * а читают текст китайской моделью. Пока встроенный голос не скачан, такой телефон отвечает только текстом.
 */
class SpeechOutput(
    private val system: () -> AndroidTtsProvider,
    private val systemCreated: () -> Boolean,
    private val loli: LoliVoice,
    private val mode: () -> Mode,
) : TextToSpeechProvider {

    enum class Mode { AUTO, LOLI, SYSTEM }

    enum class Engine { LOLI, SYSTEM, NONE }

    /** Чем Лоли будет говорить по-русски прямо сейчас. */
    suspend fun current(): Engine = when (mode()) {
        Mode.LOLI -> if (loli.isAvailable()) Engine.LOLI else Engine.NONE
        Mode.SYSTEM -> Engine.SYSTEM
        Mode.AUTO -> when {
            loli.isAvailable() -> Engine.LOLI
            systemTrusted() -> Engine.SYSTEM
            else -> Engine.NONE
        }
    }

    /** Синтезатор телефона проверенный и знает русский. */
    suspend fun systemTrusted(): Boolean {
        val s = system()
        val status = s.checkRussian()
        return status == AndroidTtsProvider.RuStatus.OK && s.activeEngine() in TRUSTED
    }

    /** Нужно ли предложить скачать встроенный голос (на этом телефоне по-русски сейчас говорить нечем). */
    suspend fun needsLoliVoice(): Boolean = current() == Engine.NONE

    override val isReady: Boolean get() = loli.isAvailable() || (systemCreated() && system().isReady)

    override suspend fun speak(text: String) = speakIn(text, null)

    override suspend fun speakIn(text: String, language: String?) {
        if (text.isBlank()) return
        // Перевод на другой язык — только синтезатором телефона (у встроенного голоса лишь русский).
        if (language != null && language != "ru") { system().speakIn(text, language); return }
        when (current()) {
            Engine.LOLI -> if (!loli.speak(text)) system().speak(text)
            Engine.SYSTEM -> system().speak(text)
            Engine.NONE -> Logger.w(TAG, "Нет русского голоса — ответ только текстом")
        }
    }

    override fun stop() {
        loli.stop()
        if (systemCreated()) system().stop()
    }

    override fun shutdown() {
        loli.release()
        if (systemCreated()) system().shutdown()
    }

    companion object {
        private const val TAG = "Speech"

        /** Синтезаторы, которые действительно говорят по-русски, если так отвечают. */
        val TRUSTED = setOf(
            AndroidTtsProvider.GOOGLE_TTS,
            "com.samsung.SMT",
            "com.github.olga_yakovleva.rhvoice.android",
        )
    }
}
