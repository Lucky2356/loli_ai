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
}
