package ai.loli.core.assistant

import ai.loli.core.model.ShoppingItem
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.core.nlp.RuTokenizer
import java.time.LocalDate

/**
 * Команды 1.7.0: списки покупок, сценарии, дни рождения, секретные заметки, перевод.
 * Разбираются на устройстве раньше облачного AI — это точные команды, AI их только запутает.
 */
class SpecialCommands(private val dates: RuDateTimeParser = RuDateTimeParser()) {

    /** Запрос перевода: целевой язык (ISO-код) и фраза. */
    data class Translation(val language: String, val languageRu: String, val phrase: String)

    fun parse(input: String, today: LocalDate): AssistantPlan? {
        val text = input.trim().trimEnd('.', '!', '?').trim()
        if (text.isEmpty()) return null
        val n = RuTokenizer.normalize(text)
        val action = shopping(text, n) ?: routine(text, n) ?: birthday(text, n, today) ?: secret(text, n) ?: return null
        return AssistantPlan("", listOf(action))
    }

    // --- Списки покупок ---------------------------------------------------------

    private fun shopping(text: String, n: String): AssistantAction? {
        val list = ShoppingItem.DEFAULT_LIST
        // «добавь в покупки молоко и хлеб», «запиши в список покупок: хлеб, яйца»
        Regex("""^(?:добавь|допиши|запиши|внеси|закинь|кинь|положи|включи)\s+(?:мне\s+)?(?:в\s+)?(?:мой\s+)?(?:список\s+покупок|список\s+продуктов|покупки|продуктовый\s+список)[:,]?\s+(.+)$""")
            .find(n)?.let { return AssistantAction.AddToList(list, items(sub(text, it.groups[1]!!))) }
        // «добавь молоко в покупки», «запиши хлеб и яйца в список покупок»
        Regex("""^(?:добавь|допиши|запиши|внеси|закинь|кинь)\s+(.+?)\s+в\s+(?:мой\s+)?(?:список\s+покупок|список\s+продуктов|покупки)$""")
            .find(n)?.let { return AssistantAction.AddToList(list, items(sub(text, it.groups[1]!!))) }
        // «в список покупок добавь сметану», «в покупки запиши хлеб»
        Regex("""^в\s+(?:мой\s+)?(?:список\s+покупок|список\s+продуктов|покупки)\s+(?:добавь|допиши|запиши|внеси|закинь)[:,]?\s+(.+)$""")
            .find(n)?.let { return AssistantAction.AddToList(list, items(sub(text, it.groups[1]!!))) }
        // «купить: молоко, хлеб» / «в магазин: …»
        Regex("""^(?:список\s+покупок|в\s+магазин|купить)\s*:\s*(.+)$""")
            .find(n)?.let { return AssistantAction.AddToList(list, items(sub(text, it.groups[1]!!))) }
        // Другие списки: «добавь в список в дорогу: зарядку, паспорт»
        Regex("""^(?:добавь|запиши|внеси)\s+в\s+список\s+(.+?)\s*:\s*(.+)$""")
            .find(n)?.let { m ->
                val name = sub(text, m.groups[1]!!).trim('«', '»', '"').replaceFirstChar { it.uppercase() }
                return AssistantAction.AddToList(name, items(sub(text, m.groups[2]!!)))
            }
        if (Regex("""^(?:что|чего)\s+(?:мне\s+|нам\s+)?(?:нужно\s+|надо\s+|ещё\s+|еще\s+)*купить$|^(?:покажи|прочитай|озвучь|открой|какой|скажи|назови)\s+(?:мне\s+)?(?:мой\s+|весь\s+)?(?:список\s+покупок|список\s+продуктов|покупки)$|^(?:список\s+покупок|мои\s+покупки)$|^что\s+(?:у\s+меня\s+)?в\s+(?:списке\s+покупок|покупках)$""").containsMatchIn(n)) {
            return AssistantAction.QueryList(list)
        }
        Regex("""^(?:покажи|прочитай|открой)\s+список\s+(.+)$""").find(n)?.let { m ->
            val name = sub(text, m.groups[1]!!)
            if (!Regex("""^(?:задач|дел|напомин|расход|трат|заметок|идей)""").containsMatchIn(RuTokenizer.normalize(name))) {
                return AssistantAction.QueryList(name.replaceFirstChar { it.uppercase() })
            }
        }
        // «вычеркни хлеб из покупок», «убери молоко из списка покупок»
        Regex("""^(?:вычеркни|отметь|убери|удали)\s+(.+?)\s+(?:из\s+(?:списка\s+покупок|покупок|списка\s+продуктов)|в\s+покупках(?:\s+как\s+купленн\p{L}*)?)$""")
            .find(n)?.let { return AssistantAction.CheckListItem(list, sub(text, it.groups[1]!!)) }
        if (Regex("""^(?:очисти|удали|сотри)\s+(?:весь\s+)?(?:список\s+покупок|покупки|список\s+продуктов)$""").containsMatchIn(n)) return AssistantAction.ClearList(list, onlyDone = false)
        if (Regex("""^(?:убери|удали|очисти|сотри)\s+(?:всё\s+|все\s+)?купленн\p{L}*(?:\s+из\s+(?:списка|покупок))?$""").containsMatchIn(n)) return AssistantAction.ClearList(list, onlyDone = true)
        return null
    }

