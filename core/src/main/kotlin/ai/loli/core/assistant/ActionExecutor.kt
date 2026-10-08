package ai.loli.core.assistant

import ai.loli.core.domain.ExpenseRepository
import ai.loli.core.domain.MemoryRepository
import ai.loli.core.domain.NoteRepository
import ai.loli.core.domain.ReminderRepository
import ai.loli.core.domain.TaskRepository
import ai.loli.core.finance.ExpenseAnalytics
import ai.loli.core.finance.PeriodPreset
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.model.TaskItem
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.search.SearchService
import ai.loli.core.util.TimeSource

/** Итог выполнения одного действия. */
data class Outcome(val text: String, val kind: Kind, val record: RecordRef? = null) {
    enum class Kind { CHANGED, QUERY, QUESTION, ERROR }
}

data class ExecutionResult(
    val outcomes: List<Outcome>,
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingChoice: PendingChoice? = null,
) {
    val hasErrors: Boolean get() = outcomes.any { it.kind == Outcome.Kind.ERROR }
    val hasQueries: Boolean get() = outcomes.any { it.kind == Outcome.Kind.QUERY }
    val needsAnswer: Boolean get() = pendingConfirmation != null || pendingChoice != null || outcomes.any { it.kind == Outcome.Kind.QUESTION }
}

/**
 * Исполняет проверенные действия над локальным хранилищем. Не зависит от AI:
 * одни и те же действия приходят и из облачной модели, и из офлайн-парсера.
 */
