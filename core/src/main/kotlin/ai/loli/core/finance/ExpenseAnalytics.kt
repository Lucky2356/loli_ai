package ai.loli.core.finance

import ai.loli.core.model.Expense
import ai.loli.core.nlp.Money
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PeriodPreset(val wire: String) {
    TODAY("today"), YESTERDAY("yesterday"), THIS_WEEK("this_week"), LAST_7_DAYS("last_7_days"),
    THIS_MONTH("this_month"), LAST_MONTH("last_month"), LAST_30_DAYS("last_30_days"), THIS_YEAR("this_year"), ALL("all");

    companion object {
        fun fromWire(v: String?): PeriodPreset? = entries.firstOrNull { it.wire == v?.lowercase() }
    }
}

data class DateRange(val from: LocalDate, val to: LocalDate, val label: String)

enum class ReportMode(val wire: String) {
    TOTAL("total"), BY_CATEGORY("by_category"), TOP("top"), LIST("list"), AVERAGE("average");

    companion object {
        fun fromWire(v: String?): ReportMode = entries.firstOrNull { it.wire == v?.lowercase() } ?: TOTAL
    }
}

data class CategorySum(val category: String, val amountMinor: Long)

data class ExpenseReport(
    val range: DateRange,
    val category: String?,
    val currency: String,
    val totalMinor: Long,
    val count: Int,
    val byCategory: List<CategorySum>,
    val top: List<Expense>,
    val items: List<Expense>,
    val otherCurrencies: Map<String, Long>,
)

/** Аналитика расходов: периоды, суммы, разбивка по категориям, крупнейшие траты. */
object ExpenseAnalytics {
    private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

    fun range(preset: PeriodPreset, today: LocalDate): DateRange = when (preset) {
        PeriodPreset.TODAY -> DateRange(today, today, "сегодня")
        PeriodPreset.YESTERDAY -> today.minusDays(1).let { DateRange(it, it, "вчера") }
        PeriodPreset.THIS_WEEK -> DateRange(today.with(DayOfWeek.MONDAY), today, "на этой неделе")
        PeriodPreset.LAST_7_DAYS -> DateRange(today.minusDays(6), today, "за последние 7 дней")
        PeriodPreset.THIS_MONTH -> DateRange(today.withDayOfMonth(1), today, "в этом месяце")
        PeriodPreset.LAST_MONTH -> today.minusMonths(1).let { DateRange(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth()), "в прошлом месяце") }
        PeriodPreset.LAST_30_DAYS -> DateRange(today.minusDays(29), today, "за последние 30 дней")
        PeriodPreset.THIS_YEAR -> DateRange(today.withDayOfYear(1), today, "в этом году")
        PeriodPreset.ALL -> DateRange(LocalDate.of(2000, 1, 1), today, "за всё время")
    }

    fun customRange(from: LocalDate, to: LocalDate): DateRange {
        val (a, b) = if (from.isAfter(to)) to to from else from to to
        val label = if (a == b) dayFmt.format(a) else "с ${dayFmt.format(a)} по ${dayFmt.format(b)}"
        return DateRange(a, b, label)
    }

    fun report(expenses: List<Expense>, range: DateRange, category: String? = null, preferredCurrency: String = "RUB"): ExpenseReport {
        val inRange = expenses.filter { !it.occurredOn.isBefore(range.from) && !it.occurredOn.isAfter(range.to) }
            .filter { category == null || matchesCategory(it, category) }
        val byCurrency = inRange.groupBy { it.currency }
        val main = if (byCurrency.containsKey(preferredCurrency) || byCurrency.isEmpty()) preferredCurrency
        else byCurrency.maxBy { it.value.size }.key
        val mainItems = byCurrency[main].orEmpty()
        return ExpenseReport(
            range = range,
            category = category,
            currency = main,
            totalMinor = mainItems.sumOf { it.amountMinor },
            count = inRange.size,
            byCategory = mainItems.groupBy { it.category }.map { (c, l) -> CategorySum(c, l.sumOf { it.amountMinor }) }
                .sortedByDescending { it.amountMinor },
            top = mainItems.sortedByDescending { it.amountMinor }.take(5),
            items = inRange.sortedWith(compareByDescending<Expense> { it.occurredOn }.thenByDescending { it.createdAt }),
            otherCurrencies = byCurrency.filterKeys { it != main }.mapValues { (_, l) -> l.sumOf { it.amountMinor } },
        )
    }

    fun matchesCategory(e: Expense, category: String): Boolean {
        val c = category.lowercase().trim()
        if (c.isEmpty()) return true
        val stem = c.take(maxOf(4, c.length - 2))
        return e.category.lowercase().contains(stem) || e.description.lowercase().contains(stem)
    }

    /** Текст ответа на русском для голоса и чата. */
    fun describe(report: ExpenseReport, mode: ReportMode): String {
        val period = report.range.label
        val cat = report.category?.let { " на «$it»" } ?: ""
        if (report.count == 0) return "Расходов$cat $period не нашла."
        val total = Money.format(report.totalMinor, report.currency)
        val others = report.otherCurrencies.entries.joinToString(", ") { Money.format(it.value, it.key) }
            .let { if (it.isEmpty()) "" else " (и ещё $it)" }
        val sb = StringBuilder()
        when (mode) {
            ReportMode.TOTAL, ReportMode.BY_CATEGORY -> {
                sb.append("${period.replaceFirstChar { it.uppercase() }} вы потратили$cat $total$others — ${plural(report.count, "операция", "операции", "операций")}.")
                if (report.category == null && report.byCategory.size > 1) {
                    val cats = report.byCategory.take(if (mode == ReportMode.BY_CATEGORY) 8 else 3)
                        .joinToString(", ") { "${it.category} — ${Money.format(it.amountMinor, report.currency)}" }
                    sb.append(if (mode == ReportMode.BY_CATEGORY) " По категориям: $cats." else " Больше всего: $cats.")
                }
            }
            ReportMode.TOP -> {
                sb.append("Самые большие расходы $period$cat: ")
                sb.append(report.top.joinToString("; ") { "${Money.format(it.amountMinor, it.currency)} — ${label(it)} (${dayFmt.format(it.occurredOn)})" })
                sb.append(". Всего $total.")
            }
            ReportMode.AVERAGE -> {
                val days = (java.time.temporal.ChronoUnit.DAYS.between(report.range.from, report.range.to) + 1).coerceAtLeast(1)
                val firstDay = report.items.minOfOrNull { it.occurredOn }
                val effectiveDays = if (report.range.from.year <= 2000 && firstDay != null) {
                    (java.time.temporal.ChronoUnit.DAYS.between(firstDay, report.range.to) + 1).coerceAtLeast(1)
                } else days
                sb.append("В среднем ${Money.format(report.totalMinor / effectiveDays, report.currency)} в день $period$cat (всего $total за ${plural(effectiveDays.toInt(), "день", "дня", "дней")}).")
            }
            ReportMode.LIST -> {
                sb.append("Расходы $period$cat, всего $total$others:\n")
                sb.append(report.items.take(15).joinToString("\n") { "• ${dayFmt.format(it.occurredOn)}: ${Money.format(it.amountMinor, it.currency)} — ${label(it)}" })
                if (report.items.size > 15) sb.append("\n…и ещё ${report.items.size - 15}.")
            }
        }
        return sb.toString()
    }

    private fun label(e: Expense) = if (e.description.isNotBlank() && !e.description.equals(e.category, true)) "${e.category}, ${e.description}" else e.category

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $word"
    }
}