    /** «Купила молоко и хлеб» без суммы — вычеркнуть из покупок (решает движок: только если такие пункты есть). */
    fun boughtItems(input: String): List<String>? {
        val n = RuTokenizer.normalize(input.trim().trimEnd('.', '!'))
        val m = Regex("""^(?:я\s+)?(?:уже\s+)?(?:купил|купила|купили|взял|взяла|взяли)\s+(.+)$""").find(n) ?: return null
        if (Regex("""\d""").containsMatchIn(n) || Regex("""(?:рубл|₽|доллар|евро|тысяч|сотн|за\s)""").containsMatchIn(n)) return null
        return items(m.groupValues[1]).takeIf { it.isNotEmpty() }
    }

    private fun items(raw: String): List<String> =
        raw.split(Regex(""",|;|\s+и\s+|\s+а\s+также\s+|\s+ещё\s+|\s+еще\s+"""))
            .map { it.trim().trim('.', '«', '»', '"').trim() }
            .map { it.replace(Regex("""^(?:ещё|еще|и)\s+""", RegexOption.IGNORE_CASE), "") }
            .filter { it.isNotEmpty() && it.length <= 80 }

    // --- Сценарии ---------------------------------------------------------------

    private fun routine(text: String, n: String): AssistantAction? {
        Regex("""^(?:когда|если)\s+я\s+(?:говорю|скажу|произношу|произнесу)\s+[«"]?(.+?)[»"]?\s*(?:,\s*то|,|—|-|:|\s+то)\s+(.+)$""").find(n)?.let { m ->
            val trigger = sub(text, m.groups[1]!!).trim('«', '»', '"', ' ', ',')
            return AssistantAction.CreateRoutine(trigger.replaceFirstChar { it.uppercase() }, commands(sub(text, m.groups[2]!!)))
        }
        Regex("""^(?:создай|сделай|добавь|запиши)\s+сценарий\s+[«"]?(.+?)[»"]?\s*[:—-]\s*(.+)$""").find(n)?.let { m ->
            return AssistantAction.CreateRoutine(sub(text, m.groups[1]!!).replaceFirstChar { it.uppercase() }, commands(sub(text, m.groups[2]!!)))
        }
        if (Regex("""^(?:какие|покажи|перечисли|мои)\s+(?:у\s+меня\s+|мои\s+)?(?:есть\s+)?сценари\p{L}*$""").containsMatchIn(n)) return AssistantAction.QueryRoutines
        Regex("""^(?:удали|убери|сотри)\s+сценарий\s+[«"]?(.+?)[»"]?$""").find(n)?.let { return AssistantAction.DeleteRoutine(sub(text, it.groups[1]!!)) }
        return null
    }

    private fun commands(raw: String): List<String> =
        raw.split(Regex("""\s*(?:;|,\s*(?:а\s+)?(?:потом|затем|и)?\s*|\s+и\s+(?:потом\s+|ещё\s+|еще\s+)?|\s+потом\s+|\s+затем\s+)\s*"""))
            .map { it.trim().trim('.', ',') }.filter { it.length >= 3 }

    // --- Дни рождения -------------------------------------------------------------

