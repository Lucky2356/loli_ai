package ai.loli.core.assistant

import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.personal.ListTemplates

/**
 * 2.8: «где лежит», долги, свои списки. Точные команды — разбираются на устройстве раньше AI.
 */
object PersonalCommands {
    private fun rx(p: String) = Rx.of(p)

    fun parse(text: String, n: String): AssistantAction? =
        thing(text, n) ?: debt(n) ?: lists(text, n)

    // --- Где лежит ----------------------------------------------------------------

    private const val PREP = """(в|во|на|под|за|над|между|у|около|возле|рядом\s+с|внутри|среди)"""
    private const val PUT_VERB = """(положил|положила|положили|убрал|убрала|убрали|спрятал|спрятала|спрятали|оставил|оставила|оставили|сунул|сунула|повесил|повесила|повесили|поставил|поставила|переложил|переложила)"""
    private const val LIE_VERB = """(лежит|лежат|находится|находятся|хранится|хранятся|висит|висят|спрятан|спрятана|спрятаны)"""
    private val NOT_THING = rx("""^(?:что|где|кто|как|какой|какая|какие|сколько|это|оно|он|она|они|все|всё|я|ты|вы|мы|деньги на карт\p{L}*|будильник|таймер|напоминание|задачу|задача|встречу|встреча)$""")

    private fun thing(text: String, n: String): AssistantAction? {
        val body = n.replace(rx("""^(?:запомни|запиши)[,:]?\s+(?:что\s+|где\s+)?"""), "")
        val put = rx("""^(?:я\s+)?$PUT_VERB\s+(.+?)\s+$PREP\s+(.+)$""").find(body)
        val lie = rx("""^(.+?)\s+$LIE_VERB\s+$PREP\s+(.+)$""").find(body)
        val (item, place) = when {
            put != null -> {
                val verb = put.groupValues[1].let { if (it.endsWith("и")) it else it.removeSuffix("а") + "и" }
                put.groupValues[2] to "$verb ${put.groupValues[3]} ${put.groupValues[4]}"
            }
            lie != null -> lie.groupValues[1] to "${lie.groupValues[2]} ${lie.groupValues[3]} ${lie.groupValues[4]}"
            else -> return null
        }
        val cleanItem = item.trim().removePrefix("мой ").removePrefix("моя ").removePrefix("мои ").removePrefix("мою ").trim()
        if (cleanItem.isEmpty() || NOT_THING.matches(cleanItem) || cleanItem.split(' ').size > 4 || place.length > 80) return null
        // «Положила 500 рублей на карту» — это деньги, а не вещь; «поставила будильник на 7» — не вещь.
        if (Regex("""\d""").containsMatchIn(cleanItem) || rx("""\b(?:будильник|таймер|напоминани|задач|встреч)""").containsMatchIn(cleanItem)) return null
        return AssistantAction.PutThing(original(text, cleanItem), original(text, place.trim()))
    }

    /** Вопрос «где паспорт?», «куда я положила ключи». [strong] — точно о вещи (есть «я положила», «мой»…). */
    data class WhereAsk(val item: String, val strong: Boolean)

    fun where(input: String): WhereAsk? {
        val n = RuTokenizer.normalize(input.trim().trimEnd('?', '.', '!')).trim()
        val m = rx("""^(?:а\s+)?(?:где|куда)\s+(?:же\s+|теперь\s+|опять\s+)?(?:(я\s+)?(?:$PUT_VERB|дел|дела|девал|девала|подевал\p{L}*|задевал\p{L}*)\s+|(?:у меня\s+)?$LIE_VERB\s+)?(?:(мой|моя|моё|мое|мои|мою|наш|наша|наши|нашу)\s+)?(.+)$""").find(n) ?: return null
        val item = m.groupValues.last().trim()
        if (item.isEmpty() || item.split(' ').size > 4 || NOT_THING.matches(item)) return null
        if (rx("""^(?:ты|вы|я|мы|находится|находишься|живу|работаю|учусь|живет|живёт|ближайш\p{L}*|можно|купить|найти)\b""").containsMatchIn(item)) return null
        // «Где находится Казань» — не о вещи: слабый вопрос, отвечаем, только если такая вещь записана.
        val strong = m.groups[1] != null || m.groups[2] != null || m.groups[4] != null ||
            rx("""\b(?:лежит|лежат|хранится|хранятся|спрятан\p{L}*|дел|дела|девал\p{L}*|подевал\p{L}*|задевал\p{L}*)\b""").containsMatchIn(n)
        return WhereAsk(item, strong)
    }

