package ai.loli.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Правило повторения напоминания (упрощённый RRULE).
 * Сериализуется в строку вида `FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE;TIME=09:00` —
 * тот же формат хранится в Supabase и будет понятен Windows-клиенту.
 */
data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val time: LocalTime? = null,
    val dayOfMonth: Int? = null,
    /** Месяц для ежегодного правила (дни рождения, годовщины). */
    val month: Int? = null,
) {
    enum class Frequency { HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY }

    init {
        require(interval in 1..366) { "interval out of range" }
        require(dayOfMonth == null || dayOfMonth in 1..31) { "dayOfMonth out of range" }
        require(month == null || month in 1..12) { "month out of range" }
    }

    fun encode(): String = buildList {
        add("FREQ=${frequency.name}")
        add("INTERVAL=$interval")
        if (daysOfWeek.isNotEmpty()) add("BYDAY=" + daysOfWeek.sorted().joinToString(",") { DAY_CODES.getValue(it) })
        if (month != null) add("BYMONTH=$month")
        if (dayOfMonth != null) add("BYMONTHDAY=$dayOfMonth")
        if (time != null) add("TIME=%02d:%02d".format(time.hour, time.minute))
    }.joinToString(";")

    /** Следующее срабатывание строго после [after] в часовом поясе [zone]. */
    fun nextAfter(after: Instant, zone: ZoneId, anchor: Instant = after): Instant {
        val base = after.atZone(zone)
        return when (frequency) {
            Frequency.HOURLY -> {
                val anchorZ = anchor.atZone(zone)
                var candidate = anchorZ
                if (!candidate.isAfter(base)) {
                    val hours = ChronoUnit.HOURS.between(anchorZ, base)
                    val steps = hours / interval + 1
                    candidate = anchorZ.plusHours(steps * interval)
                    while (!candidate.isAfter(base)) candidate = candidate.plusHours(interval.toLong())
                }
                candidate.toInstant()
            }
            Frequency.DAILY -> {
                val t = time ?: anchor.atZone(zone).toLocalTime()
                var date = base.toLocalDate()
                var candidate = at(date, t, zone)
                if (!candidate.isAfter(base)) {
                    date = date.plusDays(1)
                    candidate = at(date, t, zone)
                }
                if (interval > 1) {
                    val anchorDate = anchor.atZone(zone).toLocalDate()
                    while (ChronoUnit.DAYS.between(anchorDate, candidate.toLocalDate()).mod(interval.toLong()) != 0L) {
                        candidate = at(candidate.toLocalDate().plusDays(1), t, zone)
                    }
                }
                candidate.toInstant()
            }
            Frequency.WEEKLY -> {
                val t = time ?: anchor.atZone(zone).toLocalTime()
                val days = daysOfWeek.ifEmpty { setOf(anchor.atZone(zone).dayOfWeek) }
                val anchorWeekStart = anchor.atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
                var date = base.toLocalDate()
                repeat(7 * interval + 8) {
                    val candidate = at(date, t, zone)
                    val weeks = ChronoUnit.WEEKS.between(anchorWeekStart, date.with(DayOfWeek.MONDAY))
                    if (date.dayOfWeek in days && candidate.isAfter(base) && weeks.mod(interval.toLong()) == 0L) {
                        return candidate.toInstant()
                    }
                    date = date.plusDays(1)
                }
                // Недостижимо для корректных правил, но не падаем.
                at(base.toLocalDate().plusWeeks(interval.toLong()), t, zone).toInstant()
            }
            Frequency.MONTHLY -> {
                val anchorZ = anchor.atZone(zone)
                val t = time ?: anchorZ.toLocalTime()
                val dom = dayOfMonth ?: anchorZ.dayOfMonth
                var month = base.toLocalDate().withDayOfMonth(1)
                repeat(24 * interval + 2) {
                    val day = minOf(dom, month.lengthOfMonth())
                    val candidate = at(month.withDayOfMonth(day), t, zone)
                    val months = ChronoUnit.MONTHS.between(anchorZ.toLocalDate().withDayOfMonth(1), month)
                    if (candidate.isAfter(base) && months.mod(interval.toLong()) == 0L) return candidate.toInstant()
                    month = month.plusMonths(1)
                }
                at(base.toLocalDate().plusMonths(interval.toLong()), t, zone).toInstant()
            }
            Frequency.YEARLY -> {
                val anchorZ = anchor.atZone(zone)
                val t = time ?: anchorZ.toLocalTime()
                val m = month ?: anchorZ.monthValue
                val dom = dayOfMonth ?: anchorZ.dayOfMonth
                var year = base.year
                repeat(interval + 2) {
                    val ym = java.time.YearMonth.of(year, m)
                    val candidate = at(ym.atDay(minOf(dom, ym.lengthOfMonth())), t, zone)
                    if (candidate.isAfter(base) && (year - anchorZ.year).mod(interval) == 0) return candidate.toInstant()
                    year++
                }
                at(base.toLocalDate().plusYears(interval.toLong()), t, zone).toInstant()
            }
        }
    }

    /** Описание на русском для UI и голосового ответа. */
    fun describeRu(): String {
        val timePart = time?.let { " в %02d:%02d".format(it.hour, it.minute) } ?: ""
        return when (frequency) {
            Frequency.HOURLY -> if (interval == 1) "каждый час" else "каждые $interval ч."
            Frequency.DAILY -> (if (interval == 1) "каждый день" else "каждые $interval дн.") + timePart
            Frequency.WEEKLY -> {
                val days = daysOfWeek.sorted()
                val dayText = when {
                    days.isEmpty() -> "каждую неделю"
                    days.size == 5 && days.none { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY } -> "по будням"
                    days.size == 2 && days.containsAll(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) -> "по выходным"
                    else -> days.joinToString(", ") { EVERY_DAY_RU.getValue(it) }
                }
                (if (interval > 1) "раз в $interval нед., " else "") + dayText + timePart
            }
            Frequency.MONTHLY -> (if (interval == 1) "каждый месяц" else "каждые $interval мес.") +
                (dayOfMonth?.let { " $it-го числа" } ?: "") + timePart
            Frequency.YEARLY -> (if (interval == 1) "каждый год" else "раз в $interval г.") +
                (if (month != null && dayOfMonth != null) " $dayOfMonth ${MONTHS_GEN[month - 1]}" else "") + timePart
        }
    }

    companion object {
        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
        private val CODE_DAYS = DAY_CODES.entries.associate { (k, v) -> v to k }
        private val EVERY_DAY_RU = mapOf(
            DayOfWeek.MONDAY to "каждый понедельник", DayOfWeek.TUESDAY to "каждый вторник",
            DayOfWeek.WEDNESDAY to "каждую среду", DayOfWeek.THURSDAY to "каждый четверг",
            DayOfWeek.FRIDAY to "каждую пятницу", DayOfWeek.SATURDAY to "каждую субботу",
            DayOfWeek.SUNDAY to "каждое воскресенье",
        )

        fun dayCode(day: DayOfWeek): String = DAY_CODES.getValue(day)
        fun dayFromCode(code: String): DayOfWeek? = CODE_DAYS[code.trim().uppercase().take(2)]

        /** Разбор строки правила. Возвращает null для некорректной строки (не бросает исключение). */
        fun decode(value: String?): Recurrence? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                val parts = value.split(";").mapNotNull {
                    val idx = it.indexOf('=')
                    if (idx <= 0) null else it.substring(0, idx).trim().uppercase() to it.substring(idx + 1).trim()
                }.toMap()
                val freq = Frequency.valueOf(parts.getValue("FREQ").uppercase())
                Recurrence(
                    frequency = freq,
                    interval = parts["INTERVAL"]?.toIntOrNull() ?: 1,
                    daysOfWeek = parts["BYDAY"]?.split(",")?.mapNotNull(::dayFromCode)?.toSet() ?: emptySet(),
                    time = parts["TIME"]?.let { LocalTime.parse(it) },
                    dayOfMonth = parts["BYMONTHDAY"]?.toIntOrNull(),
                    month = parts["BYMONTH"]?.toIntOrNull(),
                )
            }.getOrNull()
        }

        private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime =
            ZonedDateTime.of(date, time.truncatedTo(ChronoUnit.MINUTES), zone)
    }
}
