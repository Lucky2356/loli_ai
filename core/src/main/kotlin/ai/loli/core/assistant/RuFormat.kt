package ai.loli.core.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Форматирование дат и времени по-русски для ответов ассистента. */
object RuFormat {
    private val ru = Locale("ru")
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", ru)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", ru)

    fun date(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        today.plusDays(2) -> "послезавтра"
        today.minusDays(1) -> "вчера"
        else -> when {
            date.isAfter(today) && date.isBefore(today.plusDays(7)) -> "${weekdayAcc(date)}, ${dayMonth.format(date)}"
            date.year == today.year -> dayMonth.format(date)
            else -> dayMonthYear.format(date)
        }
    }

    /** «завтра, 26 сентября» — относительное слово и точная дата, чтобы не было сомнений. */
    fun dateFull(date: LocalDate, today: LocalDate): String {
        val rel = date(date, today)
        return if (rel in setOf("сегодня", "завтра", "послезавтра", "вчера")) "$rel, ${dayMonth.format(date)}" else rel
    }

    fun time(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    fun dateTime(instant: Instant, zone: ZoneId, now: Instant): String {
        val z = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return "${date(z.toLocalDate(), today)} в ${time(z.toLocalTime())}"
    }

    private fun weekdayAcc(date: LocalDate): String = when (weekday.format(date)) {
        "среда" -> "в среду"; "пятница" -> "в пятницу"; "суббота" -> "в субботу"
        "понедельник" -> "в понедельник"; "вторник" -> "во вторник"; "четверг" -> "в четверг"; "воскресенье" -> "в воскресенье"
        else -> weekday.format(date)
    }

    fun quote(s: String): String = "«${s.trim().take(80)}»"

    /** Форма слова для числа: plural(1, "задача", "задачи", "задач") → «задача»; 2 → «задачи»; 5 → «задач». */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = Math.floorMod(n, 10L); val m100 = Math.floorMod(n, 100L)
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    fun plural(n: Int, one: String, few: String, many: String): String = plural(n.toLong(), one, few, many)

    /** «5 задач», «1 задача», «22 задачи». */
    fun count(n: Int, one: String, few: String, many: String): String = "$n ${plural(n, one, few, many)}"
}
