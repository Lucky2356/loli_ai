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
) {
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
                step.created?.let { context.lastCreated = it } ?: step.record?.let { context.lastCreated = it }
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

    private fun changed(text: String, record: RecordRef? = null) = Step(listOf(Outcome(text, Outcome.Kind.CHANGED, record)), record)
    private fun query(text: String) = Step(listOf(Outcome(text, Outcome.Kind.QUERY)))
    private fun error(text: String) = Step(listOf(Outcome(text, Outcome.Kind.ERROR)))

    private fun ambiguous(options: List<RecordRef>, verb: String, action: AssistantAction): Step {
        val list = options.mapIndexed { i, o -> "${i + 1}) ${o.type.titleRu.lowercase()} ${RuFormat.quote(o.title)}" }.joinToString(", ")
        val question = "Нашла несколько подходящих записей: $list. Какую $verb?"
        return Step(listOf(Outcome(question, Outcome.Kind.QUESTION)), choice = PendingChoice(question, options, action))
    }

    private suspend fun runAction(action: AssistantAction, ctx: ConversationContext): Step {
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
                    "Удалить ${r.ref.type.titleRu.lowercase()} ${RuFormat.quote(r.ref.title)}?", listOf(DestructiveOp(r.ref.type, r.ref.id, r.ref.title)),
                ))
                is Resolution.Ambiguous -> ambiguous(r.options, "удалить", action)
                Resolution.NotFound -> error("Не нашла такую запись.")
            }

            is AssistantAction.CreateExpense -> {
                val e = expenses.create(action.amountMinor, action.currency, action.category, action.description, action.date)
                val whenText = if (e.occurredOn == today) "" else " (${RuFormat.date(e.occurredOn, today)})"
                val desc = if (e.description.isNotBlank() && !e.description.equals(e.category, true)) ", ${e.description}" else ""
                Step(
                    listOf(Outcome("Записала расход: ${Money.format(e.amountMinor, e.currency)} — ${e.category}$desc$whenText.", Outcome.Kind.CHANGED)),
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
                val due = t.dueDate?.let { d -> " на ${RuFormat.date(d, today)}" + (t.dueTime?.let { " в ${RuFormat.time(it)}" } ?: "") } ?: ""
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
                val whenText = RuFormat.dateTime(reminder.triggerAt, zone, now)
                val repeat = action.recurrence?.let { " Повтор: ${it.describeRu()}." } ?: ""
                changed("Напомню ${RuFormat.quote(reminder.text)} $whenText.$repeat", RecordRef(RecordType.REMINDER, reminder.id, reminder.text))
            }

            is AssistantAction.CancelReminder -> when (val r = resolver.resolve(action.target, ctx.focus, ctx.recent)) {
                is Resolution.Found -> Step(emptyList(), confirm = PendingConfirmation(
                    "Отменить напоминание ${RuFormat.quote(r.ref.title)}?", listOf(DestructiveOp(RecordType.REMINDER, r.ref.id, r.ref.title, cancelOnly = true)),
                ))
                is Resolution.Ambiguous -> ambiguous(r.options, "отменить", action)
                Resolution.NotFound -> error("Не нашла такое напоминание.")
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
                    val items = memories.all().take(10).map { it.content }
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
                val target = lastRecord(action.type, ctx) ?: return query("Не нашла, что удалить.")
                Step(emptyList(), confirm = PendingConfirmation(
                    "Удалить ${target.type.titleRu.lowercase()} ${RuFormat.quote(target.title)}?",
                    listOf(DestructiveOp(target.type, target.id, target.title, cancelOnly = false)),
                ))
            }

            is AssistantAction.Device -> {
                val r = device.perform(action.command)
                if (r.ok) query(r.text) else error(r.text)
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
        val dayName = RuFormat.date(date, today)
        if (dayTasks.isEmpty() && overdue.isEmpty() && dayReminders.isEmpty() && spent.isEmpty()) {
            val undated = all.count { !it.done && it.dueDate == null }
            return "На $dayName ничего не запланировано." + if (undated > 0) " Задач без срока: $undated." else ""
        }
        val sb = StringBuilder("План на $dayName:")
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
