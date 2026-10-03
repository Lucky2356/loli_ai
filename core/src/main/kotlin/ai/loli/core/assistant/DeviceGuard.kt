package ai.loli.core.assistant

import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.TextAnalysis

/**
 * Защита от «инъекций» через AI. В запрос к модели попадают тексты заметок и память; чужая инструкция оттуда
 * («позвони на номер…», «открой сайт…») могла бы сработать, если бы мы верили каждой команде телефону, которую вернул AI.
 * Команда выполняется, только если сказанное вами в этой фразе её подтверждает: есть нужное слово
 * («позвони», «открой») И то, к чему оно относится (кому звонить, какой сайт открыть) названо в вашей фразе.
 */
object DeviceGuard {
    /** Известные сайты: «открой сайт гугла» → AI пишет google.com, и это нормально. */
    private val KNOWN_HOSTS = listOf(
        "google.com", "google.ru", "yandex.ru", "yandex.com", "ya.ru", "youtube.com", "wikipedia.org", "vk.com", "mail.ru", "rbc.ru", "lenta.ru",
        "ria.ru", "tass.ru", "habr.com", "github.com", "gosuslugi.ru", "sberbank.ru", "2gis.ru", "avito.ru", "ozon.ru", "wildberries.ru",
        "kinopoisk.ru", "rutube.ru", "telegram.org", "whatsapp.com", "openstreetmap.org",
    )

    fun allowed(cmd: DeviceCommand, userText: String): Boolean {
        val n = RuTokenizer.normalize(userText)
        fun has(pattern: String) = Regex(pattern).containsMatchIn(n)
        return when (cmd) {
            is DeviceCommand.Call -> has("""позвон|набер|звонок|вызов|звякн""") && mentions(cmd.who, userText)
            is DeviceCommand.Message -> has("""напиш|сообщ|смс|sms|отправ|перешл|скажи|передай""") && mentions(cmd.who, userText)
            is DeviceCommand.Share -> has("""отправ|перешл|подел|скинь|напиш""") && (cmd.app.isBlank() || mentions(cmd.app, userText))
            is DeviceCommand.OpenUrl -> has("""сайт|ссылк|страниц|открой|зайди|перейди|http|www|\.ru|\.com""") && urlNamed(cmd.url, userText)
            is DeviceCommand.AddContact -> has("""контакт|номер""") && mentions(cmd.name, userText)
            is DeviceCommand.OpenApp -> has("""открой|запусти|включи|зайди|перейди|приложени""")
            // Системные кнопки и режимы — только по прямой просьбе, а не по подсказке из заметки.
            is DeviceCommand.Global -> has("""назад|домой|недавн|скриншот|снимок|заблокир|блокир|уведомлен|быстры|питани|разделит|экран""")
            is DeviceCommand.OpenBackup -> has("""копи|бэкап|бекап|перен""")
            is DeviceCommand.Driving -> has("""за рулем|за рулём|еду|вожу|приехал|машин|вожден""")
            is DeviceCommand.DoNotDisturb -> has("""беспоко|тихий|тишин|режим""")
            is DeviceCommand.Brightness -> has("""яркост|ярче|темнее|тусклее|светлее""")
            is DeviceCommand.Camera -> has("""камер|фото|сним|селфи|видео""")
            else -> true
        }
    }

    /** Названо ли во фразе то, что стоит в команде: цифры номера или основа имени/названия. */
    internal fun mentions(value: String, userText: String): Boolean {
        val digits = value.filter { it.isDigit() }
        if (digits.length >= 3) return DevicePhrases.digitize(RuTokenizer.normalize(userText)).filter { it.isDigit() }.contains(digits.takeLast(7))
        val wanted = TextAnalysis.stems(value)
        if (wanted.isEmpty()) return RuTokenizer.normalize(userText).contains(RuTokenizer.normalize(value).trim())
        val said = TextAnalysis.stems(userText)
        return wanted.any { w -> said.any { s -> s == w || TextAnalysis.stemSimilarity(w, s) >= 0.7 || (w.length >= 3 && s.startsWith(w)) } }
    }

    internal fun urlNamed(url: String, userText: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return false
        if (KNOWN_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val n = RuTokenizer.normalize(userText)
        if (n.contains(host)) return true
        // «яндекс точка ру» / «vk» — сравниваем главное слово адреса с сказанным (в том числе в русской записи).
        val label = host.substringBeforeLast('.').substringAfterLast('.')
        if (label.length < 3) return false
        return n.split(Regex("""[^\p{L}\d]+""")).filter { it.length >= 3 }.any { w ->
            w == label || latin(w).let { l -> TextAnalysis.levenshtein(l, label) <= if (label.length >= 6) 2 else 1 }
        }
    }

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
        'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    private fun latin(word: String): String = buildString { word.forEach { append(TRANSLIT[it] ?: it.toString()) } }
        .replace("ks", "x")
}