    private fun birthday(text: String, n: String, today: LocalDate): AssistantAction? {
        // Вопросы
        Regex("""^когда\s+(?:будет\s+)?(?:у\s+(.+?)\s+день\s+рождения|день\s+рождения\s+(?:у\s+)?(.+?))$""").find(n)?.let { m ->
            val g = m.groups[1] ?: m.groups[2]!!
            return AssistantAction.QueryBirthdays(sub(text, g))
        }
        if (Regex("""^(?:чьи|какие|у\s+кого)\s+(?:будут\s+|есть\s+)?(?:дни|день)\s+рождения\s+(?:будут\s+)?в\s+этом\s+месяце$|^(?:дни\s+рождения|у\s+кого\s+день\s+рождения)\s+в\s+этом\s+месяце$""").containsMatchIn(n)) {
            return AssistantAction.QueryBirthdays(null, thisMonth = true)
        }
        if (Regex("""^(?:покажи|какие|перечисли|все)\s+(?:мои\s+|все\s+)?дни\s+рождения$|^(?:дни\s+рождения|список\s+дней\s+рождения)$""").containsMatchIn(n)) {
            return AssistantAction.QueryBirthdays(null)
        }
        // «день рождения мамы 5 мая», «запомни, что у Саши день рождения 12 марта», «у папы др 3 июня»
        val m = Regex("""^(?:запомни|запиши|добавь|сохрани)?[,:]?\s*(?:что\s+)?(?:у\s+(.+?)\s+)?(?:день\s+рождения|др|днюха|днюшка)(?:\s+у)?\s+(.+)$""").find(n) ?: return null
        val rest = sub(text, m.groups[2]!!)
        val parsed = dates.parse(rest, today)
        val date = parsed.spec.date ?: return null
        if (parsed.spec.offset != null) return null
        val person = (m.groups[1]?.let { sub(text, it) } ?: parsed.remainder).trim().trim(',', '—', '-', ' ')
            .replace(Regex("""^(?:у|моей|моего|мой|моя)\s+""", RegexOption.IGNORE_CASE), "")
        if (person.isEmpty() || person.split(' ').size > 4) return null
        return AssistantAction.AddBirthday(person, date.monthValue, date.dayOfMonth)
    }

    // --- Секретные заметки ----------------------------------------------------------

    private fun secret(text: String, n: String): AssistantAction? {
        val m = Regex("""^(?:запиши|создай|сохрани|сделай|добавь)\s+(?:мне\s+)?(?:секретн\p{L}+|тайн\p{L}+|личн\p{L}+\s+секретн\p{L}+|скрыт\p{L}+)\s+(?:заметку|запись)[:,]?\s+(.+)$""").find(n) ?: return null
        val content = sub(text, m.groups[1]!!).replaceFirstChar { it.uppercase() }
        val title = content.split(Regex("""\s+""")).take(6).joinToString(" ").trimEnd(',', '.', ':')
        return AssistantAction.CreateSecretNote(title, content)
    }

    // --- Перевод ------------------------------------------------------------------

    fun translation(input: String): Translation? {
        val text = input.trim().trimEnd('.', '!', '?').trim()
        val n = RuTokenizer.normalize(text)
        val lang = LANGS.entries.firstOrNull { (k, _) -> Regex("""(?:на\s+$k\p{L}*|по-$k\p{L}*)""").containsMatchIn(n) } ?: return null
        val code = lang.value.first
        val body = Regex("""^(?:переведи|перевести|переведите|как\s+(?:будет|сказать|звучит|написать)|скажи)\s+(.+)$""").find(n)?.groups?.get(1)
            ?: Regex("""^как\s+(?:по-\S+)\s+(.+)$""").find(n)?.groups?.get(1)
            ?: return null
        val phrase = sub(text, body)
            .replace(Regex("""(?iu)(?:на\s+${lang.key}\p{L}*(?:\s+язык\p{L}*)?|по-${lang.key}\p{L}*)"""), "")
            .replace(Regex("""^\s*(?:фразу|слово|предложение|текст)\s+""", RegexOption.IGNORE_CASE), "")
            .trim().trim(',', ':', '«', '»', '"', ' ')
        if (phrase.isEmpty()) return null
        return Translation(code, lang.value.second, phrase)
    }

    private fun sub(original: String, g: MatchGroup): String = original.substring(g.range.first, minOf(g.range.last + 1, original.length)).trim()

