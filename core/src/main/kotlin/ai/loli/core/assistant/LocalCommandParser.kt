package ai.loli.core.assistant

import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.nlp.Calculator
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.nlp.Tok
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Все шаблоны этого файла компилируются с Unicode-границами слов (см. [Rx]). */
private fun Regex(pattern: String): kotlin.text.Regex = Rx.of(pattern)
private fun Regex(pattern: String, option: RegexOption): kotlin.text.Regex = kotlin.text.Regex(Rx.unicode(pattern), option)

/**
 * Локальное понимание речи — основной режим работы (без облачного AI и без интернета).
 *
 * Как устроено:
 *  1. Фраза очищается от вежливых слов и обращений («слушай», «пожалуйста», «можешь…»).
 *  2. Составная фраза делится на части по союзам перед новыми командами
 *     («запиши расход 500 на такси и напомни завтра позвонить маме»).
 *  3. Каждая часть проходит правила намерений в порядке приоритета — от вопросов и запросов к созданию записей;
 *     слоты (сумма, дата, время, категория, цель) извлекаются русскими парсерами чисел и дат.
 *  4. Если данных не хватает — возвращается [SlotRequest] (ассистент спросит и дозаполнит).
 *
 * Результат — те же структурированные действия, что возвращает облачный AI: они проходят общий исполнитель.
 */
class LocalCommandParser(private val dates: RuDateTimeParser = RuDateTimeParser()) {

    fun parse(input: String, now: Instant, zone: ZoneId): AssistantPlan? {
        val text = cleanup(input)
        if (text.isEmpty()) return null
        val today = now.atZone(zone).toLocalDate()

        wholePhrase(text, now, zone)?.let { return it }

        val clauses = splitClauses(text)
        val actions = ArrayList<AssistantAction>()
        val rejected = ArrayList<String>()
        var slot: SlotRequest? = null
        var reply = ""
        var preface = ""
        var followUp = false
        var prev: AssistantAction? = null
        for (clause in clauses) {
            val plan = parseClause(clause, now, zone, today, prev)
            if (plan == null) {
                if (clauses.size == 1) return null
                rejected += "не поняла «${clause.take(60)}»"
                continue
            }
            actions += plan.actions
            if (slot == null) slot = plan.slot
            if (reply.isEmpty()) reply = plan.reply
            if (preface.isEmpty()) preface = plan.preface
            followUp = followUp || plan.expectFollowUp
            prev = plan.actions.lastOrNull() ?: prev
        }
        if (actions.isEmpty() && slot == null && reply.isEmpty()) return null
        return AssistantPlan(reply, actions, followUp, rejected = rejected, slot = slot, preface = preface)
    }

    /** Дозаполнение недостающих данных ответом пользователя. null — ответ не подходит (это новая команда). */
    fun fillSlot(slot: SlotRequest, answer: String, now: Instant, zone: ZoneId): AssistantPlan? {
        val text = cleanup(answer)
        val today = now.atZone(zone).toLocalDate()
        return when (slot) {
            is SlotRequest.ExpenseAmount -> {
                val tokens = RuTokenizer.tokenize(text)
                val idx = pickAmount(tokens) ?: return null
                val amount = tokens[idx].number?.takeIf { it > 0 } ?: return null
                if (tokens.size > 4 && triggerAt(text)) return null
                val currency = tokens.getOrNull(idx + 1)?.norm?.let { Money.currencyWords[it] } ?: "RUB"
                plan(AssistantAction.CreateExpense(Money.toMinor(amount), currency, slot.category, slot.description, slot.date))
            }
            is SlotRequest.ReminderTime -> {
                val parsed = dates.parse(text, today)
                if (parsed.spec.isEmpty) return null
                val trigger = dates.resolveTrigger(parsed.spec, now, zone) ?: return null
                if (!trigger.isAfter(now)) return AssistantPlan("", emptyList(), slot = slot.copy(question = "Это время уже прошло. Когда напомнить?"))
                plan(AssistantAction.CreateReminder(slot.text, trigger, dates.effectiveRecurrence(parsed.spec)))
            }
            is SlotRequest.SaveAsNote -> when {
                isYes(text) -> plan(noteFromText(slot.text))
                isNo(text) -> AssistantPlan("Хорошо, не сохраняю.", emptyList())
                else -> null
            }
        }
    }

    // =====================================================================
    // Подготовка текста
    // =====================================================================

    private fun cleanup(s: String): String {
        var t = s.trim().trimEnd('.', '!')
        repeat(3) {
            t = t.replace(
                Regex("""^(?:пожалуйста|слушай|послушай|ну|а|так|короче|значит|смотри|эй|окей|ок|будь добра|будь другом|можешь|могла бы ты|не могла бы ты|ты можешь|мне нужно чтобы ты|сделай так чтобы|сделай так, чтобы)[,\s]+""", RegexOption.IGNORE_CASE), "",
            ).trim()
        }
        t = t.replace(Regex("""[,\s]+пожалуйста[.!?]?$""", RegexOption.IGNORE_CASE), "").trim()
        // «можешь добавить задачу…» → «добавь задачу…»
        val first = t.substringBefore(' ')
        INFINITIVES[RuTokenizer.normalize(first)]?.let { imp -> t = imp + t.substring(first.length) }
        return t
    }

