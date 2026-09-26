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
    /** Сколько тишины считать концом фразы. */
    val silenceMillis: Long = 2000,
    /** Слова-подсказки распознавателю: имя ассистента, частые команды. */
    val biasing: List<String> = emptyList(),
)

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object SpeechStarted : SpeechEvent
    /** Уровень громкости 0..1 — для анимации индикатора. */
    data class Level(val value: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    /** [alternatives] — другие варианты услышанного (от лучшего к худшему), если сервис их даёт. */
    data class Final(val text: String, val alternatives: List<String> = emptyList()) : SpeechEvent
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

/**
 * Поправки к распознанному тексту: частые ошибки распознавания русской речи и нашего имени.
 * Правим только то, что почти наверняка ошибка, — осмысленные слова не трогаем.
 */
object SpeechFixes {
    private val NAME_VARIANTS = listOf("лали", "лоле", "лолли", "лолы", "ляли", "лоля", "лолли", "лолей", "loli", "lolly", "лори", "лолу")

    fun apply(raw: String, assistantName: String = "Лоли"): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        // Имя в начале фразы: «Лали, запиши…» → «Лоли, запиши…».
        val first = t.substringBefore(' ').trim(',', '.', '!').lowercase().replace('ё', 'е')
        val name = assistantName.lowercase().replace('ё', 'е')
        if (name == "лоли" && first in NAME_VARIANTS) t = assistantName + t.substring(t.substringBefore(' ').trimEnd(',', '.', '!').length)
        val rules = listOf(
            Regex("""(?iu)(?<=^|\s)тыщ(?:а|и|у)?(?=\s|$|[.,!?])""") to "тысяч",
            Regex("""(?iu)(?<=\d)\s?(?:руб\.?|р\.)(?=\s|$|[.,!?])""") to " рублей",
            Regex("""(?iu)(?<=^|\s)пол\s(часа|минуты|года|месяца)""") to "пол$1",
            Regex("""(?iu)(?<=^|\s)на поминание""") to "напоминание",
            Regex("""(?iu)(?<=^|\s)на помни(?=\s|$)""") to "напомни",
            Regex("""(?iu)(?<=^|\s)за помни(?=\s|$)""") to "запомни",
            Regex("""(?iu)(?<=^|\s)за пиши(?=\s|$)""") to "запиши",
            Regex("""(?iu)(?<=^|\s)по ставь(?=\s|$)""") to "поставь",
            Regex("""(?iu)(?<=^|\s)до бавь(?=\s|$)""") to "добавь",
            Regex("""(?iu)(?<=^|\s)за дачу(?=\s|$)""") to "задачу",
            Regex("""\s{2,}""") to " ",
        )
        for ((re, to) in rules) t = t.replace(re, to)
        return t.trim()
    }

    /** Фраза оборвалась на полуслове («купи хлеб и», «напомни мне завтра в») — стоит дослушать продолжение. */
    fun looksUnfinished(text: String): Boolean {
        val last = text.trim().trimEnd('.', '!', '?').substringAfterLast(' ').lowercase().replace('ё', 'е')
        if (text.trim().endsWith(",")) return true
        return last in DANGLING
    }

    private val DANGLING = setOf(
        "и", "а", "но", "или", "на", "в", "во", "к", "ко", "по", "за", "с", "со", "у", "о", "об", "про", "для", "от", "до", "из",
        "потом", "затем", "что", "чтобы", "это", "мне", "мой", "моя", "мою", "еще", "также", "когда", "если", "как", "через", "после",
        "перед", "около", "где", "куда", "очень", "самый", "самая", "не",
    )
}