    companion object {
        private val DICTATION_START = Regex("""^(?:давай\s+)?(?:я\s+)?(?:надиктую|продиктую|хочу\s+надиктовать|надиктовать)(?:\s+(?:тебе\s+)?(?:заметку|текст|запись))?$|^(?:запиши|записывай|прими)\s+(?:под\s+диктовку|длинную\s+заметку|голосовую\s+заметку)$|^(?:режим\s+)?диктовк[аи]$|^(?:начни|включи)\s+диктовку$|^(?:запиши|создай|новая)\s+(?:мне\s+)?заметку$""")
        private val DICTATION_END = Regex("""[\s,.]*(?:готово|всё|все|конец|закончил[а]?|закончили|стоп|хватит|сохрани|конец заметки)[.!]?$""", RegexOption.IGNORE_CASE)

        /** «Надиктую заметку», «запиши под диктовку», «запиши заметку» без текста. */
        fun isDictationStart(text: String): Boolean = DICTATION_START.matches(RuTokenizer.normalize(text.trim().trimEnd('.', '!', '?')))

        /** Слово завершения в конце куска диктовки. */
        fun endsDictation(piece: String): Boolean = DICTATION_END.containsMatchIn(piece.trim()) && piece.trim().split(Regex("""\s+""")).size <= 40

        fun cleanDictation(text: String): String {
            var t = text.trim()
            repeat(2) { t = t.replace(DICTATION_END, "").trim() }
            return t.trim(',', ' ')
        }

        /** Основа названия языка → (код, «английский»). */
        val LANGS = linkedMapOf(
            "английск" to ("en" to "английский"), "немецк" to ("de" to "немецкий"), "французск" to ("fr" to "французский"),
            "испанск" to ("es" to "испанский"), "итальянск" to ("it" to "итальянский"), "китайск" to ("zh" to "китайский"),
            "японск" to ("ja" to "японский"), "турецк" to ("tr" to "турецкий"), "корейск" to ("ko" to "корейский"),
            "португальск" to ("pt" to "португальский"), "польск" to ("pl" to "польский"), "украинск" to ("uk" to "украинский"),
            "арабск" to ("ar" to "арабский"), "грузинск" to ("ka" to "грузинский"), "казахск" to ("kk" to "казахский"),
            "русск" to ("ru" to "русский"),
        )

        /** Небольшой офлайн-словарь самых частых фраз (без AI). */
        val OFFLINE = mapOf(
            "en" to mapOf("привет" to "Hello", "спасибо" to "Thank you", "пожалуйста" to "Please", "до свидания" to "Goodbye",
                "как дела" to "How are you?", "извините" to "Excuse me", "да" to "Yes", "нет" to "No", "доброе утро" to "Good morning",
                "добрый вечер" to "Good evening", "спокойной ночи" to "Good night", "я тебя люблю" to "I love you",
                "сколько стоит" to "How much is it?", "где туалет" to "Where is the toilet?", "счёт пожалуйста" to "The bill, please",
                "счет пожалуйста" to "The bill, please", "помогите" to "Help!", "меня зовут" to "My name is", "я не понимаю" to "I don't understand"),
            "de" to mapOf("привет" to "Hallo", "спасибо" to "Danke", "пожалуйста" to "Bitte", "до свидания" to "Auf Wiedersehen",
                "как дела" to "Wie geht's?", "да" to "Ja", "нет" to "Nein", "доброе утро" to "Guten Morgen", "спокойной ночи" to "Gute Nacht",
                "я не понимаю" to "Ich verstehe nicht", "сколько стоит" to "Wie viel kostet das?"),
            "fr" to mapOf("привет" to "Salut", "спасибо" to "Merci", "пожалуйста" to "S'il vous plaît", "до свидания" to "Au revoir",
                "как дела" to "Comment ça va ?", "да" to "Oui", "нет" to "Non", "доброе утро" to "Bonjour", "спокойной ночи" to "Bonne nuit",
                "я тебя люблю" to "Je t'aime"),
            "es" to mapOf("привет" to "Hola", "спасибо" to "Gracias", "пожалуйста" to "Por favor", "до свидания" to "Adiós",
                "как дела" to "¿Cómo estás?", "да" to "Sí", "нет" to "No", "доброе утро" to "Buenos días", "спокойной ночи" to "Buenas noches",
                "я тебя люблю" to "Te quiero"),
            "it" to mapOf("привет" to "Ciao", "спасибо" to "Grazie", "пожалуйста" to "Per favore", "до свидания" to "Arrivederci",
                "да" to "Sì", "нет" to "No", "доброе утро" to "Buongiorno"),
            "tr" to mapOf("привет" to "Merhaba", "спасибо" to "Teşekkür ederim", "пожалуйста" to "Lütfen", "да" to "Evet", "нет" to "Hayır",
                "до свидания" to "Hoşça kal"),
        )
    }
}