    /** Разделяет составную фразу на отдельные команды. */
    fun splitClauses(text: String): List<String> {
        val parts = text.split(Regex("""\s*;\s*|(?<=[.!?])\s+"""))
            .flatMap { splitByConjunction(it) }
            .map { it.trim().trim(',', '.', ';').trim() }
            .filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(text) }
    }

    private fun splitByConjunction(text: String): List<String> {
        val result = ArrayList<String>()
        val sep = Regex(""",?\s+(?:и ещё|и еще|и также|а также|а ещё|а еще|а потом|и потом|после этого|потом|затем|ещё|еще|и)\s+|,\s+""")
        var start = 0
        for (m in sep.findAll(text)) {
            val rest = text.substring(m.range.last + 1)
            val nextWord = RuTokenizer.normalize(rest.trimStart().substringBefore(' '))
            val restTokens = RuTokenizer.tokenize(rest)
            val startsWithAmount = restTokens.firstOrNull()?.isNumber == true &&
                (Money.currencyWords.containsKey(restTokens.getOrNull(1)?.norm) || restTokens.getOrNull(1)?.norm == "на")
            if (nextWord in TRIGGERS || startsWithAmount) {
                result += text.substring(start, m.range.first)
                start = m.range.last + 1
            }
        }
        result += text.substring(start)
        return result
    }

    private fun triggerAt(text: String): Boolean = RuTokenizer.normalize(text).split(" ").firstOrNull() in TRIGGERS

    /** Начинается ли фраза с команды (после вежливых слов) — тогда это не ответ на уточнение. */
    fun startsWithCommand(text: String): Boolean {
        val t = RuTokenizer.normalize(cleanup(text))
        val first = t.split(" ").firstOrNull().orEmpty()
        return first in TRIGGERS || first in setOf("надо", "нужно", "создай", "покажи", "найди", "что", "какие", "давай", "список")
    }

    // =====================================================================
    // Фразы целиком: разговор, справка, время, калькулятор, мозговой штурм
    // =====================================================================

    private fun wholePhrase(text: String, now: Instant, zone: ZoneId): AssistantPlan? {
        val n = RuTokenizer.normalize(text).trimEnd('?', '!', '.')
        val z = now.atZone(zone)

        if (Regex("""^(доброе утро|с добрым утром)""").containsMatchIn(n)) {
            return AssistantPlan("", listOf(AssistantAction.Agenda(z.toLocalDate())), preface = "Доброе утро!")
        }
        if (Regex("""^(привет|приветик|здравствуй|здравствуйте|хай|добрый день|добрый вечер|доброй ночи)$""").containsMatchIn(n)) {
            return AssistantPlan(greeting(z.hour), emptyList())
        }
        if (Regex("""^(спасибо|благодарю|спасиб|пасиб|ты молодец|умница|отлично|супер|класс)""").containsMatchIn(n)) {
            return AssistantPlan(listOf("Всегда пожалуйста!", "Рада помочь!", "Обращайтесь!")[pick(now, 3)], emptyList())
        }
        if (Regex("""^(хватит|стоп|достаточно|отбой|не надо|ничего)$""").containsMatchIn(n)) {
            return AssistantPlan("Хорошо.", emptyList())
        }
        if (Regex("""^(пока|до свидания|спокойной ночи|до завтра)$""").containsMatchIn(n)) {
            return AssistantPlan(if (n.contains("ночи")) "Спокойной ночи!" else "До связи!", emptyList())
        }
        if (Regex("""^(как дела|как ты|как поживаешь|как настроение|ты тут|ты здесь|ты меня слышишь)""").containsMatchIn(n)) {
            return AssistantPlan("Всё отлично, я на связи и готова помочь.", emptyList())
        }
        if (Regex("""^(кто ты|ты кто|как тебя зовут|что ты такое|представься)""").containsMatchIn(n)) {
            return AssistantPlan("Я {name} — ваш личный ассистент. Запоминаю заметки, идеи, расходы, задачи и напоминания. Работаю даже без интернета.", emptyList())
        }
        if (Regex("""^(что ты умеешь|что ты можешь|помощь|справка|помоги|какие команды|как тобой пользоваться|что умеешь)""").containsMatchIn(n)) {
            return AssistantPlan(HELP, emptyList())
        }
        if (Regex("""(который час|сколько времени|сколько сейчас времени|какое сейчас время|время сейчас)""").containsMatchIn(n)) {
            return AssistantPlan("Сейчас ${RuFormat.time(z.toLocalTime())}.", emptyList())
        }
        if (Regex("""(какое сегодня число|какой сегодня день|какая сегодня дата|какое число|какой день недели|что сегодня за день)""").containsMatchIn(n)) {
            val day = z.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
            return AssistantPlan("Сегодня $day, ${DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")).format(z)}.", emptyList())
        }
        if (Regex("""^(расскажи|скажи)? ?(анекдот|шутку|что-нибудь смешное)|^пошути""").containsMatchIn(n)) {
            return AssistantPlan(JOKES[pick(now, JOKES.size)], emptyList())
        }
        Calculator.evaluate(n)?.let { v -> return AssistantPlan("Получается ${Calculator.format(v)}.", emptyList()) }

        // Мозговой штурм: «давай придумаем приложение для склада», «давай подумаем над моей идеей про холодильник»
        Regex("""^давай(?:те)?\s+(?:вместе\s+)?(придумаем|подумаем|обсудим|пофантазируем|накидаем|запишем идеи|разовьём|разовьем)\s+(?:над\s+|о\s+|об\s+|про\s+|для\s+|идеи\s+для\s+)?(.+)$""").find(n)?.let { m ->
            val g = m.groups[2]!!
            val subject = text.substring(g.range.first, g.range.last + 1).trim()
            val existing = Regex("""^(?:мо\w+|нашей|нашу)\s+(?:идее|идеей|идею|заметкой|заметке)\s+(?:про\s+|о\s+|об\s+|с\s+|для\s+)?(.+)$""").find(RuTokenizer.normalize(subject))
            if (existing != null) {
                val q = subject.substring(existing.groups[1]!!.range.first)
                return AssistantPlan(
                    "Давайте! Рассказывайте — всё, что скажете, добавлю в эту идею. Скажите «хватит», когда закончим.",
                    listOf(AssistantAction.Search(q, emptyList(), setOf(RecordType.IDEA, RecordType.NOTE))),
                    expectFollowUp = true, preface = "Давайте подумаем!",
                )
            }
            val title = subject.replaceFirstChar { it.uppercase() }.take(120)
            return AssistantPlan(
                "Давайте! Записала идею «$title». Рассказывайте — всё, что скажете, добавлю в неё. Скажите «хватит», когда закончим.",
                listOf(AssistantAction.CreateNote(NoteKind.IDEA, title, "")),
                expectFollowUp = true,
            )
        }
        return null
    }

    private fun greeting(hour: Int) = when (hour) {
        in 5..11 -> "Доброе утро! Я на связи."
        in 12..17 -> "Добрый день! Чем помочь?"
        in 18..22 -> "Добрый вечер! Слушаю."
        else -> "Привет! Не спится? Я здесь."
    }

    private fun pick(now: Instant, size: Int) = ((now.epochSecond / 7) % size).toInt()

    // =====================================================================
    // Разбор одной команды
    // =====================================================================

    private fun parseClause(clause: String, now: Instant, zone: ZoneId, today: LocalDate, prev: AssistantAction?): AssistantPlan? {
        val original = cleanup(clause)
        if (original.isEmpty()) return null
        val n = RuTokenizer.normalize(original).trimEnd('?', '!', '.')

        parseExpenseQuery(n, today)?.let { return plan(it) }
        if (Regex("""(какие|покажи|мои|список|перечисли|что за)\s.*напоминани|^напоминания$""").containsMatchIn(n) &&
            !Regex("""^(добавь|создай|поставь|удали|отмени)""").containsMatchIn(n)
        ) return plan(AssistantAction.QueryReminders)
        parseAgenda(original, n, today)?.let { return plan(it) }
        parseReminder(original, n, now, zone, prev)?.let { return it }
        parseTaskCreateExplicit(original, n, today)?.let { return plan(it) }
        parseList(original, n, today)?.let { return AssistantPlan("", it) }
        parseTaskQuery(n)?.let { return plan(it) }
        parseCompleteTask(original, n)?.let { return plan(it) }
        parseUndoAndEdit(n)?.let { return plan(it) }
        parseDelete(original, n)?.let { return plan(it) }
        parseMemoryQuestion(original, n)?.let { return plan(it) }
        parseMemory(original, n)?.let { return plan(it) }
        parseAppend(original, n, now, zone, today, prev)?.let { return it }
        parseIdea(original, n)?.let { return plan(it) }
        if (Regex("""^(?:мне\s+)?(?:надо|нужно|необходимо|не забыть|не забудь|я должна|я должен)\s""").containsMatchIn(n)) {
            parseTaskImplicit(original, n, now, zone, today)?.let { return plan(it) }
        }
        parseExpense(original, n, today, prev)?.let { return it }
        parseNote(original, n)?.let { return plan(it) }
        parseTaskImplicit(original, n, now, zone, today)?.let { return plan(it) }
        parseScheduledStatement(original, n, now, zone, today)?.let { return plan(it) }
        parseSearch(original, n)?.let { return plan(it) }
        parseBareInfinitiveTask(original, n, today)?.let { return plan(it) }
        return null
    }

    private fun plan(a: AssistantAction) = AssistantPlan("", listOf(a))

    private fun sub(original: String, g: MatchGroup): String = original.substring(g.range.first, minOf(g.range.last + 1, original.length)).trim()

    /**
     * Уточнение к предыдущему вопросу о расходах: «а на транспорт?», «а вчера?», «а в августе?».
     */
    fun followUpExpenseQuery(text: String, previous: AssistantAction.QueryExpenses, now: Instant, zone: ZoneId): AssistantAction.QueryExpenses? {
        val n = RuTokenizer.normalize(cleanupKeepA(text)).trimEnd('?', '.', '!')
        val m = Regex("""^(?:а|и)\s+(.+)$""").find(n) ?: return null
        val tail = m.groupValues[1].trim()
        if (tail.split(" ").size > 5) return null
        val today = now.atZone(zone).toLocalDate()
        monthRange(tail, today)?.let { (from, to) -> return previous.copy(preset = null, from = from, to = to) }
        val periodHit = Regex("""(сегодня|вчера|недел|месяц|год|вс[её] время)""").containsMatchIn(tail)
        if (periodHit) return previous.copy(preset = period(tail), from = null, to = null)
        val catWord = tail.removePrefix("на ").removePrefix("в ").removePrefix("за ").trim()
        if (catWord.isEmpty() || !Regex("""^(на|в|за)\s""").containsMatchIn(tail)) return null
        val category = ExpenseCategories.categorize(catWord).takeIf { it != ExpenseCategories.OTHER } ?: catWord
        return previous.copy(category = category)
    }

    private fun cleanupKeepA(s: String): String = s.trim().trimEnd('.', '!')

    // --- Расходы: вопросы -------------------------------------------------

    private fun parseExpenseQuery(n: String, today: LocalDate): AssistantAction? {
        // «траты на кофе 300 рублей» — это запись расхода, а не вопрос
        val amountTokens = RuTokenizer.tokenize(n)
        val hasAmount = amountTokens.withIndex().any { (i, t) ->
            t.isNumber && amountTokens.getOrNull(i + 1)?.norm?.let { it.startsWith("дн") || it.startsWith("недел") || it.startsWith("месяц") } != true
        }
        if (hasAmount && !n.contains("сколько") && !Regex("""(самы|на что|куда|по категориям)""").containsMatchIn(n)) return null
        val mode = when {
            Regex("""в среднем|средн\w+ (расход|трат)""").containsMatchIn(n) -> ReportMode.AVERAGE
            Regex("""(самы[ей]|самый)\s+(большие|крупные|дорогие|большой|крупный|дорогой)\s+(расход|трат|покупк)""").containsMatchIn(n) -> ReportMode.TOP
            Regex("""(на что|куда)\s+(я\s+)?(больше всего\s+)?(трач|потрат|уход|ушл|спуска)""").containsMatchIn(n) -> ReportMode.BY_CATEGORY
            Regex("""по категориям""").containsMatchIn(n) && Regex("""(расход|трат)""").containsMatchIn(n) -> ReportMode.BY_CATEGORY
            Regex("""^(покажи|выведи|список|перечисли|какие)\s+(мои\s+|все\s+|были\s+)?(расход|трат|покупк)""").containsMatchIn(n) -> ReportMode.LIST
            Regex("""сколько.*(потрат|трат|расход|ушл|израсход|спустил|заплатил|отдал|вышл)""").containsMatchIn(n) -> ReportMode.TOTAL
            Regex("""^(мои\s+)?(расходы|траты)\s+(за|в|на)\s""").containsMatchIn(n) -> ReportMode.LIST
            Regex("""(итог|сумма|бюджет)\s+(расходов|трат)""").containsMatchIn(n) -> ReportMode.TOTAL
            else -> return null
        }
        val category = Regex("""(?:потратил\w*|трат\w*|трачу|расход\w*|ушл\w*|спустил\w*|заплатил\w*|отдал\w*)\s+(?:на|в)\s+([а-я]+(?:\s[а-я]+)?)""").find(n)?.groupValues?.get(1)
            ?.split(" ")?.firstOrNull { it !in PERIOD_WORDS }
            ?.takeIf { it !in setOf("что", "все", "всё", "это") }
            ?.let { word -> ExpenseCategories.categorize(word).takeIf { it != ExpenseCategories.OTHER } ?: word }
        monthRange(n, today)?.let { (from, to) -> return AssistantAction.QueryExpenses(null, from, to, category, mode) }
        Regex("""за\s+(?:последние\s+)?(\d+)\s+(дн|недел)""").find(n)?.let { m ->
            val k = m.groupValues[1].toLong()
            val days = if (m.groupValues[2].startsWith("недел")) k * 7 else k
            return AssistantAction.QueryExpenses(null, today.minusDays(days - 1), today, category, mode)
        }
        return AssistantAction.QueryExpenses(period(n), null, null, category, mode)
    }

    private fun monthRange(n: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val m = Regex("""(?:в|за)\s+(январ|феврал|март|апрел|ма[йея]|июн|июл|август|сентябр|октябр|ноябр|декабр)\w*""").find(n) ?: return null
        val month = MONTH_STEMS.indexOfFirst { m.groupValues[1].startsWith(it) } + 1
        if (month <= 0) return null
        val year = if (month > today.monthValue) today.year - 1 else today.year
        val from = LocalDate.of(year, month, 1)
        val to = minOf(from.withDayOfMonth(from.lengthOfMonth()), today)
        return from to to
    }

    private fun period(n: String): PeriodPreset = when {
        Regex("""\bсегодня""").containsMatchIn(n) -> PeriodPreset.TODAY
        Regex("""\bвчера""").containsMatchIn(n) -> PeriodPreset.YESTERDAY
        Regex("""(на этой|за эту|на текущей)\s+недел""").containsMatchIn(n) -> PeriodPreset.THIS_WEEK
        Regex("""(последн\w+|прошл\w+)\s+недел|за недел|за 7 дней|за семь дней""").containsMatchIn(n) -> PeriodPreset.LAST_7_DAYS
        Regex("""(прошлом|прошлый)\s+месяц""").containsMatchIn(n) -> PeriodPreset.LAST_MONTH
        Regex("""30 дней|тридцать дней|последний месяц""").containsMatchIn(n) -> PeriodPreset.LAST_30_DAYS
        Regex("""(этом|этот|текущем)\s+год|за год""").containsMatchIn(n) -> PeriodPreset.THIS_YEAR
        Regex("""за вс[её] время|вообще|всего""").containsMatchIn(n) -> PeriodPreset.ALL
        else -> PeriodPreset.THIS_MONTH
    }

    // --- План на день -----------------------------------------------------

    private fun parseAgenda(original: String, n: String, today: LocalDate): AssistantAction? {
        val hit = Regex("""^(что у меня|что мне (надо|нужно) (сделать|успеть)|какие (у меня )?планы|мои планы|мой план|план на|планы на|расписание|что запланировано|что на сегодня|что на завтра|мой день$|чем я занята|чем я занят)""").containsMatchIn(n)
        if (!hit) return null
        if (Regex("""(задач|расход|трат|напоминан|заметк|иде)""").containsMatchIn(n) && !n.startsWith("что у меня")) return null
        if (n.startsWith("что у меня") && Regex("""(задач|расход|трат|напоминан|заметк|иде|записано)""").containsMatchIn(n)) return null
        if (Regex("""\bнедел""").containsMatchIn(n)) return AssistantAction.QueryTasks(TaskFilter.WEEK)
        val date = dates.parse(original, today).spec.date ?: today
        return AssistantAction.Agenda(date)
    }

    // --- Напоминания --------------------------------------------------------

    private fun parseReminder(original: String, n: String, now: Instant, zone: ZoneId, prev: AssistantAction?): AssistantPlan? {
        val today = now.atZone(zone).toLocalDate()
        val start = Regex("""^(напомни(те)?|напоминай(те)?|напомнить|поставь напоминание|создай напоминание|сделай напоминание|напоминание|не дай(те)? (мне )?забыть|разбуди(те)?( меня)?|поставь будильник)\b[,:]?\s*(мне|нам)?\s*""").find(n)
        var wake = false
        val body = when {
            start != null -> {
                wake = start.value.startsWith("разбуди") || start.value.startsWith("поставь будильник")
                original.substring(minOf(start.range.last + 1, original.length))
            }
            else -> {
                val mid = Regex("""\s(напомни(те)?|напоминай(те)?)(\s+(мне|нам))?(\s|$)""").find(n) ?: return null
                original.substring(0, mid.range.first) + " " + original.substring(minOf(mid.range.last + 1, original.length))
            }
        }
        val parsedRaw = dates.parse(body, today)
        val rawText = parsedRaw.remainder.trim().trim(',', '.').trim()
        val isReference = Regex("""^(об этом|про это|это|о ней|о нем|о нём|про неё|про нее|про него)$""").matches(RuTokenizer.normalize(rawText))
        var text = if (isReference) "" else rawText
            .replace(Regex("""^(что|о том,? что|о том|про|о|об|чтобы|,)\s+""", RegexOption.IGNORE_CASE), "")
            .trim().trim(',', '.').trim()
        if (wake && text.isEmpty()) text = "Подъём!"
        val parsed = if (wake) parsedRaw.copy(spec = parsedRaw.spec.copy(ambiguousHour = false)) else parsedRaw
        // «добавь задачу купить хлеб и напомни об этом завтра» — «об этом» = предыдущая команда
        if (text.isEmpty()) {
            text = when (prev) {
                is AssistantAction.CreateTask -> prev.title
                is AssistantAction.CreateNote -> prev.title
                is AssistantAction.CreateReminder -> prev.text
                else -> ""
            }
        }
        if (text.isEmpty()) return AssistantPlan("", listOf(AssistantAction.Clarify("О чём напомнить?")))
        text = text.replaceFirstChar { it.uppercase() }
        if (parsed.spec.isEmpty) {
            return AssistantPlan("", emptyList(), slot = SlotRequest.ReminderTime(text, "Когда напомнить ${RuFormat.quote(text)}? Например: «завтра в 10» или «через час»."))
        }
        val trigger = dates.resolveTrigger(parsed.spec, now, zone)
            ?: return AssistantPlan("", emptyList(), slot = SlotRequest.ReminderTime(text, "Не поняла время. Когда напомнить?"))
        if (!trigger.isAfter(now.minusSeconds(60))) {
            return AssistantPlan("", emptyList(), slot = SlotRequest.ReminderTime(text, "Это время уже прошло. Когда напомнить ${RuFormat.quote(text)}?"))
        }
        return AssistantPlan("", listOf(AssistantAction.CreateReminder(text, trigger, dates.effectiveRecurrence(parsed.spec))))
    }

    // --- Задачи -----------------------------------------------------------

    private fun parseTaskQuery(n: String): AssistantAction? {
        if (Regex("""^(добавь|создай|запиши|внеси|поставь|заведи)\s""").containsMatchIn(n)) return null
        val hit = Regex("""(покажи|какие|мои|список|что у меня|что по|сколько( у меня)?|перечисли|выведи)\s.*(задач|дел\b|дела\b)|^(задачи|мои дела|список дел)$""").containsMatchIn(n)
        if (!hit) return null
        val filter = when {
            n.contains("просроч") || n.contains("не успел") -> TaskFilter.OVERDUE
            n.contains("сегодня") -> TaskFilter.TODAY
            n.contains("завтра") -> TaskFilter.TOMORROW
            n.contains("недел") -> TaskFilter.WEEK
            Regex("""(выполненн|сделанн|завершенн|готовые|закрыт)""").containsMatchIn(n) -> TaskFilter.COMPLETED
            Regex("""\bвсе\b""").containsMatchIn(n) -> TaskFilter.ALL
            else -> TaskFilter.ACTIVE
        }
        return AssistantAction.QueryTasks(filter)
    }

    private fun parseCompleteTask(original: String, n: String): AssistantAction? {
        val patterns = listOf(
            Regex("""^(?:отметь|пометь|отметить|отмечай)\s+(?:задачу\s+)?(.+?)\s+(?:как\s+)?(?:выполненн\w*|сделанн\w*|готов\w*|завершенн\w*)$"""),
            Regex("""^(?:задача\s+)(.+?)\s+(?:выполнена|сделана|готова|завершена)$"""),
            Regex("""^(?:я\s+)?(?:сделал\w*|выполнил\w*|закончил\w*|доделал\w*|завершил\w*)\s+(?:задачу\s+)(.+)$"""),
            Regex("""^(?:вычеркни|закрой|заверши|отметь выполнение)\s+(?:задачу\s+)?(.+)$"""),
            Regex("""^(?:отметь|пометь)[,]?\s+что\s+(?:я\s+)?(?:уже\s+)?(.+)$"""),
            Regex("""^(.+?)\s*[—:-]\s*(?:готово|сделано|выполнено)$"""),
        )
        for (p in patterns) {
            val m = p.find(n) ?: continue
            val query = sub(original, m.groups[1]!!).trim('«', '»', '"', ' ')
            if (query.isEmpty()) continue
            return AssistantAction.CompleteTask(TargetRef(null, query, setOf(RecordType.TASK)))
        }
        return null
    }

    private fun parseTaskCreateExplicit(original: String, n: String, today: LocalDate): AssistantAction? {
        val m = Regex("""^(?:(?:добавь|создай|запиши|поставь|заведи|внеси|сделай)\s+)?(?:мне\s+)?(?:(?:новую\s+)?задачу|в\s+(?:мои\s+)?задачи|в\s+список\s+задач|в\s+список\s+дел|в\s+(?:мои\s+)?дела|новая\s+задача|задача|в\s+туду|туду)[:,]?\s+(.+)$""").find(n) ?: return null
        // «задачи на завтра» / «задача на сегодня какая?» — это вопрос, а не создание
        if (Regex("""^(?:задачи|задача)\s""").containsMatchIn(n) && dates.parse(sub(original, m.groups[1]!!), today).remainder.isBlank()) return null
        return taskFrom(sub(original, m.groups[1]!!), today)
    }

    private fun parseTaskImplicit(original: String, n: String, now: Instant, zone: ZoneId, today: LocalDate): AssistantAction? {
        val m = Regex("""(?:^|\s)(?:мне\s+)?(?:надо|нужно|необходимо|следует|пора|не забыть|не забудь|запланируй|я должна|я должен|должна|должен)(?:\s+бы)?[,:]?\s+(.+)$""").find(n) ?: return null
        // Перед «надо» может стоять только время: «на выходных надо разобрать шкаф».
        val prefix = original.substring(0, m.range.first).trim()
        if (prefix.isNotEmpty() && dates.parse(prefix, today).remainder.isNotBlank()) return null
        val body = (prefix + " " + sub(original, m.groups[1]!!)).trim()
            .replace(Regex("""^(?:не забыть|не забудь)\s+""", RegexOption.IGNORE_CASE), "")
        val parsed = dates.parse(body, today)
        // С конкретным временем — это скорее напоминание: «не забыть в 18:00 забрать посылку».
        if (parsed.spec.time != null || parsed.spec.offset != null) {
            val trigger = dates.resolveTrigger(parsed.spec, now, zone)
            val text = parsed.remainder.trim(',', ' ').replaceFirstChar { it.uppercase() }
            if (trigger != null && trigger.isAfter(now) && text.isNotEmpty()) return AssistantAction.CreateReminder(text, trigger, dates.effectiveRecurrence(parsed.spec))
        }
        return taskFrom(body, today)
    }

    private fun taskFrom(raw: String, today: LocalDate): AssistantAction? {
        val parsed = dates.parse(raw, today)
        val title = parsed.remainder.trim('«', '»', '"', ' ', ',').ifEmpty { return null }
        return AssistantAction.CreateTask(title.replaceFirstChar { it.uppercase() }, "", parsed.spec.date ?: parsed.spec.time?.let { today }, parsed.spec.time)
    }

    // --- Отмена и исправление ----------------------------------------------

    private fun parseUndoAndEdit(n: String): AssistantAction? {
        if (Regex("""^(отмени|удали|убери|сотри)\s+(последнее|это|предыдущее|то что (я )?(только что )?(сказала|сказал|добавила|добавил))(\s+действие|\s+запись)?$|^(отмени|отмена последнего|верни как было)$""").containsMatchIn(n)) {
            return AssistantAction.DeleteLast(null)
        }
        Regex("""^(?:удали|сотри|убери|отмени)\s+(?:последн\w+|предыдущ\w+)\s+(расход|трату|покупку|задачу|заметку|идею|напоминание|запись)""").find(n)?.let { m ->
            return AssistantAction.DeleteLast(
                when (m.groupValues[1]) {
                    "расход", "трату", "покупку" -> RecordType.EXPENSE
                    "задачу" -> RecordType.TASK
                    "заметку" -> RecordType.NOTE
                    "идею" -> RecordType.IDEA
                    "напоминание" -> RecordType.REMINDER
                    else -> null
                },
            )
        }
        Regex("""^(?:исправь|измени|поменяй|поправь)\s+(?:последн\w+\s+)?(?:расход|трату|сумму|покупку)\s+(?:на\s+)?(.+)$""").find(n)?.let { m ->
            val tokens = RuTokenizer.tokenize(m.groupValues[1])
            val amount = tokens.firstOrNull { it.isNumber }?.number?.takeIf { it > 0 }
            if (amount != null) return AssistantAction.UpdateLastExpense(Money.toMinor(amount), null)
        }
        Regex("""^не\s+(.+?),?\s+а\s+(.+)$""").find(n)?.let { m ->
            val first = RuTokenizer.tokenize(m.groupValues[1]).firstOrNull { it.isNumber }
            val second = RuTokenizer.tokenize(m.groupValues[2]).firstOrNull { it.isNumber }?.number
            if (first != null && second != null && second > 0) return AssistantAction.UpdateLastExpense(Money.toMinor(second), null)
        }
        Regex("""(?:поменяй|измени|смени|исправь)\s+категорию(?:\s+(?:последнего\s+)?расхода)?\s+на\s+(.+)$""").find(n)?.let { m ->
            return AssistantAction.UpdateLastExpense(null, ExpenseCategories.normalize(m.groupValues[1]))
        }
        return null
    }

    // --- Удаление по описанию -------------------------------------------------

    private fun parseDelete(original: String, n: String): AssistantAction? {
        val m = Regex("""^(?:удали|удалить|сотри|убери)\s+(заметку|идею|задачу|напоминание|из памяти)\s+(?:про\s+|о\s+|об\s+|что\s+)?(.+)$""").find(n)
            ?: Regex("""^(?:отмени|отменить)\s+(напоминание)\s+(?:про\s+|о\s+)?(.+)$""").find(n)
            ?: Regex("""^(забудь)[,]?\s+(?:что\s+|про\s+|о\s+)?(.+)$""").find(n)
            ?: return null
        val query = sub(original, m.groups[2]!!).trim('«', '»', '"', ' ')
        return when (m.groupValues[1]) {
            "заметку" -> AssistantAction.DeleteNote(TargetRef(null, query, setOf(RecordType.NOTE)))
            "идею" -> AssistantAction.DeleteNote(TargetRef(null, query, setOf(RecordType.IDEA)))
            "задачу" -> AssistantAction.DeleteTask(TargetRef(null, query, setOf(RecordType.TASK)))
            "из памяти", "забудь" -> AssistantAction.ForgetMemory(TargetRef(null, query, setOf(RecordType.MEMORY)))
            else -> AssistantAction.CancelReminder(TargetRef(null, query, setOf(RecordType.REMINDER)))
        }
    }

    // --- Память о пользователе -------------------------------------------------

    private fun parseMemoryQuestion(original: String, n: String): AssistantAction? {
        if (Regex("""что ты (обо мне |про меня )?(знаешь|помнишь)|что ты запомнила""").containsMatchIn(n)) return AssistantAction.QueryMemories(null)
        val q = Regex("""^(как меня зовут|когда (у меня |мой )?(день рождения|др)|что я (люблю|не люблю|не ем|предпочитаю|обожаю)|где я (работаю|живу|учусь)|как(ой|ая|ое|ие) (у меня |мой |моя |мое |моё )?любим\w*.*|как зовут мо\w+.*|когда день рождения у .+|когда у .+ день рождения|на что у меня аллергия|какая у меня аллергия|сколько мне лет)$""").find(n)
            ?: return null
        return AssistantAction.QueryMemories(original.trimEnd('?'))
    }

    private fun parseMemory(original: String, n: String): AssistantAction? {
        Regex("""^(?:запомни(?:те)?|имей в виду|учти|не забывай|знай)[,:]?\s+(?:что\s+|,\s*что\s+)?(.+)$""").find(n)?.let { m ->
            val content = sub(original, m.groups[1]!!)
            if (content.isEmpty()) return null
            return AssistantAction.Remember(content.replaceFirstChar { it.uppercase() }, categoryOfFact(RuTokenizer.normalize(content)))
        }
        // Факты о себе без слова «запомни»
        val selfFact = Regex("""^(меня зовут .+|мой день рождения .+|у меня день рождения .+|я родил(ся|ась) .+|у меня аллергия .+|я (не )?(люблю|обожаю|ненавижу|предпочитаю|ем|пью) .+|я не ем .+|я не пью .+|мо(й|я|е|ё) любим\w+ .+|я работаю .+|я живу .+|я учусь .+|(моего|мою|мой|моя|моих) \w+ зовут .+|у (моего|моей|мое|моё) \w+ день рождения .+|мне \d+ (лет|год|года))$""").find(n) ?: return null
        return AssistantAction.Remember(original.replaceFirstChar { it.uppercase() }, categoryOfFact(selfFact.value))
    }

    private fun categoryOfFact(n: String): String = when {
        Regex("""(люблю|обожаю|ненавижу|предпочитаю|любим|не ем|не пью)""").containsMatchIn(n) -> "preference"
        Regex("""(зовут|муж|жена|мама|папа|сын|дочь|брат|сестра|друг|подруг|коллег|начальник)""").containsMatchIn(n) -> "person"
        Regex("""(хочу|мечтаю|цель|планирую|собираюсь|когда-нибудь)""").containsMatchIn(n) -> "goal"
        else -> "fact"
    }

    // --- Списки: «список покупок: молоко, хлеб», «список дел: …» ---------------

    private fun parseList(original: String, n: String, today: LocalDate): List<AssistantAction>? {
        val m = Regex("""^(?:составь|создай|сделай|запиши|новый)?\s*(список\s+[а-я]+)[:,]?\s+(.+)$""").find(n) ?: return null
        val title = sub(original, m.groups[1]!!).replaceFirstChar { it.uppercase() }
        val rest = sub(original, m.groups[2]!!)
        // «список дел на сегодня» — это вопрос о задачах, а не новый список
        if (dates.parse(rest, today).remainder.isBlank() || Regex("""^(на|за)\s""").containsMatchIn(RuTokenizer.normalize(rest)) && !rest.contains(':') && !rest.contains(',')) return null
        val items = rest.split(Regex("""\s*,\s*|\s+и\s+|\s*;\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        if (RuTokenizer.normalize(title).let { it == "список дел" || it == "список задач" }) {
            return items.map { AssistantAction.CreateTask(it.replaceFirstChar { c -> c.uppercase() }, "", null, null) }
        }
        return listOf(AssistantAction.CreateNote(NoteKind.NOTE, title, items.joinToString("\n") { "• $it" }))
    }

    // --- Дополнение записей ------------------------------------------------------

    private fun parseAppend(original: String, n: String, now: Instant, zone: ZoneId, today: LocalDate, prev: AssistantAction?): AssistantPlan? {
        // «добавь в список покупок хлеб и масло» — каждый продукт отдельным пунктом
        Regex("""^(?:добавь|допиши|внеси|запиши|занеси)\s+в\s+(?:мой\s+)?(список\s+[а-я]+)\s+(.+)$""").find(n)?.let { m ->
            val listName = sub(original, m.groups[1]!!)
            val items = sub(original, m.groups[2]!!).split(Regex("""\s*,\s*|\s+и\s+|\s*;\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isNotEmpty()) {
                return AssistantPlan("", items.map {
                    AssistantAction.AppendNote(TargetRef(null, listName, setOf(RecordType.NOTE, RecordType.IDEA)), it, titleIfNew = listName.replaceFirstChar { c -> c.uppercase() })
                })
            }
        }
        // «добавь к идее холодильника сканирование штрихкодов» — сначала запись, потом текст
        Regex("""^(?:добавь|допиши|дополни|внеси)\s+(?:к|в|ко)\s+(?:моей\s+|мою\s+|моим\s+|нашей\s+)?(идее|идею|заметке|заметку|идеям|заметкам|списку|список)\s+(?:про\s+|о\s+|с\s+|об\s+|для\s+)?(.+)$""").find(n)?.let { m ->
            val kindWord = m.groupValues[1]
            val types = when {
                kindWord.startsWith("иде") -> setOf(RecordType.IDEA)
                kindWord.startsWith("спис") -> setOf(RecordType.NOTE, RecordType.IDEA)
                else -> setOf(RecordType.NOTE)
            }
            val rest = sub(original, m.groups[2]!!)
            val kind = if (types == setOf(RecordType.IDEA)) NoteKind.IDEA else NoteKind.NOTE
            val prefix = if (kindWord.startsWith("спис")) "список " else ""
            val split = kotlin.text.Regex("""\s*(?::|—|-)\s+""").split(rest, limit = 2)
            return if (split.size == 2) plan(AssistantAction.AppendNote(TargetRef(null, prefix + split[0], types), split[1], kindIfNew = kind, titleIfNew = (prefix + split[0]).replaceFirstChar { it.uppercase() }))
            else plan(AssistantAction.AppendNote(TargetRef(null, null, types), (if (prefix.isNotEmpty()) "$prefix " else "") + rest, kindIfNew = kind, splitQueryFromContent = true))
        }
        // «добавь молоко в список покупок», «запиши штрихкоды в идею про склад» — сначала текст, потом запись
        Regex("""^(?:добавь|допиши|внеси|запиши|положи|занеси)\s+(.+?)\s+(?:в|к|ко)\s+(?:мою\s+|моей\s+|мой\s+|наш\w*\s+)?(заметк\w*|иде\w*|спис\w*)\s*(?:про\s+|о\s+|об\s+|для\s+|с\s+)?(.*)$""").find(n)?.let { m ->
            val content = sub(original, m.groups[1]!!)
            val kindWord = m.groupValues[2]
            val name = m.groups[3]?.let { if (it.value.isBlank()) "" else sub(original, it) }.orEmpty()
            val types = when {
                kindWord.startsWith("иде") -> setOf(RecordType.IDEA)
                kindWord.startsWith("заметк") -> setOf(RecordType.NOTE)
                else -> setOf(RecordType.NOTE, RecordType.IDEA)
            }
            val query = if (kindWord.startsWith("спис")) "список $name".trim() else name.ifBlank { null }
            val kind = if (types == setOf(RecordType.IDEA)) NoteKind.IDEA else NoteKind.NOTE
            return plan(AssistantAction.AppendNote(TargetRef(null, query, types), content, titleIfNew = query?.replaceFirstChar { it.uppercase() }, kindIfNew = kind))
        }
        // Продолжение текущей темы: «там нужно учитывать остатки», «и ещё сканирование штрихкодов», «добавь туда…»
        Regex("""^(?:(?:добавь|допиши|запиши|внеси)\s+(?:туда|сюда|ещё|еще|к\s+ней|к\s+нему|в\s+неё|в\s+нее|в\s+него)|там|тут|туда|сюда|плюс|также|кроме того|а также|и\s+ещ[её]|и\s+там|ещ[её]\s+добавь|ещ[её])[,:]?\s+(?:ещ[её]\s+)?(.+)$""").find(n)?.let { m ->
            val rest = sub(original, m.groups[1]!!)
            // «ещё напомни…» — это новая команда, а не дополнение
            if (triggerAt(rest)) return parseClause(rest, now, zone, today, prev)
            val content = rest.replaceFirstChar { it.uppercase() }
            return plan(AssistantAction.AppendNote(TargetRef(null, null, setOf(RecordType.NOTE, RecordType.IDEA)), content))
        }
        return null
    }

    // --- Идеи ------------------------------------------------------------------

    private fun parseIdea(original: String, n: String): AssistantAction? {
        val m = Regex("""^(?:у меня\s+)?(?:появилась\s+|есть\s+|новая\s+|возникла\s+|родилась\s+|пришла\s+)?(?:идея|мысль|задумка)[:,]?\s+(?:такая[:,]?\s+)?(.+)$""").find(n)
            ?: Regex("""^(?:запиши|сохрани|добавь|создай|зафиксируй)\s+(?:новую\s+)?(?:идею|мысль|задумку)[:,]?\s+(.+)$""").find(n)
            ?: Regex("""^(?:придумал\w*|я придумал\w*|мне пришло в голову|пришло в голову)[,:]?\s+(?:что\s+)?(.+)$""").find(n)
        if (m != null) {
            val text = sub(original, m.groups[1]!!).trim('«', '»', '"')
            if (text.isEmpty()) return null
            return AssistantAction.CreateNote(NoteKind.IDEA, text.replaceFirstChar { it.uppercase() }.take(120), if (text.length > 120) text else "")
        }
        Regex("""^(?:а\s+)?что если\s+(.+)$""").find(n)?.let {
            return AssistantAction.CreateNote(NoteKind.IDEA, ("Что если " + sub(original, it.groups[1]!!)).take(120), "")
        }
        return null
    }

    // --- Заметки ----------------------------------------------------------------

    private fun parseNote(original: String, n: String): AssistantAction? {
        Regex("""^(?:создай|сделай|заведи|новая|открой|начни)\s+(?:новую\s+)?заметк\w*[:,]?\s+(?:с названием\s+|под названием\s+)?(.+)$""").find(n)?.let { m ->
            val title = sub(original, m.groups[1]!!).trim('«', '»', '"', '“', '”')
            return AssistantAction.CreateNote(NoteKind.NOTE, title.replaceFirstChar { it.uppercase() }, "")
        }
        Regex("""^(?:запиши|заметка|сохрани|зафиксируй|законспектируй|запиши себе|запиши в заметки|сделай заметку|отметь себе)[:,]?\s+(?:что\s+|,\s*что\s+)?(.+)$""").find(n)?.let { m ->
            val text = sub(original, m.groups[1]!!)
            if (text.isEmpty()) return null
            return noteFromText(text)
        }
        return null
    }

    private fun noteFromText(text: String): AssistantAction.CreateNote {
        val clean = text.trim().replaceFirstChar { it.uppercase() }
        // «рецепт блинов: мука, молоко, яйца» — заголовок до двоеточия, пункты после
        val colon = clean.indexOf(':')
        if (colon in 2..80) {
            val head = clean.substring(0, colon).trim()
            val items = clean.substring(colon + 1).split(Regex("""\s*,\s*|\s*;\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isNotEmpty()) return AssistantAction.CreateNote(NoteKind.NOTE, head, if (items.size > 1) items.joinToString("\n") { "• $it" } else items.single())
        }
        val title = clean.split(Regex("\\s+")).take(7).joinToString(" ").trimEnd(',', '.', ':')
        return AssistantAction.CreateNote(NoteKind.NOTE, title, if (title == clean) "" else clean)
    }

    // --- Расходы: запись ------------------------------------------------------------

    private fun parseExpense(original: String, n: String, today: LocalDate, prev: AssistantAction?): AssistantPlan? {
        val tokens = RuTokenizer.tokenize(original)
        val hasVerb = tokens.any { t -> SPEND_PREFIXES.any { t.norm.startsWith(it) } }
        val explicit = Regex("""^(?:запиши|добавь|внеси)?\s*(?:расход|трату|покупку|траты)\b""").containsMatchIn(n)
        val amountIdx = pickAmount(tokens)
        val continuesExpense = prev is AssistantAction.CreateExpense
        if (amountIdx == null) {
            // «потратила на продукты» — сумма не названа: спросим
            if ((hasVerb || explicit) && !n.contains("сколько")) {
                val desc = descriptionFrom(tokens, null)
                if (desc.isNotEmpty() || explicit) {
                    val date = dates.parse(original, today).spec.date?.takeIf { !it.isAfter(today) } ?: today
                    val cat = ExpenseCategories.categorize(desc.ifEmpty { original })
                    return AssistantPlan("", emptyList(), slot = SlotRequest.ExpenseAmount(cat, desc, date, "Сколько потратили${if (desc.isNotEmpty()) " на «$desc»" else ""}?"))
                }
            }
            return null
        }
        val amount = tokens[amountIdx].number?.takeIf { it > 0 } ?: return null
        val currencyNext = Money.currencyWords.containsKey(tokens.getOrNull(amountIdx + 1)?.norm)
        val desc = descriptionFrom(tokens, amountIdx)
        val categoryHit = ExpenseCategories.categorize(desc.ifEmpty { original }) != ExpenseCategories.OTHER
        val hasPrep = tokens.any { it.norm == "на" || it.norm == "за" }
        val shortPhrase = tokens.size <= 4
        val accept = hasVerb || explicit || currencyNext || (categoryHit && (hasPrep || shortPhrase)) || continuesExpense
        if (!accept) return null
        val currency = if (currencyNext) Money.currencyWords.getValue(tokens[amountIdx + 1].norm) else "RUB"
        val date = dates.parse(original, today).spec.date?.takeIf { !it.isAfter(today) } ?: (prev as? AssistantAction.CreateExpense)?.date ?: today
        val category = ExpenseCategories.categorize(desc.ifEmpty { original })
        return plan(AssistantAction.CreateExpense(Money.toMinor(amount), currency, category, desc, date))
    }

    /** Описание покупки: после «на»/«за», иначе значимые слова фразы. */
    private fun descriptionFrom(tokens: List<Tok>, amountIdx: Int?): String {
        fun skip(t: Tok, i: Int) = i == amountIdx || t.isNumber || t.isTime || Money.currencyWords.containsKey(t.norm) ||
            SPEND_PREFIXES.any { t.norm.startsWith(it) } || t.norm in FILLER || t.norm in DATE_WORDS ||
            t.norm in setOf("расход", "трату", "покупку", "траты", "запиши", "добавь", "внеси")
        val na = tokens.indices.firstOrNull { i -> (tokens[i].norm == "на" || tokens[i].norm == "за") && tokens.drop(i + 1).any { !skip(it, -1) } }
        val source = if (na != null) tokens.drop(na + 1).mapIndexed { i, t -> Pair(na + 1 + i, t) } else tokens.mapIndexed { i, t -> Pair(i, t) }
        return source.filter { (i, t) -> !skip(t, i) && t.norm !in PREPOSITIONS }
            .joinToString(" ") { it.second.text }.trim()
    }

    private fun pickAmount(tokens: List<Tok>): Int? {
        tokens.forEachIndexed { i, t -> if (t.isNumber && Money.currencyWords.containsKey(tokens.getOrNull(i + 1)?.norm)) return i }
        tokens.forEachIndexed { i, t ->
            val next = tokens.getOrNull(i + 1)?.norm
            val prev = tokens.getOrNull(i - 1)?.norm
            val beforePrev = tokens.getOrNull(i - 2)?.norm.orEmpty()
            val vAfterSpend = (prev == "в" || prev == "во") && SPEND_PREFIXES.any { beforePrev.startsWith(it) }
            val dayOfMonth = next == "числа" || next == "число" || next == "го" || next == "-го"
            if (t.isNumber && next !in TIME_WORDS && !dayOfMonth && (vAfterSpend || (prev != "в" && prev != "во")) && next !in MONTHS_GEN && !t.isTime) return i
        }
        return null
    }

    // --- События с датой/временем: «у меня завтра встреча в 15:00», «в пятницу сдать отчёт» -----

    private fun parseScheduledStatement(original: String, n: String, now: Instant, zone: ZoneId, today: LocalDate): AssistantAction? {
        if (original.trim().endsWith("?")) return null
        if (Regex("""^(что|где|когда|кто|какой|какая|какие|как|сколько|почему|зачем)\s""").containsMatchIn(n)) return null
        val parsed = dates.parse(original, today)
        if (parsed.spec.isEmpty) return null
        val text = parsed.remainder
            .replace(Regex("""^(?:у меня|у нас|мне|я|нам)\s+(?:будет\s+|есть\s+)?""", RegexOption.IGNORE_CASE), "")
            .trim().trim(',', '.', '-', '—').trim()
        if (text.isEmpty() || text.split(Regex("\\s+")).size > 10) return null
        val title = text.replaceFirstChar { it.uppercase() }
        if (parsed.spec.time != null || parsed.spec.offset != null || parsed.spec.recurrence != null) {
            val trigger = dates.resolveTrigger(parsed.spec, now, zone) ?: return null
            if (!trigger.isAfter(now)) return null
            return AssistantAction.CreateReminder(title, trigger, dates.effectiveRecurrence(parsed.spec))
        }
        val date = parsed.spec.date ?: return null
        if (date.isBefore(today)) return null
        return AssistantAction.CreateTask(title, "", date, null)
    }

    /** «купить корм коту», «позвонить в банк» — короткая фраза-действие без команды становится задачей. */
    private fun parseBareInfinitiveTask(original: String, n: String, today: LocalDate): AssistantAction? {
        val words = n.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 8) return null
        val first = words.first()
        val isInfinitive = (first.endsWith("ть") || first.endsWith("ти") || first.endsWith("чь")) && first.length >= 4 &&
            first !in setOf("есть", "быть", "мать", "путь", "часть", "власть", "память", "новость", "радость", "сеть", "кровать", "тетрадь", "площадь")
        if (!isInfinitive) return null
        return taskFrom(original, today)
    }

    // --- Поиск и вопросы ----------------------------------------------------------------

    private fun parseSearch(original: String, n: String): AssistantAction? {
        val m = Regex("""^(?:найди|поищи|покажи|найти|где|открой|вспомни|посмотри)\s+(?:мне\s+)?(?:вс[её],?\s+что\s+(?:я\s+)?\w+\s+|вс[её]\s+)?(?:мо[юиея]\s+|мой\s+|наш\w*\s+)?(?:(заметк\w*|иде\w*|задач\w*|напоминани\w*)\s+)?(?:про\s+|о\s+|об\s+|по\s+|на тему\s+|с\s+)?(.+)$""").find(n)
            ?: Regex("""^что я (?:записывал\w*|писал\w*|сохранял\w*|говорил\w*)\s+(?:про|о|об)\s+(.+)$""").find(n)
        if (m != null) {
            var typeWord = if (m.groups.size > 2) m.groups[1]?.value else null
            val g = m.groups[m.groups.size - 1]!!
            var query = sub(original, g).trim('«', '»', '"', '?')
            if (query.isEmpty()) return null
            val typeGroup = m.groups.takeIf { it.size > 2 }?.get(1)
            if (typeGroup != null && !Regex("""^(про|о|об|по|на тему|с)\s""").containsMatchIn(n.substring(typeGroup.range.last + 1).trimStart())) {
                query = sub(original, MatchGroup(typeGroup.value, typeGroup.range.first..g.range.last)).trim('?')
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
        // Вопросы по своим записям: «что я хотела подарить маме?», «где рецепт борща?»
        if (original.trim().endsWith("?") || Regex("""^(что|где|когда|кто|какой|какая|какие|какое|есть ли|помнишь ли|напомни мне что|как)\s""").containsMatchIn(n)) {
            val q = n.replace(Regex("""^(что|где|когда|кто|какой|какая|какие|какое|есть ли|помнишь ли|напомни мне что|как)\s+"""), "")
            if (q.split(" ").size >= 1 && q.isNotBlank()) return AssistantAction.Search(q, emptyList(), null)
        }
        return null
    }

    companion object {
        /** Слова, с которых начинается новая команда (для разбиения составных фраз). */
        val TRIGGERS = setOf(
            "напомни", "напоминай", "добавь", "запиши", "запомни", "создай", "отметь", "удали", "найди", "покажи",
            "потратила", "потратил", "заплатила", "заплатил", "поставь", "допиши", "внеси", "сохрани", "отмени",
            "купила", "купил", "сколько", "разбуди", "забудь", "вычеркни", "заведи", "составь",
        )
        private val SPEND_PREFIXES = listOf(
            "потрат", "потрач", "заплат", "оплат", "купил", "отдал", "закинул", "спустил", "обошл", "обошел", "вышло", "вышел", "вышла", "ушло",
            "стоил", "израсход", "взял", "перевел", "перевёл", "задонатил", "скинул", "заказал", "трата", "траты",
        )
        private val INFINITIVES = mapOf(
            "добавить" to "добавь", "записать" to "запиши", "создать" to "создай", "напомнить" to "напомни",
            "найти" to "найди", "показать" to "покажи", "удалить" to "удали", "отметить" to "отметь",
            "запомнить" to "запомни", "поставить" to "поставь", "сохранить" to "сохрани", "внести" to "внеси",
            "дописать" to "допиши", "отменить" to "отмени", "посчитать" to "посчитай", "рассказать" to "расскажи",
        )
        private val TIME_WORDS = setOf("утра", "вечера", "дня", "ночи", "часов", "часа", "час", "минут", "минуты", "минуту")
        private val DATE_WORDS = setOf("сегодня", "вчера", "позавчера", "завтра")
        private val PREPOSITIONS = setOf("на", "за", "в", "во", "с", "со", "у", "к", "по", "для", "из")
        private val FILLER = setOf("я", "мне", "уже", "вот", "и", "ещё", "еще", "там", "тут", "это", "всего", "примерно", "около", "где-то")
        private val PERIOD_WORDS = setOf(
            "этой", "прошлой", "этот", "прошлый", "этом", "прошлом", "неделе", "неделю", "месяц", "месяце", "сегодня", "вчера", "год",
            "последнюю", "последний", "последние", "прошлую", "эту", "текущий", "текущую", "январе", "феврале", "марте", "апреле", "мае",
            "июне", "июле", "августе", "сентябре", "октябре", "ноябре", "декабре",
        )
        private val MONTH_STEMS = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
        private val MONTHS_GEN = setOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

        private val HELP = """
            Я работаю прямо на телефоне, даже без интернета. Примеры:
            • «Потратила 850 рублей на продукты», «сколько я потратила в этом месяце», «на что я больше всего трачу»
            • «Добавь задачу купить хлеб на завтра», «что у меня на сегодня», «отметь задачу купить хлеб выполненной»
            • «Напомни через час выключить плиту», «каждый понедельник в 9 напоминай проверить почту»
            • «Создай заметку Идеи для дня рождения», «добавь туда настолку», «список покупок: молоко, хлеб, сыр»
            • «У меня идея: приложение для холодильника», «давай придумаем приложение для склада»
            • «Запомни, что я люблю зелёный чай», «что ты обо мне знаешь», «как меня зовут?»
            • «Найди всё про отпуск», «отмени последнее», «исправь последний расход на 900»
            • «Сколько будет 15% от 2000», «который час»
        """.trimIndent()

        private val JOKES = listOf(
            "Программист ставит на тумбочку два стакана: один с водой — если захочет пить, другой пустой — если не захочет.",
            "— Лоли, запомни: я никогда не опаздываю. — Запомнила. Напоминание «не опаздывать» уже стоит на завтра, 8:00.",
            "Мой бюджет — как Wi-Fi в метро: вроде есть, а пользоваться невозможно.",
            "Я не забываю задачи. Я их просто очень надолго откладываю — это называется «долгосрочное планирование».",
            "Холодильник — единственный, кто по-настоящему знает, сколько раз за ночь я «просто посмотреть».",
        )

        private val YES_WORDS = setOf(
            "да", "ага", "угу", "конечно", "подтверждаю", "верно", "ок", "окей", "yes", "согласна", "согласен", "точно",
            "разумеется", "именно", "давай", "удаляй", "отменяй", "сохраняй", "записывай", "естественно", "подтверди",
        )
        private val YES_FILLER = setOf("да", "конечно", "удаляй", "давай", "подтверждаю", "пожалуйста", "так", "ок", "это", "все", "всё", "его", "её", "ее", "их", "точно", "сохраняй")
        private val NO_START = Regex("""^(нет|неа|отмена|отмени|стоп|no|не надо|не нужно|не удаляй|не сохраняй|не стоит|не надо удалять|передумал\w*|да нет|не)(\s|$|[,.!])""")

        private fun words(text: String) = RuTokenizer.normalize(text).trim().trim('.', '!', ',', '?')
            .split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }

        /**
         * Короткий ответ «да» на запрос подтверждения. Только сама фраза согласия (до 4 слов) —
         * новая команда («удали заметку про борщ») подтверждением не считается.
         */
        fun isYes(text: String): Boolean {
            val w = words(text)
            if (w.isEmpty() || w.size > 4) return false
            if (w.size == 1 && w[0] in setOf("удали", "отмени", "сохрани", "запиши")) return true
            if (w.first() !in YES_WORDS) return false
            if (w.joinToString(" ").startsWith("да нет")) return false
            return w.drop(1).all { it in YES_FILLER || it.startsWith("удал") || it.startsWith("сохран") }
        }

        /** Короткий отказ. «Не забудь купить хлеб» — не отказ, а новая команда. */
        fun isNo(text: String): Boolean {
            val w = words(text)
            if (w.isEmpty()) return false
            val joined = w.joinToString(" ")
            if (!NO_START.containsMatchIn(joined)) return false
            if (w.first() == "не" && w.size > 1 && joined !in setOf("не надо", "не нужно", "не стоит", "не удаляй", "не сохраняй", "не надо удалять")) {
                return joined.startsWith("не надо") || joined.startsWith("не нужно") || joined.startsWith("не удаляй") || joined.startsWith("не сохраняй")
            }
            return w.size <= 5 || w.first() == "нет"
        }

        /** Выход из диалогового режима. */
        fun isDialogEnd(text: String): Boolean {
            val n = RuTokenizer.normalize(text).trim().trim('.', '!', ',')
            return Regex("""^(хватит|стоп|все,? спасибо|всё,? спасибо|закончим|заканчиваем|пока|на этом вс[её]|достаточно|спасибо,? вс[её]|вс[её]|это вс[её])$""").containsMatchIn(n)
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
