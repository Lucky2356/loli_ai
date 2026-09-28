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
    /** Озвучить на другом языке (перевод). [language] — ISO-код; null — как обычно. */
    suspend fun speakIn(text: String, language: String?) = speak(text)
    fun stop()
    fun shutdown()
}

/** Очищает ответ перед озвучиванием: убирает маркеры списков и эмодзи, сокращает длинные списки. */
object SpeechText {
    fun forSpeech(text: String, maxLines: Int = 7): String {
        val lines = clean(text).lines().map { it.trim().removePrefix("•").removePrefix("✓").removePrefix("!").trim() }.filter { it.isNotEmpty() }
        // Заголовок («Прогноз на неделю:») не считается пунктом: неделя — это 7 дней, их читаем целиком.
        val head = lines.takeWhile { it.endsWith(":") }.size.coerceAtMost(1)
        val items = lines.size - head
        val limited = if (items > maxLines) lines.take(head + maxLines) + "и ещё ${items - maxLines}." else lines
        val sb = StringBuilder()
        limited.forEachIndexed { i, line ->
            if (i > 0) sb.append(if (limited[i - 1].last() in ".:!?…;") " " else ". ")
            sb.append(line)
        }
        return declineUnits(pronounce(sb.toString().replace(Regex("[«»]"), ""))).replace(Regex("""\s{2,}"""), " ").trim()
    }

