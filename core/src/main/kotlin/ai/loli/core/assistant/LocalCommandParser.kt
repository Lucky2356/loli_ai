package ai.loli.core.assistant

import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.nlp.Tok
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Все шаблоны этого файла компилируются с Unicode-границами слов (см. [Rx]). */
private fun Regex(pattern: String): kotlin.text.Regex = Rx.of(pattern)
private fun Regex(pattern: String, option: RegexOption): kotlin.text.Regex = kotlin.text.Regex(Rx.unicode(pattern), option)

/**
 * Офлайн-распознавание типовых команд без AI (нет интернета, не настроен ключ, AI недоступен).
 * Возвращает те же структурированные действия, что и AI, — они проходят тот же исполнитель.
 * Понимает только распространённые формулировки; всё остальное честно отдаёт null.
 */
class LocalCommandParser(private val dates: RuDateTimeParser = RuDateTimeParser()) {

    fun parse(input: String, now: Instant, zone: ZoneId): AssistantPlan? {
        val original = cleanup(input)
        if (original.isEmpty()) return null
        val n = RuTokenizer.normalize(original)
        val today = now.atZone(zone).toLocalDate()

        smallTalk(n)?.let { return AssistantPlan(it, emptyList()) }
        parseExpenseQuery(n)?.let { return plan(it) }
        parseReminder(original, n, now, zone)?.let { return it }
        parseTaskQuery(n)?.let { return plan(it) }
        parseCompleteTask(original, n)?.let { return plan(it) }
        parseDelete(original, n)?.let { return plan(it) }
        parseTaskCreate(original, n, today)?.let { return plan(it) }
        parseMemory(original, n)?.let { return plan(it) }
        parseAppend(original, n)?.let { return plan(it) }
        parseIdea(original, n)?.let { return plan(it) }
        parseExpense(original, n, today)?.let { return plan(it) }
        parseNote(original, n)?.let { return plan(it) }
        parseSearch(original, n)?.let { return plan(it) }
        if (Regex("""(какие|покажи|мои|список)\s.*напоминани""").containsMatchIn(n)) return plan(AssistantAction.QueryReminders)
        return null
    }

    private fun plan(a: AssistantAction) = AssistantPlan("", listOf(a))

    private fun cleanup(s: String): String = s.trim()
        .replace(Regex("""^(?:пожалуйста|слушай|ну|а|так|давай)[,\s]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[,\s]+пожалуйста[.!]?$""", RegexOption.IGNORE_CASE), "")
        .trim().trimEnd('.', '!')

    private fun smallTalk(n: String): String? = when {
        Regex("""^(привет|здравствуй|здравствуйте|доброе утро|добрый день|добрый вечер)\b""").containsMatchIn(n) -> "Привет! Я на связи."
        Regex("""^(спасибо|благодарю)\b""").containsMatchIn(n) -> "Всегда пожалуйста!"
        Regex("""^(как дела|ты тут|ты здесь)""").containsMatchIn(n) -> "Я здесь и готова помочь."
        else -> null
    }

    // --- Расходы: запросы ---

    private fun parseExpenseQuery(n: String): AssistantAction? {
        val mode = when {
            Regex("""(самые|самый)\s+(большие|крупные|дорогие|большой|крупный)""").containsMatchIn(n) -> ReportMode.TOP
            Regex("""^(покажи|выведи|список)\s+(мои\s+|все\s+)?(расход|трат)""").containsMatchIn(n) -> ReportMode.LIST
            Regex("""(по категориям|на что.*(трат|потрат|уход))""").containsMatchIn(n) -> ReportMode.BY_CATEGORY
            Regex("""сколько.*(потрат|трат|расход|ушло|израсход)""").containsMatchIn(n) -> ReportMode.TOTAL
            Regex("""(какие|мои)\s+(были\s+)?(расходы|траты)""").containsMatchIn(n) -> ReportMode.LIST
            else -> return null
        }
        val category = Regex("""(?:потратил\w*|трат\w*|расход\w*|ушло)\s+на\s+([а-я]+)""").find(n)?.groupValues?.get(1)
            ?.takeIf { it !in setOf("этой", "прошлой", "этот", "прошлый", "все", "что") }
            ?.let { word -> ExpenseCategories.categorize(word).takeIf { it != ExpenseCategories.OTHER } ?: word }
        return AssistantAction.QueryExpenses(period(n), null, null, category, mode)
    }

