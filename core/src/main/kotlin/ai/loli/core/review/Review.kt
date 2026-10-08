package ai.loli.core.review

import ai.loli.core.assistant.RuFormat
import ai.loli.core.domain.NoteRepository
import ai.loli.core.domain.ReminderRepository
import ai.loli.core.domain.TaskRepository
import ai.loli.core.health.HabitEntry
import ai.loli.core.health.Habits
import ai.loli.core.model.Expense
import ai.loli.core.model.NoteKind
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.util.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** «Итоги недели» и «что я делала 5 октября»: сводка по записям за период. Только чтение. */
class Review(
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val habitLog: suspend (Instant, Instant) -> List<HabitEntry>,
    private val expenses: suspend (LocalDate, LocalDate) -> List<Expense>,
    private val time: TimeSource,
) {
    sealed interface Ask {
        data object Week : Ask
        data class Day(val date: LocalDate) : Ask
    }

    companion object {
        private val WEEK = Rx.of("""^(?:итоги\s+недели|подведи\s+итоги\s+недели|как\s+прошла\s+(?:моя\s+|эта\s+)?неделя|неделя\s+в\s+цифрах|сводка\s+за\s+неделю|обзор\s+недели|что\s+(?:я\s+)?(?:сделал[аи]?|успел[аи]?)\s+за\s+неделю)$""")
        private val DAY = Rx.of("""^(?:а\s+)?(?:что\s+(?:я\s+)?(?:делал|делала|делали|успел|успела|успели\s+сделать|успела\s+сделать|успел\s+сделать)|что\s+(?:у\s+меня\s+)?было|что\s+происходило|чем\s+я\s+занимал\p{L}*|лента(?:\s+дня)?(?:\s+за)?|хроника(?:\s+дня)?(?:\s+за)?|мой\s+день)\s+(.+)$""")

        fun parse(text: String, today: LocalDate): Ask? {
            val n = RuTokenizer.normalize(text).trim().trimEnd('?', '.', '!')
            if (WEEK.matches(n)) return Ask.Week
            val m = DAY.find(n) ?: return null
            val rest = m.groupValues[1].trim()
            val parsed = RuDateTimeParser().parse(rest, today)
            var date = parsed.spec.date ?: return null
            // «Что я делала 5 сентября / в понедельник» — о прошлом, а разбор дат выбирает ближайшее будущее.
            if (date.isAfter(today) && !Rx.of("""завтра|через|следующ|будущ""").containsMatchIn(rest)) {
                date = when {
                    Rx.of("""понедельник|вторник|сред[уа]|четверг|пятниц|суббот|воскресень""").containsMatchIn(rest) -> date.minusWeeks(1)
                    !Regex("""\d{4}""").containsMatchIn(rest) -> date.minusYears(1)
                    else -> date
                }
            }
            // Лишние слова после даты — это уже другой вопрос («что было вчера на работе»).
            if (parsed.remainder.isNotBlank() || parsed.spec.time != null) return null
            return Ask.Day(date)
        }
    }

    private fun spent(list: List<Expense>): Long = list.filter { it.category != ExpenseCategories.INCOME && it.currency == "RUB" }.sumOf { it.amountMinor }

    private fun rub(minor: Long) = Money.format(minor, "RUB")

    private fun fmt(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(Locale("ru"), "%.1f", x)

    private fun trend(now: Double, before: Double, more: String, less: String): String = when {
        before <= 0.0 -> ""
        now > before * 1.1 -> " ($more, чем на прошлой неделе: было ${fmt(before)})"
        now < before * 0.9 -> " ($less, чем на прошлой неделе: было ${fmt(before)})"
        else -> " (как на прошлой неделе)"
    }

    /** Итоги последних 7 дней и сравнение с предыдущими семью. */
    suspend fun week(): String {
        val zone = time.zone()
        val today = time.today()
        val from = today.minusDays(6)
        val prevFrom = from.minusDays(7)
        fun inst(d: LocalDate) = d.atStartOfDay(zone).toInstant()
        val end = inst(today.plusDays(1))

        val spentNow = expenses(from, today)
        val spentBefore = expenses(prevFrom, from.minusDays(1))
        val allTasks = tasks.all()
        fun doneIn(a: Instant, b: Instant) = allTasks.count { t -> t.completedAt?.let { !it.isBefore(a) && it.isBefore(b) } == true }
        val doneNow = doneIn(inst(from), end)
        val doneBefore = doneIn(inst(prevFrom), inst(from))
        val log = habitLog(inst(prevFrom), end)
        val (logNow, logBefore) = log.partition { !it.at.isBefore(inst(from)) }
        val moodNow = logNow.filter { it.kind == Habits.MOOD }.map { it.amount }
        val moodBefore = logBefore.filter { it.kind == Habits.MOOD }.map { it.amount }
        val waterDays = logNow.filter { it.kind == Habits.WATER }.groupBy { it.at.atZone(zone).toLocalDate() }
        val habits = logNow.filter { it.kind == Habits.HABIT }.groupBy { it.name.lowercase() }

        val parts = ArrayList<String>()
        val sumNow = spent(spentNow)
        val sumBefore = spent(spentBefore)
        if (sumNow > 0) {
            val top = spentNow.filter { it.category != ExpenseCategories.INCOME && it.currency == "RUB" }.groupBy { it.category }
                .mapValues { (_, l) -> l.sumOf { it.amountMinor } }.maxByOrNull { it.value }
            val cmp = when {
                sumBefore <= 0 -> ""
                sumNow > sumBefore * 11 / 10 -> " — больше, чем на прошлой неделе (${rub(sumBefore)})"
                sumNow < sumBefore * 9 / 10 -> " — меньше, чем на прошлой неделе (${rub(sumBefore)})"
                else -> " — примерно как на прошлой неделе"
            }
            parts += "Потрачено ${rub(sumNow)}$cmp." + (top?.let { " Больше всего — ${it.key.lowercase()}: ${rub(it.value)}." } ?: "")
        } else parts += "Трат за неделю не записано."
        parts += (if (doneNow > 0) "Закрыто задач: $doneNow" else "Закрытых задач нет") + trend(doneNow.toDouble(), doneBefore.toDouble(), "больше", "меньше") + "."
        if (moodNow.isNotEmpty()) {
            val avg = Math.round(moodNow.average() * 10) / 10.0
            parts += "Среднее настроение — ${fmt(avg)} из 5" + (if (moodBefore.isNotEmpty()) trend(avg, Math.round(moodBefore.average() * 10) / 10.0, "лучше", "хуже") else "") + "."
        }
        if (waterDays.isNotEmpty()) {
            val avg = waterDays.values.map { d -> d.sumOf { it.amount } }.average()
            parts += "Воды в среднем ${fmt(Math.round(avg * 10) / 10.0)} стакана в день."
        }
        if (habits.isNotEmpty()) {
            parts += "Привычки: " + habits.entries.joinToString(", ") { (name, l) ->
                "$name — ${RuFormat.count(l.map { it.at.atZone(zone).toLocalDate() }.distinct().size, "день", "дня", "дней")}"
            } + "."
        }
        val good = moodNow.isNotEmpty() && moodNow.average() >= 4 || doneNow > doneBefore && doneNow >= 3
        val tough = moodNow.size >= 2 && moodNow.average() <= 2.5
        parts += when {
            tough -> "Неделя была непростой — на следующей постарайтесь выкроить время на отдых."
            good -> "Хорошая неделя!"
            else -> "Хорошей новой недели!"
        }
        return "Итоги недели. " + parts.joinToString(" ")
    }

    /** Что было в этот день: записи, траты, дела, настроение. */
    suspend fun day(date: LocalDate): String {
        val zone = time.zone()
        val today = time.today()
        val a = date.atStartOfDay(zone).toInstant()
        val b = date.plusDays(1).atStartOfDay(zone).toInstant()
        fun inDay(i: Instant?) = i != null && !i.isBefore(a) && i.isBefore(b)
        val title = when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> "${date.dayOfMonth} ${genitive(date)}, ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))}"
        }
        val lines = ArrayList<String>()
        val dayNotes = notes.all().filter { inDay(it.createdAt) }
        val ideas = dayNotes.filter { it.kind == NoteKind.IDEA }
        val plain = dayNotes.filter { it.kind != NoteKind.IDEA }
        if (plain.isNotEmpty()) lines += "Заметки: " + plain.take(5).joinToString(", ") { RuFormat.quote(it.title) } + if (plain.size > 5) " и ещё ${plain.size - 5}" else ""
        if (ideas.isNotEmpty()) lines += "Идеи: " + ideas.take(5).joinToString(", ") { RuFormat.quote(it.title) }
        val allTasks = tasks.all()
        val done = allTasks.filter { inDay(it.completedAt) }
        val added = allTasks.filter { inDay(it.createdAt) && !inDay(it.completedAt) }
        if (done.isNotEmpty()) lines += "Сделано: " + done.take(6).joinToString(", ") { it.title } + if (done.size > 6) " и ещё ${done.size - 6}" else ""
        if (added.isNotEmpty()) lines += "Новые задачи: " + added.take(5).joinToString(", ") { it.title }
        val fired = reminders.all().filter { inDay(it.lastFiredAt) }
        if (fired.isNotEmpty()) lines += "Напоминания: " + fired.take(5).joinToString(", ") { it.text }
        val spent = expenses(date, date).filter { it.category != ExpenseCategories.INCOME }
        if (spent.isNotEmpty()) {
            lines += "Траты: " + spent.groupBy { it.currency }.entries.joinToString(", ") { (c, l) -> Money.format(l.sumOf { it.amountMinor }, c) } +
                " — " + spent.groupBy { it.category }.keys.joinToString(", ") { it.lowercase() }
        }
        val log = habitLog(a, b)
        log.lastOrNull { it.kind == Habits.MOOD }?.let { m -> lines += "Настроение: ${m.amount.toInt()} из 5" + (if (m.name.isNotBlank()) " — ${m.name}" else "") }
        val water = log.filter { it.kind == Habits.WATER }.sumOf { it.amount }
        if (water > 0) lines += "Вода: ${fmt(water)} стак."
        val habits = log.filter { it.kind == Habits.HABIT }.map { it.name }.distinct()
        if (habits.isNotEmpty()) lines += "Привычки: " + habits.joinToString(", ")
        val pills = log.filter { it.kind == Habits.PILL }.map { it.name }.distinct()
        if (pills.isNotEmpty()) lines += "Лекарства: " + pills.joinToString(", ")
        if (lines.isEmpty()) return "$title — ничего не записано. Лента дня собирается из заметок, задач, трат и дневника."
        return "$title:\n" + lines.joinToString("\n") { "• $it" }
    }

    private fun genitive(d: LocalDate): String = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )[d.monthValue - 1]
}