    /** Разметка из ответов AI и символы, которые синтезатор читает вслух или спотыкается: **, #, `, ссылки, эмодзи. */
    fun clean(text: String): String = text
        .replace(Regex("""\[([^\]]+)]\((?:https?://)?[^)]+\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "ссылка")
        .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        // Нумерованный список оставляем с номерами (шаги рецепта), маркеры «-», «*» убираем; «- 5°» — это минус, не маркер.
        .replace(Regex("""(?m)^\s*(\d+)[.)]\s+"""), "$1. ")
        .replace(Regex("""(?m)^\s*[-*+]\s+(?!\d+(?:,\d+)?\s?°)"""), "• ")
        .replace(Regex("""(?m)^\s*-\s+(?=\d+(?:,\d+)?\s?°)"""), "-")
        // Разметка — только парная: «2*3», «snake_case» и «~5 км» остаются по смыслу.
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""__(.+?)__"""), "$1").replace(Regex("""~~(.+?)~~"""), "$1")
        .replace(Regex("""`{1,3}([^`]*)`{1,3}"""), "$1")
        .replace(Regex("""(?<![\p{L}\d*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\p{L}\d*])"""), "$1")
        .replace(Regex("""(\d)\s*[*×]\s*(\d)"""), "$1 умножить на $2")
        .replace(Regex("""(?<![\p{L}\d])~\s?(?=\d)"""), "около ")
        .replace(Regex("""(?m)^\s*[-*]{3,}\s*$"""), "")
        // Эмодзи и прочие пиктограммы: стрелки, часы ⏰⌛, клавиши-цифры.
        .replace(Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{2190}-\x{21FF}\x{2300}-\x{23FF}\x{20E3}\x{FE0F}\x{200D}]"""), "")

    private val WORDS = listOf(
        "т. е.", "т.е.", "т. к.", "т.к.", "и т. д.", "и т.д.", "и т. п.", "и т.п.", "т. н.", "т.н.",
    ).zip(listOf("то есть", "то есть", "так как", "так как", "и так далее", "и так далее", "и тому подобное", "и тому подобное", "так называемый", "так называемый"))

    private val NAMES = mapOf(
        "Telegram" to "Телеграм", "WhatsApp" to "Вотсап", "YouTube" to "Ютуб", "Wi-Fi" to "вай-фай", "WiFi" to "вай-фай", "Bluetooth" to "блютус",
        "Google" to "Гугл", "Android" to "Андроид", "iPhone" to "айфон", "Viber" to "Вайбер", "VK" to "ВК", "SMS" to "эсэмэс", "СМС" to "эсэмэс",
        "GPS" to "джи-пи-эс", "OK" to "окей", "Ok" to "окей", "AI" to "ИИ", "FM" to "эф-эм", "USB" to "ю-эс-би", "PDF" to "пэ-дэ-эф",
        "Zoom" to "Зум", "Skype" to "Скайп", "Instagram" to "Инстаграм", "TikTok" to "Тикток", "Spotify" to "Спотифай", "email" to "имейл", "e-mail" to "имейл",
        "Loli" to "Лоли", "OpenAI" to "Опен-эй-ай", "ChatGPT" to "чат джи-пи-ти", "Wikipedia" to "Википедия",
    )

    /** Время, сокращения, телефоны, английские названия — так, как это говорят вслух. */
    fun pronounce(text: String): String {
        var t = text
        for ((a, b) in WORDS) t = t.replace(a, b)
        for ((a, b) in NAMES) t = t.replace(Regex("""(?<![\p{L}])${Regex.escape(a)}(?![\p{L}])"""), b)
        // Телефон «+7 916 123-45-67» — группами, без «минус».
        t = Regex("""(\+?\d[\d ()]{5,}\d)-(\d{2})-(\d{2})""").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" }
        // Время: «в 10:00» → «в 10 ч.» (потом «10 часов»), «10:30» → «10 30», «10:05» → «10 05».
        // Счёт матча и масштаб («3:0», «1:25») — не время.
        fun notTime(m: MatchResult) = NOT_TIME.containsMatchIn(t.substring(maxOf(0, m.range.first - 24), m.range.first))
        t = Regex("""(?<![\d:])([01]?\d|2[0-3]):00(?![\d:])""").replace(t) { if (notTime(it)) it.value else "${it.groupValues[1].trimStart('0').ifEmpty { "0" }} ч." }
        t = Regex("""(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])""").replace(t) { m ->
            if (notTime(m)) return@replace m.value
            val mm = m.groupValues[2]
            "${m.groupValues[1].trimStart('0').ifEmpty { "0" }} ${if (mm.startsWith("0")) "ноль ${mm.substring(1)}" else mm}"
        }
        // Сокращения после чисел: «5 км», «60 км/ч», «2 кг», «2026 г.».
        // «2026 г.» — год («в 2026 году», «2026 года»); «200 г.» — граммы.
        t = Regex("""(?<![\d,])(1[89]\d\d|20\d\d)\s?г\.""").replace(t) { m ->
            val before = t.substring(maxOf(0, m.range.first - 3), m.range.first).lowercase()
            "${m.groupValues[1]} ${if (Regex("""(?:^|\s)в\s$""").containsMatchIn(before)) "году" else "года"}${keepDot(t, m)}"
        }
        t = Regex("""(\d+)\s?км/ч""").replace(t) { "${it.groupValues[1]} ${ai.loli.core.assistant.RuFormat.plural(it.groupValues[1].takeLast(3).toLong(), "километр", "километра", "километров")} в час" }
        val units = listOf(
            "км" to Triple("километр", "километра", "километров"), "кг" to Triple("килограмм", "килограмма", "килограммов"),
            "г\\." to Triple("грамм", "грамма", "грамм"), "мм" to Triple("миллиметр", "миллиметра", "миллиметров"), "сек\\." to Triple("секунда", "секунды", "секунд"),
            "гПа" to Triple("гектопаскаль", "гектопаскаля", "гектопаскалей"), "мм рт\\. ст\\." to Triple("миллиметр ртутного столба", "миллиметра ртутного столба", "миллиметров ртутного столба"),
        )
        for ((u, forms) in units.sortedByDescending { it.first.length }) {
            t = Regex("""(\d+)(?:,(\d+))?\s?$u(?![\p{L}])""").replace(t) { m ->
                val n = m.groupValues[1].takeLast(9).toLongOrNull() ?: return@replace m.value
                val frac = m.groupValues[2]
                // «1,5 км» — «полтора километра»: с дробью родительный падеж единственного числа.
                val w = if (frac.isNotEmpty()) forms.second else ai.loli.core.assistant.RuFormat.plural(n, forms.first, forms.second, forms.third)
                "${m.groupValues[1]}${if (frac.isNotEmpty()) ",$frac" else ""} $w${if (u.endsWith(".")) keepDot(t, m) else ""}"
            }
        }
        t = t.replace(Regex("""№\s?"""), "номер ").replace(Regex("""(?<![\p{L}])ул\.\s"""), "улица ").replace(Regex("""(?<![\p{L}])пр-т(?![\p{L}])"""), "проспект")
            .replace(" & ", " и ").replace("+/-", "плюс-минус").replace("±", "плюс-минус")
        return t
    }

    private val NOT_TIME = Regex("""(?iu)(?:сч[её]т|матч|игр[аеуы]|масштаб|соотношени|пропорци|тайм|партии|сет)""")

    /** Сокращение с точкой в конце предложения («… 2 ч. Потом…»): точку, съеденную заменой, возвращаем. */
    private fun keepDot(t: String, m: MatchResult): String {
        val rest = t.substring(m.range.last + 1)
        return if (Regex("""^\s+[А-ЯЁA-Z]""").containsMatchIn(rest)) "." else ""
    }

    /** «1 ₽» → «1 рубль», «22 ₽» → «22 рубля», «3 дн.» → «3 дня»: движок речи не склоняет символы и сокращения. */
    fun declineUnits(text: String): String {
        val units = listOf(
            Regex("""(\d[\d  ]*)(?:,(\d+))?\s?₽""") to Triple("рубль", "рубля", "рублей"),
            Regex("""(\d[\d  ]*)(?:,(\d+))?\s?\$""") to Triple("доллар", "доллара", "долларов"),
            Regex("""(\d[\d  ]*)(?:,(\d+))?\s?€""") to Triple("евро", "евро", "евро"),
            Regex("""(\d+)(?:,(\d+))?\s?мин\.""") to Triple("минута", "минуты", "минут"),
            Regex("""(\d+)(?:,(\d+))?\s?ч\.""") to Triple("час", "часа", "часов"),
            Regex("""(\d+)(?:,(\d+))?\s?дн\.""") to Triple("день", "дня", "дней"),
            Regex("""(\d+)(?:,(\d+))?\s?нед\.""") to Triple("неделя", "недели", "недель"),
            Regex("""(\d+)(?:,(\d+))?\s?мес\.""") to Triple("месяц", "месяца", "месяцев"),
            Regex("""(\d+)(?:,(\d+))?\s?м/с""") to Triple("метр в секунду", "метра в секунду", "метров в секунду"),
            Regex("""(\d+)(?:,(\d+))?\s?%""") to Triple("процент", "процента", "процентов"),
        )
        var t = text
        // «+12°» → «плюс 12 градусов», «−3°» → «минус 3 градуса».
        t = Regex("""(?:(?<=^|[\s(…:])([+−-]))?(\d+)(?:,(\d+))?\s?°\s?[CС]?(?![\p{L}])""").replace(t) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[3]
            val sign = when (m.groupValues[1]) { "+" -> "плюс "; "−", "-" -> if (n == 0L && frac.trim('0').isEmpty()) "" else "минус "; else -> "" }
            // «12,5°» — «двенадцать и пять десятых градуса»: с дробью родительный падеж единственного числа.
            val word = if (frac.isNotEmpty()) "градуса" else ai.loli.core.assistant.RuFormat.plural(n, "градус", "градуса", "градусов")
            "$sign$n${if (frac.isNotEmpty()) ",$frac" else ""} $word"
        }
        for ((re, forms) in units) {
            t = re.replace(t) { m ->
                val whole = m.groupValues[1].trim()
                val n = whole.replace(" ", "").replace(" ", "").toLongOrNull() ?: return@replace m.value
                val frac = m.groupValues[2]
                // С копейками — родительный падеж единственного числа: «12,50 рубля»
                val word = if (frac.isNotEmpty()) forms.second else ai.loli.core.assistant.RuFormat.plural(n, forms.first, forms.second, forms.third)
                val dot = if (re.pattern.endsWith("\\.")) keepDot(t, m) else ""
                "$whole${if (frac.isNotEmpty()) ",$frac" else ""} $word$dot"
            }
        }
        return t
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
            // «потратил 5000 за дачу» — это дача; «добавь за дачу» — задача.
            Regex("""(?iu)(?<=^|(?:добавь|поставь|создай|запиши|новую|одну)\s)за дачу(?=\s|$)""") to "задачу",
            // Распознаватель офлайн часто разрывает команды на приставку и корень.
            Regex("""(?iu)(?<=^|\s)по года(?=\s|$|[.,!?])""") to "погода",
            Regex("""(?iu)(?<=^|\s)раз буди(?=\s|$)""") to "разбуди",
            Regex("""(?iu)(?<=^|\s)от мени(?=\s|$)""") to "отмени",
            Regex("""(?iu)^((?:лоли,?\s+)?)вы ключи(?=\s+(?:свет|музыку|звук|радио|таймер|будильник|экран|телефон|фонарик|интернет|блютус|вай-фай|вайфай|вс[её]|телевизор|утюг|плиту)|$)""") to "$1выключи",
            Regex("""(?iu)(?<=^|\s)за секи(?=\s|$)""") to "засеки",
            Regex("""(?iu)(?<=^|\s)про читай(?=\s|$)""") to "прочитай",
            Regex("""(?iu)^((?:лоли,?\s+)?)на бери(?=\s|$)""") to "$1набери",
            Regex("""(?iu)(?<=^|\s)по звони(?=\s|$)""") to "позвони",
            Regex("""(?iu)(?<=^|\s)пере веди(?=\s|$)""") to "переведи",
            Regex("""(?iu)(?<=^|\s)на пиши(?=\s|$)""") to "напиши",
            Regex("""(?iu)(?<=^|\s)тай мер(?=\s|$)""") to "таймер",
            Regex("""(?iu)(?<=^|\s)буди льник(?=\s|$)""") to "будильник",
            Regex("""(?iu)(?<=^|\s)(?:что|че|чё|чо) там (?:по|с) погод\S*""") to "какая погода",
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