    private fun period(n: String): PeriodPreset = when {
        Regex("""\bсегодня""").containsMatchIn(n) -> PeriodPreset.TODAY
        Regex("""\bвчера""").containsMatchIn(n) -> PeriodPreset.YESTERDAY
        Regex("""(на этой|за эту|на текущей)\s+недел""").containsMatchIn(n) -> PeriodPreset.THIS_WEEK
        Regex("""(последн\w+|прошл\w+)\s+недел|за недел|за 7 дней|за семь дней""").containsMatchIn(n) -> PeriodPreset.LAST_7_DAYS
        Regex("""(прошлом|прошлый)\s+месяц""").containsMatchIn(n) -> PeriodPreset.LAST_MONTH
        Regex("""30 дней|тридцать дней""").containsMatchIn(n) -> PeriodPreset.LAST_30_DAYS
        Regex("""(этом|этот|текущем)\s+год|за год""").containsMatchIn(n) -> PeriodPreset.THIS_YEAR
        Regex("""за вс[её] время|вообще""").containsMatchIn(n) -> PeriodPreset.ALL
        else -> PeriodPreset.THIS_MONTH
    }

    // --- Расходы: запись ---

    private val expenseVerb = Regex("""\b(потратил\w*|потрачено|заплатил\w*|оплатил\w*|купил\w*|отдал\w*|расход|трата|потратила)\b""")

    private fun parseExpense(original: String, n: String, today: LocalDate): AssistantAction? {
        if (!expenseVerb.containsMatchIn(n)) return null
        val tokens = RuTokenizer.tokenize(original)
        val amountIdx = pickAmount(tokens) ?: return null
        val amount = tokens[amountIdx].number ?: return null
        if (amount <= 0) return null
        val currency = tokens.getOrNull(amountIdx + 1)?.norm?.let { Money.currencyWords[it] } ?: "RUB"
        val whenResult = dates.parse(original, today)
        val date = whenResult.spec.date ?: today
        // Описание: предпочтительно то, что после «на»/«за» (на продукты, за кофе), иначе остаток фразы.
        val skip = setOf(amountIdx, amountIdx + 1).filter { it < tokens.size && (it == amountIdx || Money.currencyWords.containsKey(tokens[it].norm)) }
        val rest = tokens.filterIndexed { i, t -> i !in skip && !expenseVerb.matches(t.norm) && t.norm !in FILLER && !isDateWord(t.norm) }
        val afterNa = tokens.indexOfFirst { it.norm == "на" && tokens.indexOf(it) > amountIdx }
        val descriptionTokens = if (afterNa >= 0) tokens.drop(afterNa + 1).filter { it.norm !in FILLER && !isDateWord(it.norm) && !it.isNumber }
        else rest.filter { !it.isNumber && it.norm != "на" && it.norm != "за" }
        val description = descriptionTokens.joinToString(" ") { it.text }.trim()
        val category = ExpenseCategories.categorize(description.ifEmpty { original })
        return AssistantAction.CreateExpense(Money.toMinor(amount), currency, category, description, date)
    }

    private fun pickAmount(tokens: List<Tok>): Int? {
        tokens.forEachIndexed { i, t -> if (t.isNumber && Money.currencyWords.containsKey(tokens.getOrNull(i + 1)?.norm)) return i }
        tokens.forEachIndexed { i, t ->
            val next = tokens.getOrNull(i + 1)?.norm
            val prev = tokens.getOrNull(i - 1)?.norm
            if (t.isNumber && next !in TIME_WORDS && prev != "в" && prev != "во") return i
        }
        return null
    }

    private fun isDateWord(w: String) = w in setOf("сегодня", "вчера", "позавчера", "завтра")

    // --- Напоминания ---

