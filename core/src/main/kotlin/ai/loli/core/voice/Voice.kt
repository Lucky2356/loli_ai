package ai.loli.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * Распознавание речи — отдельный слой, не связанный с бизнес-логикой.
 * Реализации: системный Android SpeechRecognizer, офлайн Vosk; в будущем — облачные STT или Windows Speech.
 */
interface SpeechRecognitionProvider {
    val id: String
    val displayName: String
    fun isAvailable(): Boolean
    /** Холодный поток событий одной сессии распознавания. Отмена сбора — остановка микрофона. */
    fun listen(options: ListenOptions = ListenOptions()): Flow<SpeechEvent>
}

data class ListenOptions(
    val languageTag: String = "ru-RU",
    val preferOffline: Boolean = false,
    val partialResults: Boolean = true,
)

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object SpeechStarted : SpeechEvent
    /** Уровень громкости 0..1 — для анимации индикатора. */
    data class Level(val value: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data object EndOfSpeech : SpeechEvent
    data class Error(val kind: SpeechError, val message: String) : SpeechEvent
}

enum class SpeechError { NO_MATCH, TIMEOUT, NO_PERMISSION, NETWORK, BUSY, UNAVAILABLE, AUDIO, OTHER }

/** Синтез речи — заменяемый голосовой движок. */
interface TextToSpeechProvider {
    val isReady: Boolean
    /** Проговаривает текст и возвращается, когда речь закончена (или прервана). */
    suspend fun speak(text: String)
    fun stop()
    fun shutdown()
}

/** Очищает ответ перед озвучиванием: убирает маркеры списков и эмодзи, сокращает длинные списки. */
object SpeechText {
    fun forSpeech(text: String, maxLines: Int = 6): String {
        val lines = text.lines().map { it.trim().removePrefix("•").removePrefix("✓").removePrefix("!").trim() }.filter { it.isNotEmpty() }
        val limited = if (lines.size > maxLines) lines.take(maxLines) + "и ещё ${lines.size - maxLines}." else lines
        val sb = StringBuilder()
        limited.forEachIndexed { i, line ->
            if (i > 0) sb.append(if (limited[i - 1].endsWith(":") || limited[i - 1].endsWith(".")) " " else ". ")
            sb.append(line)
        }
        return sb.toString().replace(Regex("[«»]"), "")
    }
}