class ActionExecutor(
    private val notes: NoteRepository,
    private val expenses: ExpenseRepository,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val memories: MemoryRepository,
    private val search: SearchService,
    private val resolver: TargetResolver,
    private val scheduler: ReminderScheduler,
    private val time: TimeSource,
    private val device: DeviceController = UnsupportedDevice,
    /**
     * Правила для заблокированного экрана или null, если телефон разблокирован.
     * Что разрешено — решает пользователь в настройках; по умолчанию личные данные скрыты.
     */
    private val lockPolicy: () -> LockPolicy? = { null },
    private val shopping: ai.loli.core.data.SqlShoppingRepository? = null,
    private val routines: ai.loli.core.data.SqlRoutineRepository? = null,
    private val secrets: ai.loli.core.data.SecretNoteStore? = null,
    /** Погода и события календаря телефона для плана на день. */
    private val agendaExtras: suspend (java.time.LocalDate) -> ai.loli.core.skills.AgendaExtras = { ai.loli.core.skills.AgendaExtras() },
    /** Дни рождения из контактов телефона — вместе с записанными в Лоли. */
    private val contactBirthdays: suspend () -> List<Pair<String, java.time.MonthDay>> = { emptyList() },
    /** Напоминание «настойчиво, пока не отмечу» создано: приложение запоминает его id и повторяет срабатывание. */
    private val onPersistentReminder: (String) -> Unit = {},
) {
    /**
     * Сценарий по расписанию выполняется сам, когда телефон обычно заблокирован. Его создал владелец на разблокированном
     * телефоне (создание сценариев на блокировке запрещено), поэтому на время запуска правила блокировки не действуют.
     */
    @Volatile var trusted: Boolean = false

    private val things = ai.loli.core.personal.ThingsBook(memories)
    private val debts = ai.loli.core.personal.DebtBook(memories)
    private val deadlines = ai.loli.core.personal.DeadlineBook(memories)

    private fun longDate(d: java.time.LocalDate): String = "${d.dayOfMonth} ${MONTHS_GEN[d.monthValue - 1]}" + if (d.year != time.today().year) " ${d.year}" else ""

    private fun daysLeft(d: java.time.LocalDate): String = when (val n = java.time.temporal.ChronoUnit.DAYS.between(time.today(), d)) {
        0L -> "сегодня"
        1L -> "завтра"
        in 2L..60L -> "через ${RuFormat.count(n.toInt(), "день", "дня", "дней")}"
        in Long.MIN_VALUE..-1L -> "истёк"
        else -> "через ${RuFormat.count((n / 30).toInt(), "месяц", "месяца", "месяцев")}"
    }

    /** Список с похожим названием уже есть («Фильмы» и «фильмов») — пишем в него, а не заводим второй. */
    private suspend fun listNamed(name: String): String {
        val existing = shopping?.all()?.map { it.listName }?.distinct().orEmpty()
        return existing.firstOrNull { it.equals(name, true) } ?: existing.firstOrNull { ai.loli.core.personal.sameName(it, name) } ?: name
    }

    suspend fun execute(actions: List<AssistantAction>, context: ConversationContext): ExecutionResult {
        val outcomes = ArrayList<Outcome>()
        val confirmOps = ArrayList<DestructiveOp>()
        val confirmQuestions = ArrayList<String>()
        for ((index, action) in actions.withIndex()) {
            val step = runAction(action, context)
            outcomes += step.outcomes
            step.record?.let { context.touchRecord(it) }
            if (action is AssistantAction.CreateNote || action is AssistantAction.CreateExpense || action is AssistantAction.CreateTask ||
                action is AssistantAction.CreateReminder || action is AssistantAction.Remember
            ) {
                step.created?.let { context.lastCreated = it; context.lastListAdded = emptyList() } ?: step.record?.let { context.lastCreated = it }
            }
            step.confirm?.let { confirmOps += it.operations; confirmQuestions += it.question }
            if (step.choice != null) {
                // Остальные действия подождут ответа пользователя — выполнять их вслепую нельзя.
                val skipped = actions.size - index - 1
                if (skipped > 0) outcomes += Outcome("Остальную часть команды выполню после уточнения.", Outcome.Kind.QUESTION)
                return ExecutionResult(
                    outcomes,
                    confirmOps.takeIf { it.isNotEmpty() }?.let { PendingConfirmation(confirmQuestions.joinToString(" "), it) },
                    step.choice.copy(remaining = actions.drop(index + 1)),
                )
            }
        }
        val confirmation = if (confirmOps.isEmpty()) null else PendingConfirmation(
            (if (confirmQuestions.size == 1) confirmQuestions.first() else "Подтвердите: " + confirmQuestions.joinToString(" ")) +
                " Скажите «да» или «нет».",
            confirmOps,
        )
        return ExecutionResult(outcomes, confirmation, null)
    }

    /** Выполняет подтверждённые пользователем разрушительные операции. */
    suspend fun applyConfirmed(pending: PendingConfirmation, context: ConversationContext): ExecutionResult {
        var done = 0
        for (op in pending.operations) {
            val ok = when (op.type) {
                RecordType.NOTE, RecordType.IDEA -> notes.delete(op.id)
                RecordType.EXPENSE -> expenses.delete(op.id)
                RecordType.TASK -> tasks.delete(op.id)
                RecordType.REMINDER -> {
                    scheduler.cancel(op.id)
                    if (op.cancelOnly) reminders.cancel(op.id) != null else reminders.delete(op.id)
                }
                RecordType.MEMORY -> memories.delete(op.id)
            }
            if (ok) { done++; context.forgetRecord(op.id) }
        }
        val text = when {
            done == 0 -> "Ничего не изменилось: записи уже удалены."
            pending.operations.size == 1 -> {
                val op = pending.operations.first()
                if (op.type == RecordType.REMINDER) "Готово, напоминание ${RuFormat.quote(op.title)} отменено." else "Готово, удалила ${RuFormat.quote(op.title)}."
            }
            else -> "Готово, удалила ${ExpenseAnalytics.plural(done, "запись", "записи", "записей")}."
        }
        return ExecutionResult(listOf(Outcome(text, Outcome.Kind.CHANGED)))
    }

    private data class Step(
        val outcomes: List<Outcome>,
        val record: RecordRef? = null,
        val confirm: PendingConfirmation? = null,
        val choice: PendingChoice? = null,
        /** Созданная запись, которая не должна становиться фокусом разговора (например, расход). */
        val created: RecordRef? = null,
    )

    /** «Удалить заметку», а не «Удалить заметка». */
    private fun accusative(t: RecordType): String = when (t) {
        RecordType.NOTE -> "заметку"
        RecordType.IDEA -> "идею"
        RecordType.TASK -> "задачу"
        RecordType.REMINDER -> "напоминание"
        RecordType.EXPENSE -> "расход"
        RecordType.MEMORY -> "запись в памяти"
    }


    private class Sub(val name: String, val price: Double, val yearly: Boolean)

    private suspend fun subscriptions(): List<Sub> = memories.all().filter { it.category == SUBSCRIPTIONS }.mapNotNull { m ->
        val r = Regex("""^Подписка (.+?): (\d+(?:,\d+)?) ₽ (в месяц|в год)""").find(m.content) ?: return@mapNotNull null
        Sub(r.groupValues[1], r.groupValues[2].replace(',', '.').toDouble(), r.groupValues[3] == "в год")
    }

    private fun sameSub(a: String, b: String): Boolean {
        val x = a.lowercase().replace('ё', 'е'); val y = b.lowercase().replace('ё', 'е')
        return x == y || (x.length >= 4 && y.length >= 4 && x.take(4) == y.take(4))
    }

    /** Удаляет запись в памяти и напоминания о подписке; возвращает, сколько записей убрано. */
    private suspend fun removeSubscription(name: String): Int {
        var n = 0
        memories.all().filter { it.category == SUBSCRIPTIONS }.forEach { m ->
            val title = Regex("""^Подписка (.+?):""").find(m.content)?.groupValues?.get(1) ?: return@forEach
            if (sameSub(title, name)) { memories.delete(m.id); n++ }
        }
        reminders.all().forEach { r ->
            val title = Regex("""^Подписка (.+?): сегодня спишется""").find(r.text)?.groupValues?.get(1) ?: return@forEach
            if (sameSub(title, name)) { reminders.delete(r.id); scheduler.cancel(r.id); n++ }
        }
        return n
    }

    private fun changed(text: String, record: RecordRef? = null) = Step(listOf(Outcome(text, Outcome.Kind.CHANGED, record)), record)
    private fun query(text: String) = Step(listOf(Outcome(text, Outcome.Kind.QUERY)))
    private fun error(text: String) = Step(listOf(Outcome(text, Outcome.Kind.ERROR)))

    private fun ambiguous(options: List<RecordRef>, verb: String, action: AssistantAction): Step {
        val list = options.mapIndexed { i, o -> "${i + 1}) ${o.type.titleRu.lowercase()} ${RuFormat.quote(o.title)}" }.joinToString(", ")
        val question = "Нашла несколько подходящих записей: $list. Какую $verb?"
        return Step(listOf(Outcome(question, Outcome.Kind.QUESTION)), choice = PendingChoice(question, options, action))
    }

    private suspend fun runAction(action: AssistantAction, ctx: ConversationContext): Step {
        (if (trusted) null else lockPolicy())?.let { policy ->
            if (!policy.allows(action)) {
                return error("Разблокируйте — ${lockedReason(action)} без разблокировки не разрешено (это меняется в настройках Лоли).")
            }
        }
        val now = time.now()
        val zone = time.zone()
        val today = time.today()
        return when (action) {
            is AssistantAction.CreateNote -> {
                val note = notes.create(action.kind, action.title, action.content, action.tags)
                val ref = RecordRef(action.kind.recordType, note.id, note.title)
                changed(if (action.kind == NoteKind.IDEA) "Записала идею ${RuFormat.quote(note.title)}." else "Создала заметку ${RuFormat.quote(note.title)}.", ref)
            }

            is AssistantAction.AppendNote -> appendNote(action, ctx)

            is AssistantAction.UpdateNote -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> {
                    val note = notes.get(r.ref.id) ?: return error("Запись не найдена.")
                    val updated = notes.update(note.copy(title = action.title ?: note.title, content = action.content ?: note.content))
                    changed("Обновила ${RuFormat.quote(updated.title)}.", r.ref.copy(title = updated.title))
                }
                is Resolution.Ambiguous -> ambiguous(r.options, "изменить", action)
                Resolution.NotFound -> error("Не нашла запись, которую нужно изменить.")
            }

            is AssistantAction.DeleteNote -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> Step(emptyList(), confirm = PendingConfirmation(
                    "Удалить ${accusative(r.ref.type)} ${RuFormat.quote(r.ref.title)}?", listOf(DestructiveOp(r.ref.type, r.ref.id, r.ref.title)),
                ))
                is Resolution.Ambiguous -> ambiguous(r.options, "удалить", action)
                Resolution.NotFound -> error("Не нашла такую запись.")
            }

            is AssistantAction.CreateExpense -> {
                val e = expenses.create(action.amountMinor, action.currency, action.category, action.description, action.date)
                val whenText = if (e.occurredOn == today) "" else " (${RuFormat.date(e.occurredOn, today)})"
                val desc = if (e.description.isNotBlank() && !e.description.equals(e.category, true)) ", ${e.description}" else ""
                Step(
                    listOf(Outcome(
                        if (e.category == ExpenseCategories.INCOME) "Записала доход: +${Money.format(e.amountMinor, e.currency)}$desc$whenText."
                        else "Записала расход: ${Money.format(e.amountMinor, e.currency)} — ${e.category}$desc$whenText.",
                        Outcome.Kind.CHANGED,
                    )),
                    created = RecordRef(RecordType.EXPENSE, e.id, "${Money.format(e.amountMinor, e.currency)} — ${e.category}"),
                )
            }

            is AssistantAction.QueryExpenses -> {
                ctx.lastExpenseQuery = action
                val range = if (action.from != null) ExpenseAnalytics.customRange(action.from, action.to ?: today)
                else ExpenseAnalytics.range(action.preset ?: PeriodPreset.THIS_MONTH, today)
                val report = ExpenseAnalytics.report(expenses.between(range.from, range.to), range, action.category)
                query(ExpenseAnalytics.describe(report, action.mode))
            }

            is AssistantAction.DeleteExpenses -> {
                val targets = if (action.ids.isNotEmpty()) action.ids.mapNotNull { expenses.get(it) } else {
                    val range = if (action.from != null) ExpenseAnalytics.customRange(action.from, action.to ?: action.from)
                    else ExpenseAnalytics.range(action.preset ?: PeriodPreset.TODAY, today)
                    expenses.between(range.from, range.to).filter { action.category == null || ExpenseAnalytics.matchesCategory(it, action.category) }
                }
                if (targets.isEmpty()) return query("Подходящих расходов не нашла — удалять нечего.")
                val sum = targets.groupBy { it.currency }.entries.joinToString(" и ") { (c, l) -> Money.format(l.sumOf { it.amountMinor }, c) }
                Step(emptyList(), confirm = PendingConfirmation(
                    "Удалить ${ExpenseAnalytics.plural(targets.size, "расход", "расхода", "расходов")} на сумму $sum?",
                    targets.map { DestructiveOp(RecordType.EXPENSE, it.id, "${Money.format(it.amountMinor, it.currency)} ${it.category}") },
                ))
            }

            is AssistantAction.CreateTask -> {
                val t = tasks.create(action.title, action.details, action.dueDate, action.dueTime)
                val due = t.dueDate?.let { d -> " на ${RuFormat.dateFull(d, today).removePrefix("в ")}" + (t.dueTime?.let { " в ${RuFormat.time(it)}" } ?: "") } ?: ""
                changed("Добавила задачу ${RuFormat.quote(t.title)}$due.", RecordRef(RecordType.TASK, t.id, t.title))
            }

            is AssistantAction.CompleteTask -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> {
                    val t = tasks.setDone(r.ref.id, action.done) ?: return error("Задача не найдена.")
                    changed(if (action.done) "Отметила задачу ${RuFormat.quote(t.title)} выполненной." else "Вернула задачу ${RuFormat.quote(t.title)} в работу.", r.ref)
                }
                is Resolution.Ambiguous -> ambiguous(r.options, "отметить", action)
                Resolution.NotFound -> error("Не нашла такую задачу.")
            }

            is AssistantAction.QueryTasks -> query(describeTasks(tasks.all(), action.filter))

            is AssistantAction.DeleteTask -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> Step(emptyList(), confirm = PendingConfirmation(
                    "Удалить задачу ${RuFormat.quote(r.ref.title)}?", listOf(DestructiveOp(RecordType.TASK, r.ref.id, r.ref.title)),
                ))
                is Resolution.Ambiguous -> ambiguous(r.options, "удалить", action)
                Resolution.NotFound -> error("Не нашла такую задачу.")
            }

            is AssistantAction.CreateReminder -> {
                val reminder = reminders.create(action.text, action.triggerAt, action.recurrence, zone.id)
                scheduler.schedule(reminder)
                if (action.persistent) onPersistentReminder(reminder.id)
                val whenText = RuFormat.dateTime(reminder.triggerAt, zone, now)
                val repeat = action.recurrence?.let { " Повтор: ${it.describeRu()}." } ?: ""
                val nag = if (action.persistent) " Буду напоминать каждые 10 минут, пока не нажмёте «Готово»." else ""
                changed("Напомню ${RuFormat.quote(reminder.text)} $whenText.$repeat$nag", RecordRef(RecordType.REMINDER, reminder.id, reminder.text))
            }

            is AssistantAction.CancelReminder -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> Step(emptyList(), confirm = PendingConfirmation(
                    "Отменить напоминание ${RuFormat.quote(r.ref.title)}?", listOf(DestructiveOp(RecordType.REMINDER, r.ref.id, r.ref.title, cancelOnly = true)),
                ))
                is Resolution.Ambiguous -> ambiguous(r.options, "отменить", action)
                Resolution.NotFound -> error("Не нашла такое напоминание.")
            }

            is AssistantAction.DeleteAll -> {
                val (ops, name) = when (action.type) {
                    RecordType.REMINDER -> reminders.active().map { DestructiveOp(RecordType.REMINDER, it.id, it.text, cancelOnly = true) } to
                        Triple("активное напоминание", "активных напоминания", "активных напоминаний")
                    RecordType.TASK -> tasks.all().filter { !it.done }.map { DestructiveOp(RecordType.TASK, it.id, it.title) } to
                        Triple("задачу в работе", "задачи в работе", "задач в работе")
                    else -> return error("Так удалять сразу всё не умею.")
                }
                if (ops.isEmpty()) return query(if (action.type == RecordType.TASK) "Активных задач нет — удалять нечего." else "Активных напоминаний нет — удалять нечего.")
                Step(emptyList(), confirm = PendingConfirmation("Удалить ${ExpenseAnalytics.plural(ops.size, name.first, name.second, name.third)}?", ops))
            }

            is AssistantAction.RescheduleReminder -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> {
                    if (r.ref.type == RecordType.TASK) {
                        // «Перенеси купить молоко на завтра» без слова «задачу» — нашлась задача.
                        val t = tasks.get(r.ref.id) ?: return error("Задача не найдена.")
                        val d = action.date ?: action.triggerAt?.atZone(zone)?.toLocalDate()
                        val tm = action.time ?: action.triggerAt?.atZone(zone)?.toLocalTime()
                        if (d == null && tm == null) return error("Задачу можно перенести на день или время, например: «на завтра».")
                        val upd = tasks.update(t.copy(dueDate = d ?: t.dueDate ?: today, dueTime = tm ?: t.dueTime))
                        val due = upd.dueDate?.let { dd -> " на ${RuFormat.dateFull(dd, today).removePrefix("в ")}" + (upd.dueTime?.let { " в ${RuFormat.time(it)}" } ?: "") } ?: ""
                        return changed("Перенесла задачу ${RuFormat.quote(upd.title)}$due.", r.ref)
                    }
                    val cur = reminders.get(r.ref.id) ?: return error("Напоминание не найдено.")
                    // Сдвиг считается от срока, если он ещё впереди, иначе (уже сработало) — от «сейчас»: «отложи на 10 минут».
                    val at = when {
                        action.triggerAt != null -> action.triggerAt
                        action.shiftSeconds != null -> (if (cur.active && cur.triggerAt.isAfter(now)) cur.triggerAt else now).plusSeconds(action.shiftSeconds)
                        else -> {
                            val base = cur.triggerAt.atZone(zone)
                            var z = java.time.ZonedDateTime.of(action.date ?: base.toLocalDate(), action.time ?: base.toLocalTime(), zone)
                            if (action.date == null && !z.toInstant().isAfter(now)) z = z.plusDays(1)
                            z.toInstant()
                        }
                    }
                    if (!at.isAfter(now)) return error("Это время уже прошло. Скажите, например: «перенеси на завтра в 10».")
                    val updated = reminders.update(cur.copy(triggerAt = at, active = true))
                    scheduler.schedule(updated)
                    changed("Перенесла напоминание ${RuFormat.quote(updated.text)} на ${RuFormat.dateTime(at, zone, now)}.", r.ref)
                }
                is Resolution.Ambiguous -> ambiguous(r.options, "перенести", action)
                Resolution.NotFound -> error("Не нашла, что переносить: такого напоминания или задачи нет.")
            }

            is AssistantAction.RescheduleTask -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> {
                    val cur = tasks.get(r.ref.id) ?: return error("Задача не найдена.")
                    val updated = tasks.update(cur.copy(dueDate = action.dueDate ?: cur.dueDate, dueTime = action.dueTime ?: cur.dueTime))
                    val due = updated.dueDate?.let { d -> " на ${RuFormat.dateFull(d, today).removePrefix("в ")}" + (updated.dueTime?.let { " в ${RuFormat.time(it)}" } ?: "") } ?: ""
                    changed("Перенесла задачу ${RuFormat.quote(updated.title)}$due.", r.ref)
                }
                is Resolution.Ambiguous -> ambiguous(r.options, "перенести", action)
                Resolution.NotFound -> error("Не нашла такую задачу.")
            }

            AssistantAction.QueryReminders -> {
                val active = reminders.active()
                query(
                    if (active.isEmpty()) "Активных напоминаний нет."
                    else "Напоминания:\n" + active.take(15).joinToString("\n") { r ->
                        "• ${r.text} — ${RuFormat.dateTime(r.triggerAt, zone, now)}" + (r.recurrence?.let { " (${it.describeRu()})" } ?: "")
                    },
                )
            }

            is AssistantAction.Remember -> {
                val m = memories.create(action.content, action.category)
                changed("Запомнила: ${m.content}", RecordRef(RecordType.MEMORY, m.id, m.content))
            }

            is AssistantAction.ForgetMemory -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> Step(emptyList(), confirm = PendingConfirmation(
                    "Забыть ${RuFormat.quote(r.ref.title)}?", listOf(DestructiveOp(RecordType.MEMORY, r.ref.id, r.ref.title)),
                ))
                is Resolution.Ambiguous -> ambiguous(r.options, "забыть", action)
                Resolution.NotFound -> error("Такого в памяти нет.")
            }

            is AssistantAction.QueryMemories -> {
                if (action.query.isNullOrBlank()) {
                    // Вещи и долги — не «о вас»: про них спрашивают отдельно («где паспорт», «кто мне должен»).
                    val items = memories.all().filter { it.category != ai.loli.core.personal.ThingsBook.CATEGORY && it.category != ai.loli.core.personal.DebtBook.CATEGORY }
                        .take(10).map { it.content }
                    query(if (items.isEmpty()) "Пока ничего не помню о вас. Скажите, например: «запомни, что я люблю зелёный чай»." else "Вот что я помню:\n" + items.joinToString("\n") { "• $it" })
                } else {
                    val hits = search.search(action.query, setOf(RecordType.MEMORY), limit = 3, minScore = 0.3)
                    query(
                        when {
                            hits.isEmpty() -> "Вы мне об этом не рассказывали. Скажите «запомни, что…», и я запомню."
                            hits.size == 1 || hits[0].score - hits[1].score > 0.2 -> "Вы говорили: ${RuFormat.quote(hits[0].doc.title)}."
                            else -> "Вот что я помню:\n" + hits.joinToString("\n") { "• ${it.doc.title}" }
                        },
                    )
                }
            }

            is AssistantAction.Search -> {
                val hits = search.search(action.query, action.types, action.keywords, limit = 8, minScore = 0.3)
                if (hits.isEmpty()) query("Ничего не нашла по запросу ${RuFormat.quote(action.query)}.")
                else {
                    hits.firstOrNull()?.let { ctx.touchRecord(RecordRef(it.doc.type, it.doc.id, it.doc.title)) }
                    query(
                        "Нашла ${ExpenseAnalytics.plural(hits.size, "запись", "записи", "записей")}:\n" +
                            hits.joinToString("\n") { h ->
                                val snippet = h.doc.body.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)?.let { " — $it" } ?: ""
                                "• ${h.doc.type.titleRu}: ${RuFormat.quote(h.doc.title)}$snippet"
                            },
                    )
                }
            }

            is AssistantAction.Clarify -> Step(listOf(Outcome(action.question, Outcome.Kind.QUESTION)))

            is AssistantAction.Agenda -> query(agenda(action.date))

            is AssistantAction.DeleteLast -> {
                // Последним добавляли в список — убираем это (без подтверждения: пункт списка легко вернуть).
                if (action.type == null && ctx.lastListAdded.isNotEmpty() && shopping != null) {
                    val items = shopping.all().filter { it.id in ctx.lastListAdded }
                    items.forEach { shopping.delete(it.id) }
                    ctx.lastListAdded = emptyList()
                    if (items.isNotEmpty()) return changed("Убрала из списка: ${items.joinToString(", ") { it.text.lowercase() }}.")
                }
                val target = lastRecord(action.type, ctx) ?: return query("Не нашла, что удалить.")
                Step(emptyList(), confirm = PendingConfirmation(
                    "Удалить ${accusative(target.type)} ${RuFormat.quote(target.title)}?",
                    listOf(DestructiveOp(target.type, target.id, target.title, cancelOnly = false)),
                ))
            }

            is AssistantAction.Device -> {
                val r = device.perform(action.command)
                if (r.ok) query(r.text) else error(r.text)
            }

            is AssistantAction.AddToList -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val listName = listNamed(action.listName)
                val added = repo.add(listName, action.items)
                if (added.isEmpty()) return error("Не поняла, что добавить в список.")
                ctx.lastListAdded = added.map { it.id }; ctx.lastCreated = null
                val where = if (listName == ai.loli.core.model.ShoppingItem.DEFAULT_LIST) "в покупки" else "в список ${RuFormat.quote(listName)}"
                changed("Добавила $where: ${added.joinToString(", ") { it.text.lowercase() }}.")
            }

            is AssistantAction.QueryList -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val listName = listNamed(action.listName)
                val shop = listName == ai.loli.core.model.ShoppingItem.DEFAULT_LIST
                val items = repo.all().filter { it.listName.equals(listName, true) }
                val left = items.filter { !it.done }
                query(
                    when {
                        items.isEmpty() -> "Список ${RuFormat.quote(listName)} пуст."
                        left.isEmpty() -> if (shop) "Всё из списка ${RuFormat.quote(listName)} уже куплено." else "В списке ${RuFormat.quote(listName)} всё отмечено."
                        else -> "${if (shop) "Купить" else listName} (${left.size}):\n" +
                            left.joinToString("\n") { "• ${it.text}" }
                    },
                )
            }

            is AssistantAction.CheckListItem -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val stem = ai.loli.core.nlp.TextAnalysis.stems(action.item).toSet()
                val listName = listNamed(action.listName)
                val items = repo.all().filter { it.listName.equals(listName, true) && it.done != action.done }
                val hit = items.firstOrNull { it.text.equals(action.item, true) }
                    ?: items.firstOrNull { i -> ai.loli.core.nlp.TextAnalysis.stems(i.text).any { it in stem } }
                    ?: return error("В списке нет ${RuFormat.quote(action.item)}.")
                repo.setDone(hit.id, action.done)
                val left = repo.all().count { it.listName.equals(listName, true) && !it.done }
                val all = if (listName == ai.loli.core.model.ShoppingItem.DEFAULT_LIST) " Всё куплено!" else " Всё отмечено!"
                changed(if (action.done) "Вычеркнула ${RuFormat.quote(hit.text)}." + (if (left == 0) all else " Осталось: $left.") else "Вернула ${RuFormat.quote(hit.text)} в список.")
            }

            is AssistantAction.ClearList -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val n = repo.clear(listNamed(action.listName), action.onlyDone)
                changed(if (n == 0) "Убирать нечего." else "Убрала из списка ${RuFormat.count(n, "пункт", "пункта", "пунктов")}.")
            }

            is AssistantAction.CreateRoutine -> {
                val repo = routines ?: return error("Сценарии пока недоступны.")
                if (action.commands.isEmpty()) return error("Не поняла, что делать по этой фразе.")
                val r = repo.save(action.trigger, action.commands)
                val rule = action.schedule
                if (rule != null) {
                    val text = ai.loli.core.model.Routine.SCHEDULE_PREFIX + r.trigger
                    reminders.all().filter { it.text.equals(text, true) }.forEach { reminders.delete(it.id); scheduler.cancel(it.id) }
                    val rem = reminders.create(text, rule.nextAfter(now, zone, now), rule, zone.id)
                    scheduler.schedule(rem)
                    return changed("Готово! ${r.trigger} выполню сама: ${r.commands.joinToString("; ")}. Результат покажу уведомлением и скажу вслух, если включены ответы голосом.")
                }
                changed("Готово! Когда скажете ${RuFormat.quote(r.trigger)}, я выполню: ${r.commands.joinToString("; ")}.")
            }

            AssistantAction.QueryRoutines -> {
                val list = routines?.all().orEmpty()
                query(
                    if (list.isEmpty()) "Сценариев пока нет. Скажите, например: «когда я говорю спокойной ночи — поставь будильник на 7 и включи не беспокоить»."
                    else {
                        // По расписанию — те, у кого есть напоминание «Сценарий: …».
                        val timed = reminders.active().map { it.text }.filter { it.startsWith(ai.loli.core.model.Routine.SCHEDULE_PREFIX) }
                            .map { ai.loli.core.model.Routine.normalize(it.removePrefix(ai.loli.core.model.Routine.SCHEDULE_PREFIX)) }.toSet()
                        "Сценарии:\n" + list.joinToString("\n") { r ->
                            if (ai.loli.core.model.Routine.normalize(r.trigger) in timed) "• ${r.trigger} (сам) → ${r.commands.joinToString("; ")}"
                            else "• «${r.trigger}» → ${r.commands.joinToString("; ")}"
                        }
                    },
                )
            }

            is AssistantAction.DeleteRoutine -> {
                val repo = routines ?: return error("Сценарии пока недоступны.")
                val key = ai.loli.core.model.Routine.normalize(action.trigger)
                val r = repo.all().firstOrNull { ai.loli.core.model.Routine.normalize(it.trigger) == key }
                    ?: repo.all().firstOrNull { key in ai.loli.core.model.Routine.normalize(it.trigger) }
                    ?: return error("Не нашла сценарий ${RuFormat.quote(action.trigger)}.")
                repo.delete(r.id)
                val scheduled = ai.loli.core.model.Routine.SCHEDULE_PREFIX + r.trigger
                reminders.all().filter { it.text.equals(scheduled, true) }.forEach { reminders.delete(it.id); scheduler.cancel(it.id) }
                changed("Удалила сценарий ${RuFormat.quote(r.trigger)}.")
            }

            is AssistantAction.AddSubscription -> {
                val name = action.name
                removeSubscription(name)
                val freq = if (action.yearly) ai.loli.core.model.Recurrence.Frequency.YEARLY else ai.loli.core.model.Recurrence.Frequency.MONTHLY
                val rule = ai.loli.core.model.Recurrence(freq, time = java.time.LocalTime.of(9, 0), dayOfMonth = action.day, month = if (action.yearly) action.month else null)
                val price = "${ai.loli.core.nlp.Calculator.format(action.amount)} ₽"
                val r = reminders.create("Подписка $name: сегодня спишется $price", rule.nextAfter(now, zone, now), rule, zone.id)
                scheduler.schedule(r)
                val when_ = if (action.yearly) "раз в год, ${action.day} ${MONTHS_GEN[action.month - 1]}" else "раз в месяц, ${action.day} числа"
                memories.create("Подписка $name: $price ${if (action.yearly) "в год" else "в месяц"} ($when_)", SUBSCRIPTIONS)
                changed("Запомнила: подписка $name — $price, $when_. Напомню утром в день списания.")
            }

            is AssistantAction.QuerySubscriptions -> {
                val list = subscriptions()
                if (list.isEmpty()) return query("Подписок пока нет. Скажите, например: «подписка на музыку 299 рублей раз в месяц 5 числа».")
                val month = list.sumOf { if (it.yearly) it.price / 12 else it.price }
                val year = list.sumOf { if (it.yearly) it.price else it.price * 12 }
                val sum = "На подписки уходит примерно ${ai.loli.core.nlp.Calculator.format(Math.round(month * 100) / 100.0)} ₽ в месяц, ${ai.loli.core.nlp.Calculator.format(Math.round(year * 100) / 100.0)} ₽ в год."
                query(
                    if (action.total) sum
                    else "Подписки:\n" + list.joinToString("\n") { "• ${it.name} — ${ai.loli.core.nlp.Calculator.format(it.price)} ₽ ${if (it.yearly) "в год" else "в месяц"}" } + "\n" + sum,
                )
            }

            is AssistantAction.CancelSubscription -> {
                val removed = removeSubscription(action.name)
                if (removed == 0) error("Не нашла подписку ${RuFormat.quote(action.name)}.")
                else changed("Убрала подписку ${action.name} и напоминания о ней. Саму подписку нужно отменить в сервисе.")
            }

            is AssistantAction.AddBirthday -> {
                val person = action.person.trim()
                val date = runCatching { java.time.LocalDate.of(2000, action.month, action.day) }.getOrNull() ?: return error("Такой даты нет.")
                val title = "День рождения $person"
                // Дубликаты не плодим: старые напоминания об этом дне рождения заменяются.
                reminders.all().filter { it.recurrence?.frequency == ai.loli.core.model.Recurrence.Frequency.YEARLY && it.text.lowercase().contains("день рождения ${person.lowercase()}") }
                    .forEach { reminders.delete(it.id); scheduler.cancel(it.id) }
                val onDay = ai.loli.core.model.Recurrence(ai.loli.core.model.Recurrence.Frequency.YEARLY, time = java.time.LocalTime.of(9, 0), dayOfMonth = date.dayOfMonth, month = date.monthValue)
                val before = date.minusDays(1)
                val dayBefore = ai.loli.core.model.Recurrence(ai.loli.core.model.Recurrence.Frequency.YEARLY, time = java.time.LocalTime.of(19, 0), dayOfMonth = before.dayOfMonth, month = before.monthValue)
                val first = reminders.create("$title — сегодня! Поздравить?", onDay.nextAfter(now, zone, now), onDay, zone.id)
                val second = reminders.create("Завтра ${title.replaceFirstChar { it.lowercase() }}", dayBefore.nextAfter(now, zone, now), dayBefore, zone.id)
                scheduler.schedule(first); scheduler.schedule(second)
                memories.create("$title — ${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]}", "Даты")
                changed("Запомнила: $title — ${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]}. Напомню накануне вечером и в сам день.")
            }

            is AssistantAction.QueryBirthdays -> {
                val all = reminders.all().filter { it.recurrence?.frequency == ai.loli.core.model.Recurrence.Frequency.YEARLY && it.text.startsWith("День рождения") }
                val entries = all.mapNotNull { r ->
                    val rule = r.recurrence ?: return@mapNotNull null
                    val name = r.text.removePrefix("День рождения").substringBefore(" — ").trim()
                    Triple(name, rule.month ?: return@mapNotNull null, rule.dayOfMonth ?: return@mapNotNull null)
                }.let { own ->
                    val contacts = try { contactBirthdays() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
                    own + contacts.map { (n, md) -> Triple(n, md.monthValue, md.dayOfMonth) }
                }.distinctBy { it.first.lowercase() }
                if (action.soon) {
                    fun next(e: Triple<String, Int, Int>): java.time.LocalDate = runCatching {
                        val d = java.time.MonthDay.of(e.second, e.third).atYear(today.year)
                        if (d.isBefore(today)) java.time.MonthDay.of(e.second, e.third).atYear(today.year + 1) else d
                    }.getOrDefault(today.plusYears(1))
                    val soon = entries.map { it to next(it) }.filter { !it.second.isAfter(today.plusDays(30)) }.sortedBy { it.second }
                    return query(
                        if (soon.isEmpty()) {
                            if (entries.isEmpty()) "Дней рождения пока не знаю. Скажите «день рождения мамы 5 мая» или разрешите доступ к контактам."
                            else "В ближайший месяц дней рождения нет."
                        } else "Скоро дни рождения:\n" + soon.joinToString("\n") { (e, d) ->
                            val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
                            "• ${e.first} — ${e.third} ${MONTHS_GEN[e.second - 1]}" + when (days) { 0L -> " (сегодня!)"; 1L -> " (завтра)"; else -> " (через ${RuFormat.count(days.toInt(), "день", "дня", "дней")})" }
                        },
                    )
                }
                val filtered = when {
                    action.person != null -> {
                        val stems = ai.loli.core.nlp.TextAnalysis.stems(action.person).toSet()
                        entries.filter { e -> ai.loli.core.nlp.TextAnalysis.stems(e.first).any { it in stems } }
                    }
                    action.thisMonth -> entries.filter { it.second == today.monthValue }
                    else -> entries
                }.sortedWith(compareBy({ it.second }, { it.third }))
                query(
                    when {
                        filtered.isEmpty() && action.person != null -> "Не знаю, когда день рождения ${action.person}. Скажите, например: «день рождения ${action.person} 5 мая»."
                        filtered.isEmpty() -> if (action.thisMonth) "В этом месяце дней рождения нет." else "Дней рождения пока не записано."
                        filtered.size == 1 -> "День рождения ${filtered[0].first} — ${filtered[0].third} ${MONTHS_GEN[filtered[0].second - 1]}."
                        else -> "Дни рождения:\n" + filtered.joinToString("\n") { "• ${it.first} — ${it.third} ${MONTHS_GEN[it.second - 1]}" }
                    },
                )
            }

            is AssistantAction.CreateList -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val name = listNamed(action.name)
                if (action.items.isEmpty()) {
                    val q = "Завела список ${RuFormat.quote(name)}. Что в него добавить?"
                    ctx.pendingSlot = SlotRequest.ListItems(name, q)
                    return Step(listOf(Outcome(q, Outcome.Kind.QUESTION)))
                }
                val added = repo.add(name, action.items)
                ctx.lastListAdded = added.map { it.id }; ctx.lastCreated = null
                changed(
                    "Собрала список ${RuFormat.quote(name)} — ${RuFormat.count(added.size, "пункт", "пункта", "пунктов")}: " +
                        added.joinToString(", ") { it.text.lowercase() } + ". Что уже собрано, вычёркивайте: «вычеркни паспорт».",
                )
            }

            AssistantAction.QueryLists -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val groups = repo.all().groupBy { it.listName }
                query(
                    if (groups.isEmpty()) "Списков пока нет. Скажите, например: «создай список фильмов» или «собери список в отпуск»."
                    else "Ваши списки:\n" + groups.entries.joinToString("\n") { (name, items) ->
                        val left = items.count { !it.done }
                        "• $name — " + if (left == 0) "всё отмечено" else RuFormat.count(left, "пункт", "пункта", "пунктов")
                    },
                )
            }

            is AssistantAction.AddDeadline -> {
                val left = java.time.temporal.ChronoUnit.DAYS.between(today, action.date)
                if (left < 0) return error("Эта дата уже прошла: ${longDate(action.date)}.")
                deadlines.put(action.title, action.date)
                val mark = ai.loli.core.personal.DeadlineBook.REMINDER_MARK + action.title
                reminders.all().filter { it.text.startsWith(mark) }.forEach { reminders.delete(it.id); scheduler.cancel(it.id) }
                // Заранее — тем раньше, чем дальше срок; в сам день — утром.
                val before = when { left >= 45 -> 30L; left >= 8 -> 7L; left >= 2 -> 1L; else -> 0L }
                val at = java.time.LocalTime.of(10, 0)
                val planned = listOfNotNull(
                    if (before > 0) action.date.minusDays(before) to "$mark — истекает ${longDate(action.date)}" else null,
                    action.date to "$mark — истекает сегодня",
                ).mapNotNull { (d, text) ->
                    val instant = d.atTime(at).atZone(zone).toInstant()
                    if (!instant.isAfter(now)) null else reminders.create(text, instant, null, zone.id).also { scheduler.schedule(it) }
                }
                val whenText = when (before) { 30L -> "за месяц и в сам день"; 7L -> "за неделю и в сам день"; 1L -> "накануне и в сам день"; else -> "в сам день" }
                changed("Запомнила: ${action.title.replaceFirstChar { it.lowercase() }} — до ${longDate(action.date)}." + if (planned.isNotEmpty()) " Напомню $whenText." else "")
            }

            is AssistantAction.QueryDeadlines -> {
                val list = if (action.query != null) deadlines.find(action.query) else deadlines.all()
                val shown = if (action.soon) list.filter { !it.date.isAfter(today.plusDays(60)) } else list
                query(
                    when {
                        list.isEmpty() && action.query != null -> "Про ${RuFormat.quote(action.query)} сроков не записано. Скажите, например: «гарантия на телевизор до мая 2027»."
                        list.isEmpty() -> "Сроков пока нет. Скажите, например: «гарантия на телевизор до мая 2027» или «молоко до пятницы»."
                        shown.isEmpty() -> "В ближайшие два месяца ничего не истекает. Ближайшее: ${list.first().title} — ${longDate(list.first().date)}."
                        shown.size == 1 -> "${shown[0].title} — до ${longDate(shown[0].date)} (${daysLeft(shown[0].date)})."
                        else -> (if (action.soon) "Скоро истекает:\n" else "Сроки:\n") + shown.joinToString("\n") { "• ${it.title} — до ${longDate(it.date)} (${daysLeft(it.date)})" }
                    },
                )
            }

            is AssistantAction.RemoveDeadline -> {
                val removed = deadlines.remove(action.query)
                if (removed.isEmpty()) return error("Не нашла срок ${RuFormat.quote(action.query)}.")
                removed.forEach { d ->
                    val mark = ai.loli.core.personal.DeadlineBook.REMINDER_MARK + d.title
                    reminders.all().filter { it.text.startsWith(mark) }.forEach { reminders.delete(it.id); scheduler.cancel(it.id) }
                }
                changed("Убрала: ${removed.joinToString(", ") { it.title }} и напоминания о сроке.")
            }

            is AssistantAction.SendList -> {
                val repo = shopping ?: return error("Списки пока недоступны.")
                val listName = listNamed(action.listName)
                val left = repo.all().filter { it.listName.equals(listName, true) && !it.done }
                if (left.isEmpty()) return error("Список ${RuFormat.quote(listName)} пуст — отправлять нечего.")
                val head = if (listName == ai.loli.core.model.ShoppingItem.DEFAULT_LIST) "Купить" else listName
                val text = "$head: " + left.joinToString(", ") { it.text.lowercase() }
                val cmd = when {
                    action.who != null -> DeviceCommand.Message(action.who, text)
                    else -> DeviceCommand.Share(action.app.orEmpty(), text)
                }
                val r = device.perform(cmd)
                if (r.ok) query(r.text) else error(r.text)
            }

            is AssistantAction.PutThing -> {
                val content = things.put(action.item, action.place)
                changed("Запомнила: $content. Спросите «где ${ai.loli.core.personal.nominative(action.item).lowercase()}?» — подскажу.")
            }

            is AssistantAction.AddDebt -> {
                val total = debts.add(action.person, if (action.theyOwe) action.amount else -action.amount)
                val sum = ai.loli.core.personal.DebtBook.money(action.amount)
                val head = if (action.theyOwe) "Записала долг: ${action.person}, $sum — вам должны." else "Записала долг: ${action.person}, $sum — должны вы."
                val tail = when {
                    total == null -> " Теперь вы в расчёте."
                    Math.abs(total.amount) - action.amount > 0.005 || total.amount > 0 != action.theyOwe ->
                        " Всего по ${total.person}: " + ai.loli.core.personal.DebtBook.money(total.amount) + (if (total.amount > 0) " должны вам." else " должны вы.")
                    else -> ""
                }
                var remind = ""
                action.remindText?.let { raw ->
                    val parser = ai.loli.core.nlp.RuDateTimeParser()
                    val at = parser.parse(raw, today).spec.takeUnless { it.isEmpty }?.let { parser.resolveTrigger(it, now, zone) }
                    remind = if (at != null && at.isAfter(now)) {
                        val text = if (action.theyOwe) "Напомнить ${action.person} про долг $sum" else "Вернуть долг ${action.person}: $sum"
                        val r = reminders.create(text, at, null, zone.id)
                        scheduler.schedule(r)
                        " Напомню ${RuFormat.dateTime(at, zone, now)}."
                    } else " Когда напомнить, не поняла — скажите, например: «напомни через неделю про долг»."
                }
                changed(head + tail + remind)
            }

            is AssistantAction.SettleDebt -> {
                val r = debts.settle(action.person, action.amount, action.theyPaid)
                    ?: return error(
                        if (debts.find(action.person) == null) "Долгов с ${action.person} у меня не записано." else "Такого долга у ${action.person} нет — проверьте: «сколько мне должен ${action.person}».",
                    )
                val (paid, left) = r
                changed(
                    "Отметила возврат: ${ai.loli.core.personal.DebtBook.money(paid)}." +
                        if (left == null) " Долг закрыт — вы в расчёте." else " Осталось: ${ai.loli.core.personal.DebtBook.money(left.amount)}" + (if (left.amount > 0) " должны вам." else " должны вы."),
                )
            }

            is AssistantAction.QueryDebts -> {
                if (action.person != null) {
                    val d = debts.find(action.person) ?: return query("По ${action.person} долгов нет — вы в расчёте.")
                    return query("${d.person}: " + ai.loli.core.personal.DebtBook.money(d.amount) + if (d.amount > 0) " — должны вам." else " — должны вы.")
                }
                val all = debts.all()
                if (all.isEmpty()) return query("Долгов нет. Скажите, например: «Саша должен мне 500» или «я должна Маше 200».")
                val mine = all.filter { it.amount > 0 }
                val theirs = all.filter { it.amount < 0 }
                query(
                    buildString {
                        if (mine.isNotEmpty()) append("Вам должны:\n" + mine.joinToString("\n") { "• ${it.person} — ${ai.loli.core.personal.DebtBook.money(it.amount)}" })
                        if (theirs.isNotEmpty()) {
                            if (isNotEmpty()) append("\n")
                            append("Вы должны:\n" + theirs.joinToString("\n") { "• ${it.person} — ${ai.loli.core.personal.DebtBook.money(it.amount)}" })
                        }
                    },
                )
            }

            is AssistantAction.CreateSecretNote -> {
                val store = secrets ?: return error("Секретные заметки пока недоступны.")
                store.save(action.title, action.content)
                changed("Сохранила секретную заметку. Она только на этом телефоне и открывается по отпечатку.")
            }

            is AssistantAction.UpdateLastExpense -> {
                val lastId = ctx.lastCreated?.takeIf { it.type == RecordType.EXPENSE }?.id
                val e = (lastId?.let { expenses.get(it) } ?: expenses.all().maxByOrNull { it.createdAt })
                    ?: return query("Расходов пока нет — нечего исправлять.")
                val updated = expenses.update(e.copy(amountMinor = action.amountMinor ?: e.amountMinor, category = action.category ?: e.category))
                changed("Исправила: ${Money.format(updated.amountMinor, updated.currency)} — ${updated.category} (${RuFormat.date(updated.occurredOn, today)}).")
            }
        }
    }

    private fun lockedReason(action: AssistantAction): String = when (action) {
        is AssistantAction.Device -> when (action.command) {
            is DeviceCommand.Call, is DeviceCommand.Message -> "звонить и писать"
            is DeviceCommand.OpenApp -> "открывать приложения"
            else -> "это действие"
        }
        is AssistantAction.CreateNote, is AssistantAction.CreateExpense, is AssistantAction.CreateTask,
        is AssistantAction.CreateReminder, is AssistantAction.Remember, is AssistantAction.AppendNote -> "добавлять записи"
        is AssistantAction.QueryExpenses, is AssistantAction.QueryTasks, AssistantAction.QueryReminders,
        is AssistantAction.QueryMemories, is AssistantAction.Search, is AssistantAction.Agenda -> "смотреть записи"
        else -> "изменять и удалять записи"
    }

    private suspend fun appendNote(action: AssistantAction.AppendNote, ctx: ConversationContext): Step {
        var target = action.target
        var content = action.content
        if (action.splitQueryFromContent) {
            val (q, c) = splitQueryAndContent(action.content, target.types)
            target = target.copy(query = q)
            content = c
        }
        return when (val r = resolver.resolve(target, ctx.focus, ctx.recent)) {
            is Resolution.Found -> {
                val note = notes.append(r.ref.id, content) ?: return error("Запись не найдена.")
                changed("Добавила в ${RuFormat.quote(note.title)}: $content", r.ref.copy(title = note.title))
            }
            is Resolution.Ambiguous -> ambiguous(r.options, "дополнить", action.copy(target = target, content = content, splitQueryFromContent = false))
            Resolution.NotFound -> {
                val title = action.titleIfNew ?: target.query?.replaceFirstChar { it.uppercase() } ?: content.take(60)
                val kind = if (target.types == setOf(RecordType.IDEA)) NoteKind.IDEA else action.kindIfNew
                val note = notes.create(kind, title, SqlBullet.item(content))
                changed("Не нашла подходящую запись — создала новую ${RuFormat.quote(note.title)} и добавила туда: $content", RecordRef(kind.recordType, note.id, note.title))
            }
        }
    }

    /**
     * Офлайн-режим: «добавь к идее холодильника сканирование штрихкодов» — какая часть фразы описывает запись,
     * а какая — новый текст? Перебираем точки разбиения и берём ту, где начало лучше всего совпадает с записью.
     */
    private suspend fun splitQueryAndContent(text: String, types: Set<RecordType>): Pair<String?, String> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2) return null to text
        val docs = search.documents(types)
        if (docs.isEmpty()) return null to text
        var bestK = 0
        var bestScore = 0.0
        for (k in 1 until words.size) {
            val q = words.take(k).joinToString(" ")
            if (TextAnalysis.stems(q).isEmpty()) continue
            val score = docs.maxOf { search.lexicalScore(q, emptyList(), it) }
            if (score > bestScore + 0.01) { bestScore = score; bestK = k }
        }
        if (bestK == 0 || bestScore < TargetResolver.MIN_SCORE) return null to text
        return words.take(bestK).joinToString(" ") to words.drop(bestK).joinToString(" ")
    }

    private suspend fun lastRecord(type: RecordType?, ctx: ConversationContext): RecordRef? {
        if (type == null) return ctx.lastCreated?.let { ref -> ref.takeIf { exists(it) } }
        ctx.lastCreated?.takeIf { it.type == type && exists(it) }?.let { return it }
        return when (type) {
            RecordType.EXPENSE -> expenses.all().maxByOrNull { it.createdAt }?.let { RecordRef(type, it.id, "${Money.format(it.amountMinor, it.currency)} — ${it.category}") }
            RecordType.TASK -> tasks.all().maxByOrNull { it.createdAt }?.let { RecordRef(type, it.id, it.title) }
            RecordType.NOTE, RecordType.IDEA -> notes.all(if (type == RecordType.IDEA) NoteKind.IDEA else NoteKind.NOTE).maxByOrNull { it.createdAt }?.let { RecordRef(type, it.id, it.title) }
            RecordType.REMINDER -> reminders.all().filter { it.active }.maxByOrNull { it.createdAt }?.let { RecordRef(type, it.id, it.text) }
            RecordType.MEMORY -> memories.all().maxByOrNull { it.createdAt }?.let { RecordRef(type, it.id, it.content) }
        }
    }

    private suspend fun exists(ref: RecordRef): Boolean = when (ref.type) {
        RecordType.EXPENSE -> expenses.get(ref.id) != null
        RecordType.TASK -> tasks.get(ref.id) != null
        RecordType.NOTE, RecordType.IDEA -> notes.get(ref.id) != null
        RecordType.REMINDER -> reminders.get(ref.id) != null
        RecordType.MEMORY -> memories.get(ref.id) != null
    }

    /** Сводка дня: задачи (и просроченные — для сегодня), напоминания, расходы. */
    private suspend fun agenda(date: java.time.LocalDate): String {
        val today = time.today()
        val zone = time.zone()
        val now = time.now()
        val nowTime = time.zonedNow().toLocalTime()
        val all = tasks.all()
        val dayTasks = all.filter { !it.done && it.dueDate == date }
        val overdue = if (date == today) all.filter { it.isOverdue(today, nowTime) && it.dueDate != today } else emptyList()
        val dayReminders = reminders.active().filter { it.triggerAt.atZone(zone).toLocalDate() == date }
        val spent = if (!date.isAfter(today)) expenses.between(date, date) else emptyList()
        val dayName = RuFormat.date(date, today).removePrefix("в ")
        val extras = try { agendaExtras(date) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ai.loli.core.skills.AgendaExtras() }
        val weather = (extras.weather?.let { "$it\n" } ?: "") +
            (if (extras.birthdays.isNotEmpty()) "${if (date == today) "Сегодня" else "В этот день"} день рождения: ${extras.birthdays.joinToString(", ")} — можно сказать «поздравь ${extras.birthdays.first().substringBefore(' ')}».\n" else "")
        if (dayTasks.isEmpty() && overdue.isEmpty() && dayReminders.isEmpty() && spent.isEmpty() && extras.events.isEmpty()) {
            val undated = all.count { !it.done && it.dueDate == null }
            return weather + "На $dayName ничего не запланировано." + if (undated > 0) " Задач без срока: $undated." else ""
        }
        val sb = StringBuilder(weather + "План на $dayName:")
        if (extras.events.isNotEmpty()) sb.append("\nКалендарь:\n" + extras.events.joinToString("\n") { "• $it" })
        if (dayTasks.isNotEmpty()) sb.append("\nЗадачи:\n" + dayTasks.joinToString("\n") { t -> "• ${t.title}" + (t.dueTime?.let { " в ${RuFormat.time(it)}" } ?: "") })
        if (overdue.isNotEmpty()) sb.append("\nПросрочено:\n" + overdue.joinToString("\n") { "! ${it.title}" })
        if (dayReminders.isNotEmpty()) sb.append("\nНапоминания:\n" + dayReminders.joinToString("\n") { "• ${it.text} — ${RuFormat.time(it.triggerAt.atZone(zone).toLocalTime())}" })
        if (spent.isNotEmpty()) {
            val sum = spent.groupBy { it.currency }.entries.joinToString(", ") { (c, l) -> Money.format(l.sumOf { it.amountMinor }, c) }
            sb.append("\nПотрачено: $sum")
        }
        return sb.toString()
    }

    private fun describeTasks(all: List<TaskItem>, filter: TaskFilter): String {
        val today = time.today()
        val nowTime = time.zonedNow().toLocalTime()
        val (title, list) = when (filter) {
            TaskFilter.TODAY -> "Задачи на сегодня" to all.filter { !it.done && (it.dueDate == today || it.isOverdue(today, nowTime)) }
            TaskFilter.TOMORROW -> "Задачи на завтра" to all.filter { !it.done && it.dueDate == today.plusDays(1) }
            TaskFilter.WEEK -> "Задачи на неделю" to all.filter { !it.done && it.dueDate != null && !it.dueDate.isAfter(today.plusDays(7)) }
            TaskFilter.OVERDUE -> "Просроченные задачи" to all.filter { it.isOverdue(today, nowTime) }
            TaskFilter.ACTIVE -> "Активные задачи" to all.filter { !it.done }
            TaskFilter.COMPLETED -> "Выполненные задачи" to all.filter { it.done }
            TaskFilter.ALL -> "Все задачи" to all
        }
        if (list.isEmpty()) return when (filter) {
            TaskFilter.OVERDUE -> "Просроченных задач нет."
            TaskFilter.TODAY -> "На сегодня задач нет."
            TaskFilter.COMPLETED -> "Выполненных задач пока нет."
            else -> "Задач нет."
        }
        return "$title (${list.size}):\n" + list.take(20).joinToString("\n") { t ->
            val due = t.dueDate?.let { d -> " — " + RuFormat.date(d, today) + (t.dueTime?.let { " ${RuFormat.time(it)}" } ?: "") } ?: ""
            val mark = if (t.done) "✓" else if (t.isOverdue(today, nowTime)) "!" else "•"
            "$mark ${t.title}$due"
        }
    }
}

/** Формат пункта при добавлении в заметку (общий для репозитория и исполнителя). */
internal object SqlBullet {
    fun item(text: String) = ai.loli.core.data.SqlNoteRepository.appendLine("", text)
}

private const val SUBSCRIPTIONS = "Подписки"
private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