    private fun parseReminder(original: String, n: String, now: Instant, zone: ZoneId): AssistantPlan? {
        val m = Regex("""^(напомни(те)?|напоминай(те)?|напомнить|поставь напоминание|создай напоминание|напоминание)\b[,:]?\s*(мне|нам)?\s*""").find(n)
        val body = if (m != null) {
            original.substring(m.range.last + 1)
        } else {
            // «Каждый понедельник в 9 утра напоминай проверить почту» — глагол в середине фразы.
            val mid = Regex("""\s(напомни(те)?|напоминай(те)?)(\s+(мне|нам))?\s""").find(n) ?: return null
            original.substring(0, mid.range.first) + " " + original.substring(mid.range.last + 1)
        }
        val today = now.atZone(zone).toLocalDate()
        val parsed = dates.parse(body, today)
        val text = parsed.remainder
            .replace(Regex("""^(что|о том,? что|о том|про|о|об|чтобы|,)\s+""", RegexOption.IGNORE_CASE), "")
            .trim().trim(',', '.').trim()
        if (text.isEmpty()) return AssistantPlan("", listOf(AssistantAction.Clarify("О чём напомнить?")))
        if (parsed.spec.isEmpty) {
            return AssistantPlan("", listOf(AssistantAction.Clarify("Когда напомнить ${RuFormat.quote(text)}? Скажите, например: «напомни завтра в 10 утра $text».")))
        }
        val trigger = dates.resolveTrigger(parsed.spec, now, zone)
            ?: return AssistantPlan("", listOf(AssistantAction.Clarify("Не поняла время. Когда напомнить?")))
        if (!trigger.isAfter(now.minusSeconds(60))) {
            return AssistantPlan("", listOf(AssistantAction.Clarify("Это время уже прошло. Когда напомнить ${RuFormat.quote(text)}?")))
        }
        return AssistantPlan("", listOf(AssistantAction.CreateReminder(text.replaceFirstChar { it.uppercase() }, trigger, dates.effectiveRecurrence(parsed.spec))))
    }

    // --- Задачи ---

    private fun parseTaskQuery(n: String): AssistantAction? {
        if (!Regex("""(покажи|какие|мои|список|что у меня|что по)\s.*задач|^задачи\b""").containsMatchIn(n)) return null
        val filter = when {
            n.contains("просроч") -> TaskFilter.OVERDUE
            n.contains("сегодня") -> TaskFilter.TODAY
            n.contains("завтра") -> TaskFilter.TOMORROW
            n.contains("недел") -> TaskFilter.WEEK
            Regex("""выполненн|сделанн|завершенн""").containsMatchIn(n) -> TaskFilter.COMPLETED
            Regex("""\bвсе\b""").containsMatchIn(n) -> TaskFilter.ALL
            else -> TaskFilter.ACTIVE
        }
        return AssistantAction.QueryTasks(filter)
    }

    private fun parseCompleteTask(original: String, n: String): AssistantAction? {
        val patterns = listOf(
            Regex("""^(?:отметь|пометь|отметить)\s+(?:задачу\s+)?(.+?)\s+(?:как\s+)?(?:выполненн\w*|сделанн\w*|готов\w*|завершенн\w*)$"""),
            Regex("""^(?:задача\s+)(.+?)\s+(?:выполнена|сделана|готова|завершена)$"""),
            Regex("""^(?:я\s+)?(?:сделал\w*|выполнил\w*|закончил\w*)\s+(?:задачу\s+)(.+)$"""),
        )
        for (p in patterns) {
            val m = p.find(n) ?: continue
            val g = m.groups[1]!!
            val query = original.substring(g.range.first, g.range.last + 1).trim('«', '»', '"', ' ')
            return AssistantAction.CompleteTask(TargetRef(null, query, setOf(RecordType.TASK)))
        }
        return null
    }

    private fun parseTaskCreate(original: String, n: String, today: LocalDate): AssistantAction? {
        val m = Regex("""^(?:(?:добавь|создай|запиши|поставь|заведи)\s+)?(?:мне\s+)?(?:(?:новую\s+)?задачу|в\s+задачи|в\s+список\s+задач|новая\s+задача|задача)[:,]?\s+(.+)$""").find(n) ?: return null
        val g = m.groups[1]!!
        val raw = original.substring(g.range.first, g.range.last + 1)
        val parsed = dates.parse(raw, today)
        val title = parsed.remainder.trim('«', '»', '"', ' ', ',').ifEmpty { return null }
        return AssistantAction.CreateTask(title.replaceFirstChar { it.uppercase() }, "", parsed.spec.date ?: parsed.spec.time?.let { today }, parsed.spec.time)
    }

    // --- Удаление (с подтверждением) ---

    private fun parseDelete(original: String, n: String): AssistantAction? {
        val m = Regex("""^(?:удали|удалить|сотри)\s+(заметку|идею|задачу|напоминание)\s+(?:про\s+|о\s+)?(.+)$""").find(n)
            ?: Regex("""^(?:отмени|отменить)\s+(напоминание)\s+(?:про\s+|о\s+)?(.+)$""").find(n) ?: return null
        val g = m.groups[2]!!
        val query = original.substring(g.range.first, g.range.last + 1).trim('«', '»', '"', ' ')
        return when (m.groupValues[1]) {
            "заметку" -> AssistantAction.DeleteNote(TargetRef(null, query, setOf(RecordType.NOTE)))
            "идею" -> AssistantAction.DeleteNote(TargetRef(null, query, setOf(RecordType.IDEA)))
            "задачу" -> AssistantAction.DeleteTask(TargetRef(null, query, setOf(RecordType.TASK)))
            else -> AssistantAction.CancelReminder(TargetRef(null, query, setOf(RecordType.REMINDER)))
        }
    }