    // --- Долги ----------------------------------------------------------------------

    private const val AMOUNT = """(\d+(?:\.\d+)?)(?:\s*(тыс\p{L}*|к))?(?:\s*(?:руб\p{L}*|₽|р))?"""
    private const val NAME = """([а-яё-]+(?:\s+[а-яё-]+)?)"""
    private const val REST = """(?:\s+в\s+долг)?(?:\s+до\s+[^,]+)?(?:[,.]?\s*(?:и\s+)?напомни(?:\s+мне)?\s+(.+))?$"""
    private val PRONOUN = setOf("я", "ты", "вы", "мы", "он", "она", "они", "мне", "тебе", "нам", "вам", "это", "кто", "сколько")

    private fun amount(num: String, mult: String?): Double? {
        val v = num.toDoubleOrNull() ?: return null
        return (if (mult != null) v * 1000 else v).takeIf { it > 0 && it < 1e9 }
    }

    private fun name(raw: String): String? {
        val w = raw.trim()
        if (w.isEmpty() || w.split(' ').any { it in PRONOUN } || w.endsWith("ть") || w.endsWith("ти") || w.endsWith("ся")) return null
        return w.split(' ').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
    }

    private fun debt(n: String): AssistantAction? {
        if (!rx("""долг|должен|должна|должны|задолжал|одолжил|занял|заняла|вернул|вернула|отдал|отдала|дал\p{L}*\s|взял\p{L}*\s""").containsMatchIn(n)) return null
        val d = DevicePhrases.digitize(n).replace(rx("""^(?:запомни|запиши)[,:]?\s+(?:что\s+)?"""), "")
        // Вопросы
        if (rx("""^(?:а\s+)?(?:кто\s+(?:мне\s+)?(?:ещё\s+|еще\s+)?должен(?:\s+мне)?|кому\s+я\s+(?:ещё\s+|еще\s+)?(?:должен|должна)|(?:мои|все|покажи|список)\s+(?:мои\s+)?долг\p{L}*|долги|какие\s+у\s+меня\s+долги|у\s+кого\s+я\s+занимал\p{L}*)$""").containsMatchIn(d)) {
            return AssistantAction.QueryDebts(null)
        }
        rx("""^сколько\s+(?:мне\s+)?(?:ещё\s+|еще\s+)?(?:должен|должна|должны)\s+(?:мне\s+)?$NAME$""").find(d)?.let { m -> name(m.groupValues[1])?.let { return AssistantAction.QueryDebts(it) } }
        rx("""^сколько\s+$NAME\s+(?:мне\s+)?(?:ещё\s+|еще\s+)?(?:должен|должна|должны)(?:\s+мне)?$""").find(d)?.let { m -> name(m.groupValues[1])?.let { return AssistantAction.QueryDebts(it) } }
        rx("""^сколько\s+я\s+(?:ещё\s+|еще\s+)?(?:должен|должна)\s+$NAME$""").find(d)?.let { m -> name(m.groupValues[1])?.let { return AssistantAction.QueryDebts(it) } }
        // «Я должна Саше 500», «я задолжал Пете 2 тысячи»
        rx("""^я\s+(?:должен|должна|задолжал|задолжала)\s+$NAME\s+$AMOUNT$REST""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            val sum = amount(m.groupValues[2], m.groups[3]?.value) ?: return null
            return AssistantAction.AddDebt(who, sum, theyOwe = false, remindText = m.groups[4]?.value)
        }
        // «Саша должен мне 500», «Петя задолжал мне 2 тысячи»
        rx("""^$NAME\s+(?:мне\s+)?(?:должен|должна|должны|задолжал|задолжала|задолжали)\s+(?:мне\s+)?$AMOUNT$REST""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            val sum = amount(m.groupValues[2], m.groups[3]?.value) ?: return null
            return AssistantAction.AddDebt(who, sum, theyOwe = true, remindText = m.groups[4]?.value)
        }
        // «Я заняла у Саши 500», «взял в долг у Пети 1000»
        rx("""^(?:я\s+)?(?:занял|заняла|одолжил|одолжила|взял|взяла|позаимствовал\p{L}*)\s+(?:в\s+долг\s+)?у\s+$NAME\s+$AMOUNT$REST""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            val sum = amount(m.groupValues[2], m.groups[3]?.value) ?: return null
            return AssistantAction.AddDebt(who, sum, theyOwe = false, remindText = m.groups[4]?.value)
        }
        // «Одолжила Саше 500», «дала в долг Пете 1000», «дала Пете 1000 в долг»
        rx("""^(?:я\s+)?(?:одолжил|одолжила|занял|заняла|дал\s+в\s+долг|дала\s+в\s+долг)\s+$NAME\s+$AMOUNT$REST""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            val sum = amount(m.groupValues[2], m.groups[3]?.value) ?: return null
            return AssistantAction.AddDebt(who, sum, theyOwe = true, remindText = m.groups[4]?.value)
        }
        rx("""^(?:я\s+)?(?:дал|дала)\s+$NAME\s+$AMOUNT\s+в\s+долг(?:[,.]?\s*(?:и\s+)?напомни(?:\s+мне)?\s+(.+))?$""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            val sum = amount(m.groupValues[2], m.groups[3]?.value) ?: return null
            return AssistantAction.AddDebt(who, sum, theyOwe = true, remindText = m.groups[4]?.value)
        }
        // Возвраты: «я вернула Саше 200», «я отдал долг Пете»
        rx("""^(?:я\s+)?(?:вернул|вернула|отдал|отдала)\s+(?:долг\s+)?$NAME(?:\s+долг)?(?:\s+$AMOUNT)?$""").find(d)?.let { m ->
            if (!d.startsWith("я ") && !d.contains("долг") && m.groups[2] == null) return@let
            if (!d.contains("долг") && !rx("""^(?:я\s+)?вернул""").containsMatchIn(d)) return@let
            val who = name(m.groupValues[1].removeSuffix(" долг")) ?: return@let
            return AssistantAction.SettleDebt(who, m.groups[2]?.value?.let { amount(it, m.groups[3]?.value) }, theyPaid = false)
        }
        // «Саша вернул 200», «Петя отдал долг», «Саша вернул мне деньги»
        rx("""^$NAME\s+(?:вернул|вернула|вернули|отдал|отдала|отдали)\s+(?:мне\s+)?(?:весь\s+)?(?:долг|деньги|$AMOUNT)(?:\s+(?:мне|долга|из долга))?$""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            return AssistantAction.SettleDebt(who, m.groups[2]?.value?.let { amount(it, m.groups[3]?.value) }, theyPaid = true)
        }
        // «Саша больше не должен», «прости долг Пете»
        rx("""^$NAME\s+(?:мне\s+)?больше\s+(?:мне\s+)?не\s+(?:должен|должна|должны)$""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            return AssistantAction.SettleDebt(who, null, theyPaid = true)
        }
        rx("""^(?:прости|спиши|закрой|удали)\s+долг\s+$NAME$""").find(d)?.let { m ->
            val who = name(m.groupValues[1]) ?: return null
            return AssistantAction.SettleDebt(who, null, theyPaid = null)
        }
        return null
    }

    // --- Свои списки -------------------------------------------------------------------

    /** Названия, которые уже заняты другими разделами: «список дел», «список покупок». */
    private val RESERVED = rx("""^(?:дел|задач\p{L}*|покуп\p{L}*|продукт\p{L}*|напомин\p{L}*|расход\p{L}*|трат|заметок|заметк\p{L}*|иде\p{L}*|дн\p{L}*\s+рожд\p{L}*|рожд\p{L}*|будильник\p{L}*|сценари\p{L}*|подпис\p{L}*|долг\p{L}*|мест|контакт\p{L}*|сообщени\p{L}*|событи\p{L}*|встреч\p{L}*|таймер\p{L}*|памят\p{L}*)$""")

    /** «фильмов» → «Фильмы», «подарков» → «Подарки», «желаний» → «Желания», «в дорогу» → «В дорогу». */
    fun listName(raw: String): String {
        val w = raw.trim().trim('«', '»', '"', ':', ',', ' ')
        val one = w.lowercase()
        val named = if (' ' in one) w else when {
            Regex("""[а-яё]+[кгхжшчщ]ов""").matches(one) -> w.dropLast(2) + "и"
            Regex("""[а-яё]+ов""").matches(one) && one.length > 4 -> w.dropLast(2) + "ы"
            Regex("""[а-яё]+ев""").matches(one) && one.length > 4 -> w.dropLast(2) + "и"
            Regex("""[а-яё]+ий""").matches(one) && one.length > 4 -> w.dropLast(2) + "ия"
            Regex("""[а-яё]+ей""").matches(one) && one.length > 4 -> w.dropLast(2) + "и"
            else -> w
        }
        return named.replaceFirstChar { it.uppercase() }
    }

    private fun reserved(name: String) = RESERVED.matches(RuTokenizer.normalize(name).trim())

    private fun lists(text: String, n: String): AssistantAction? {
        // Готовые чек-листы: «собери список в отпуск», «что взять в поход»
        rx("""^(?:собери|составь|сделай|создай|подготовь|дай|нужен)\s+(?:мне\s+)?(?:список|чек-?лист|чеклист)\s+(?:вещей\s+|что\s+взять\s+)?(?:в|на|для|к)\s+(.+)$""").find(n)?.let { m ->
            ListTemplates.find(m.groupValues[1])?.let { return AssistantAction.CreateList(it.name, it.items) }
        }
        rx("""^что\s+(?:мне\s+|нам\s+)?(?:нужно\s+|надо\s+)?(?:взять|брать|собрать|положить)\s+(?:с\s+собой\s+)?(?:в|на)\s+(.+)$""").find(n)?.let { m ->
            ListTemplates.find(m.groupValues[1])?.let { return AssistantAction.CreateList(it.name, it.items) }
        }
        // «Создай список фильмов» — потом спросим, что в него добавить.
        rx("""^(?:создай|заведи|сделай|начни)\s+(?:новый\s+)?список\s+(.+)$""").find(n)?.let { m ->
            val raw = original(text, m.groupValues[1])
            if (reserved(raw) || raw.contains(':')) return null
            return AssistantAction.CreateList(listName(raw), emptyList())
        }
        if (rx("""^(?:какие|покажи|перечисли)\s+(?:у\s+меня\s+)?(?:есть\s+)?(?:все\s+)?(?:мои\s+)?списки$|^(?:мои|все)\s+списки$""").containsMatchIn(n)) return AssistantAction.QueryLists
        // «Добавь в список фильмов Интерстеллар»
        rx("""^(?:добавь|допиши|запиши|внеси)\s+в\s+список\s+([а-яё-]{3,})\s+(.+)$""").find(n)?.let { m ->
            val raw = original(text, m.groupValues[1])
            if (reserved(raw)) return null
            return AssistantAction.AddToList(listName(raw), SpecialCommands.splitItems(original(text, m.groupValues[2])))
        }
        // «Добавь Интерстеллар в список фильмов»
        rx("""^(?:добавь|допиши|запиши|внеси)\s+(.+?)\s+в\s+список\s+(.+)$""").find(n)?.let { m ->
            val raw = original(text, m.groupValues[2])
            if (reserved(raw)) return null
            return AssistantAction.AddToList(listName(raw), SpecialCommands.splitItems(original(text, m.groupValues[1])))
        }
        // «Вычеркни Интерстеллар из списка фильмов», «отметь паспорт в списке в поездку»
        rx("""^(?:вычеркни|отметь|убери|удали)\s+(.+?)\s+(?:из\s+списка|в\s+списке)\s+(.+)$""").find(n)?.let { m ->
            val raw = original(text, m.groupValues[2])
            if (reserved(raw)) return null
            return AssistantAction.CheckListItem(listName(raw), original(text, m.groupValues[1]))
        }
        rx("""^(?:удали|очисти|сотри)\s+(?:весь\s+)?список\s+(.+)$""").find(n)?.let { m ->
            val raw = original(text, m.groupValues[1])
            if (reserved(raw)) return null
            return AssistantAction.ClearList(listName(raw), onlyDone = false)
        }
        rx("""^(?:что|чего)\s+(?:у\s+меня\s+)?(?:есть\s+)?в\s+списке\s+(.+)$""").find(n)?.let { m ->
            val raw = original(text, m.groupValues[1])
            if (reserved(raw)) return null
            return AssistantAction.QueryList(listName(raw))
        }
        return null
    }

    /** Кусок нормализованной строки — в исходном написании (регистр, «ё»), если его удаётся найти. */
    private fun original(text: String, part: String): String {
        val i = RuTokenizer.normalize(text).indexOf(part)
        return if (i >= 0 && i + part.length <= text.length) text.substring(i, i + part.length) else part
    }
}