    // --- Память ---

    private fun parseMemory(original: String, n: String): AssistantAction? {
        if (Regex("""что ты (обо мне |про меня )?(знаешь|помнишь)""").containsMatchIn(n)) return AssistantAction.QueryMemories(null)
        val m = Regex("""^запомни(те)?[,:]?\s+(?:что\s+|,\s*что\s+)?(.+)$""").find(n) ?: return null
        val g = m.groups[2]!!
        val content = original.substring(g.range.first, g.range.last + 1).trim()
        if (content.isEmpty()) return null
        return AssistantAction.Remember(content.replaceFirstChar { it.uppercase() }, "other")
    }

    // --- Заметки и идеи ---

    private fun parseAppend(original: String, n: String): AssistantAction? {
        Regex("""^(?:добавь|допиши|дополни)\s+(?:к|в|ко)\s+(?:моей\s+|мою\s+|моим\s+)?(идее|идею|заметке|заметку|идеям|заметкам)\s+(?:про\s+|о\s+|с\s+|об\s+)?(.+)$""").find(n)?.let { m ->
            val g = m.groups[2]!!
            val types = if (m.groupValues[1].startsWith("иде")) setOf(RecordType.IDEA) else setOf(RecordType.NOTE)
            val rest = original.substring(g.range.first, g.range.last + 1).trim()
            val kind = if (types.contains(RecordType.IDEA)) NoteKind.IDEA else NoteKind.NOTE
            // «: » или « — » явно разделяют запрос и содержимое.
            val split = Regex("""\s*(?::|—|-)\s+""").split(rest, limit = 2)
            return if (split.size == 2) AssistantAction.AppendNote(TargetRef(null, split[0], types), split[1], kindIfNew = kind)
            else AssistantAction.AppendNote(TargetRef(null, null, types), rest, kindIfNew = kind, splitQueryFromContent = true)
        }
        Regex("""^(?:(?:добавь|допиши|запиши)\s+(?:туда|сюда|ещ[её]|к\s+ней|к\s+нему|в\s+неё|в\s+нее)|и\s+ещ[её]|ещ[её]\s+добавь)[,:]?\s+(?:ещ[её]\s+)?(.+)$""").find(n)?.let { m ->
            val g = m.groups[1]!!
            val content = original.substring(g.range.first, g.range.last + 1).trim()
            return AssistantAction.AppendNote(TargetRef(null, null, setOf(RecordType.NOTE, RecordType.IDEA)), content)
        }
        return null
    }

    private fun parseIdea(original: String, n: String): AssistantAction? {
        val m = Regex("""^(?:у меня\s+)?(?:появилась\s+|есть\s+|новая\s+)?идея[:,]?\s+(.+)$""").find(n)
            ?: Regex("""^(?:запиши|сохрани|добавь|создай)\s+(?:новую\s+)?идею[:,]?\s+(.+)$""").find(n) ?: return null
        val g = m.groups[1]!!
        val text = original.substring(g.range.first, g.range.last + 1).trim().trim('«', '»', '"')
        if (text.isEmpty()) return null
        return AssistantAction.CreateNote(NoteKind.IDEA, text.replaceFirstChar { it.uppercase() }.take(120), if (text.length > 120) text else "")
    }

    private fun parseNote(original: String, n: String): AssistantAction? {
        Regex("""^(?:создай|сделай|заведи|новая|открой)\s+(?:новую\s+)?заметк\w*[:,]?\s+(.+)$""").find(n)?.let { m ->
            val g = m.groups[1]!!
            val title = original.substring(g.range.first, g.range.last + 1).trim().trim('«', '»', '"', '“', '”')
            return AssistantAction.CreateNote(NoteKind.NOTE, title.replaceFirstChar { it.uppercase() }, "")
        }
        Regex("""^(?:запиши|заметка|сохрани|запомни в заметках)[:,]?\s+(?:что\s+|,\s*что\s+)?(.+)$""").find(n)?.let { m ->
            val g = m.groups[1]!!
            val text = original.substring(g.range.first, g.range.last + 1).trim()
            if (text.isEmpty()) return null
            val title = text.split(Regex("\\s+")).take(7).joinToString(" ").trimEnd(',', '.')
            return AssistantAction.CreateNote(NoteKind.NOTE, title.replaceFirstChar { it.uppercase() }, text.replaceFirstChar { it.uppercase() })
        }
        return null
    }

    private fun parseSearch(original: String, n: String): AssistantAction? {
        val m = Regex("""^(?:найди|поищи|покажи|найти|где)\s+(?:мне\s+)?(?:вс[её],?\s+что\s+я\s+\w+\s+)?(?:мо[юиея]\s+|мой\s+)?(?:(заметк\w*|иде\w*|задач\w*|напоминани\w*)\s+)?(?:про\s+|о\s+|об\s+|по\s+|на тему\s+|с\s+)?(.+)$""").find(n)
            ?: Regex("""^что я (?:записывал\w*|писал\w*|сохранял\w*)\s+(?:про|о|об)\s+(.+)$""").find(n)
            ?: return null
        var typeWord = if (m.groups.size > 2) m.groups[1]?.value else null
        val g = m.groups[m.groups.size - 1]!!
        var query = original.substring(g.range.first, g.range.last + 1).trim().trim('«', '»', '"')
        if (query.isEmpty()) return null
        // «найди заметку про отпуск» — тип + тема; «найди мои идеи для дня рождения» — слово «идеи» часть названия.
        val typeGroup = m.groups.takeIf { it.size > 2 }?.get(1)
        if (typeGroup != null && !Regex("""^(про|о|об|по|на тему|с)\s""").containsMatchIn(n.substring(typeGroup.range.last + 1).trimStart())) {
            query = original.substring(typeGroup.range.first, g.range.last + 1).trim()
            typeWord = null
        }
        val types = when {
            typeWord == null -> null
            typeWord.startsWith("заметк") -> setOf(RecordType.NOTE)
            typeWord.startsWith("иде") -> setOf(RecordType.IDEA)
            typeWord.startsWith("задач") -> setOf(RecordType.TASK)
            else -> setOf(RecordType.REMINDER)
        }
        return AssistantAction.Search(query, emptyList(), types)
    }

    companion object {
        private val TIME_WORDS = setOf("утра", "вечера", "дня", "ночи", "часов", "часа", "час", "минут", "минуты", "минуту")
        private val FILLER = setOf("я", "сегодня", "вчера", "позавчера", "потратила", "потратил", "рублей", "руб", "р", "за", "на", "и", "мне", "уже", "вот")

        /** Короткие ответы «да/нет» на запрос подтверждения. */
        fun isYes(text: String): Boolean {
            val n = RuTokenizer.normalize(text).trim().trim('.', '!', ',')
            return Regex("""^(да|ага|угу|конечно|подтверждаю|давай|верно|удаляй|удали|отменяй|отмени|ок|окей|yes|согласна|согласен|точно|да,? удали\w*)(\s|$|[,.!])""").containsMatchIn(n) &&
                !n.startsWith("да нет")
        }

        fun isNo(text: String): Boolean {
            val n = RuTokenizer.normalize(text).trim().trim('.', '!', ',')
            return Regex("""^(нет|не надо|не нужно|не удаляй|отмена|стоп|no|передумал\w*|не стоит|да нет)(\s|$|[,.!])""").containsMatchIn(n)
        }

        /** Выход из диалогового режима. */
        fun isDialogEnd(text: String): Boolean {
            val n = RuTokenizer.normalize(text).trim().trim('.', '!', ',')
            return Regex("""^(хватит|стоп|все,? спасибо|всё,? спасибо|закончим|пока|на этом вс[её]|достаточно|спасибо,? вс[её])$""").containsMatchIn(n)
        }

        /** Выбор варианта по номеру: «первую», «2», «вторая», «последнюю». */
        fun ordinal(text: String, size: Int): Int? {
            val n = RuTokenizer.normalize(text)
            val map = listOf("перв" to 0, "втор" to 1, "трет" to 2, "четверт" to 3)
            map.firstOrNull { (p, _) -> Regex("""\b$p""").containsMatchIn(n) }?.let { return it.second.takeIf { i -> i < size } }
            if (Regex("""\bпоследн""").containsMatchIn(n)) return size - 1
            Regex("""\b(\d)\b""").find(n)?.groupValues?.get(1)?.toInt()?.let { return (it - 1).takeIf { i -> i in 0 until size } }
            return null
        }
    }
}
