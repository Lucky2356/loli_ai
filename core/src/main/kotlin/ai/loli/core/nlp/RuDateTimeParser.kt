package ai.loli.core.nlp

import ai.loli.core.model.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Результат разбора временного выражения. Все поля необязательны. */
data class WhenSpec(
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val offset: Duration? = null,
    val recurrence: Recurrence? = null,
    /** Час назван без «утра/вечера» («в 5») — может означать и 05:00, и 17:00. */
    val ambiguousHour: Boolean = false,
) {
    val isEmpty: Boolean get() = date == null && time == null && offset == null && recurrence == null
}

data class WhenParseResult(val spec: WhenSpec, val remainder: String)

/**
 * Разбор русских выражений времени без AI: «завтра в 10 утра», «через два часа», «в пятницу»,
 * «15 октября», «каждый понедельник в 9 утра», «по будням в 8:30», «через полчаса».
 * Используется офлайн-парсером команд и как страховка при валидации ответов AI.
 */
class RuDateTimeParser {

    fun parse(text: String, today: LocalDate): WhenParseResult {
        val tokens = RuTokenizer.tokenize(text)
        val consumed = BooleanArray(tokens.size)
        var spec = WhenSpec()
        var i = 0
        while (i < tokens.size) {
            val m = matchAt(tokens, i, today, spec)
            if (m != null && m.count > 0) {
                for (k in i until i + m.count) consumed[k] = true
                spec = m.spec
                i += m.count
            } else {
                i++
            }
        }
        return WhenParseResult(spec, remainder(text, tokens, consumed))
    }

    /** Вычисляет момент первого срабатывания (для напоминаний). null — если времени в фразе нет. */
    fun resolveTrigger(spec: WhenSpec, now: Instant, zone: ZoneId, defaultTime: LocalTime = LocalTime.of(9, 0)): Instant? {
        if (spec.offset != null) return now.plus(spec.offset)
        val nowZ = now.atZone(zone)
        spec.recurrence?.let { rule ->
            if (rule.frequency == Recurrence.Frequency.HOURLY && spec.time == null) {
                // «каждый час» — первое срабатывание через интервал, а не прямо сейчас
                return now.plusSeconds(3600L * rule.interval)
            }
            val withTime = if (rule.time == null && rule.frequency != Recurrence.Frequency.HOURLY) {
                rule.copy(time = spec.time ?: defaultTime)
            } else rule
            val anchorDate = spec.date ?: nowZ.toLocalDate()
            val anchor = if (withTime.frequency == Recurrence.Frequency.HOURLY) now
            else ZonedDateTime.of(anchorDate, withTime.time ?: defaultTime, zone).toInstant()
            return withTime.nextAfter(now.minusMillis(1), zone, anchor)
        }
        if (spec.date == null && spec.time == null) return null
        val date = spec.date ?: nowZ.toLocalDate()
        val time = spec.time ?: defaultTime
        if (spec.date == null && spec.ambiguousHour && time.hour in 1..11) {
            // «в 5» без даты — ближайшее будущее из 05:00 и 17:00
            val options = listOf(
                ZonedDateTime.of(date, time, zone), ZonedDateTime.of(date, time.plusHours(12), zone),
                ZonedDateTime.of(date.plusDays(1), time, zone),
            )
            return options.filter { it.isAfter(nowZ) }.minOf { it }.toInstant()
        }
        var candidate = ZonedDateTime.of(date, time, zone)
        if (spec.date == null && !candidate.isAfter(nowZ)) candidate = candidate.plusDays(1)
        return candidate.toInstant()
    }

    /** Итоговое правило повторения с учётом времени из фразы. */
    fun effectiveRecurrence(spec: WhenSpec, defaultTime: LocalTime = LocalTime.of(9, 0)): Recurrence? =
        spec.recurrence?.let { r ->
            if (r.time == null && r.frequency != Recurrence.Frequency.HOURLY) r.copy(time = spec.time ?: defaultTime) else r
        }

    private data class Match(val count: Int, val spec: WhenSpec)

    private fun matchAt(t: List<Tok>, i: Int, today: LocalDate, spec: WhenSpec): Match? {
        val w = t[i].norm
        fun at(k: Int) = t.getOrNull(k)?.norm

        // --- Повторения ---
        if (w in EVERY) {
            val next = at(i + 1) ?: return null
            WEEKDAY_ANY[next]?.let { first ->
                val days = mutableSetOf(first)
                var k = i + 2
                while (at(k) == "и" && WEEKDAY_ANY[at(k + 1)] != null) { days += WEEKDAY_ANY.getValue(at(k + 1)!!); k += 2 }
                return Match(k - i, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY, daysOfWeek = days)))
            }
            when (next) {
                "день" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY)))
                "утро" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY), time = spec.time ?: LocalTime.of(9, 0)))
                "вечер" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY), time = spec.time ?: LocalTime.of(20, 0)))
                "неделю" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY)))
                "месяц" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.MONTHLY)))
                "год" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.YEARLY)))
                "час" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.HOURLY)))
            }
            val n = t.getOrNull(i + 1)?.intValue
            val unit = at(i + 2)
            if (n != null && n in 1..365 && unit != null) {
                when {
                    unit.startsWith("час") -> return Match(3, spec.copy(recurrence = Recurrence(Recurrence.Frequency.HOURLY, interval = n)))
                    unit.startsWith("дн") || unit == "день" -> return Match(3, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY, interval = n)))
                    unit.startsWith("недел") -> return Match(3, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY, interval = n)))
                    unit.startsWith("месяц") -> return Match(3, spec.copy(recurrence = Recurrence(Recurrence.Frequency.MONTHLY, interval = n)))
                }
            }
            return null
        }
        when (w) {
            "ежедневно" -> return Match(1, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY)))
            "еженедельно" -> return Match(1, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY)))
            "ежемесячно" -> return Match(1, spec.copy(recurrence = Recurrence(Recurrence.Frequency.MONTHLY)))
            "ежегодно" -> return Match(1, spec.copy(recurrence = Recurrence(Recurrence.Frequency.YEARLY)))
            "ежечасно" -> return Match(1, spec.copy(recurrence = Recurrence(Recurrence.Frequency.HOURLY)))
        }
        if (w == "по") {
            val next = at(i + 1)
            WEEKDAY_PLURAL[next]?.let { first ->
                val days = mutableSetOf(first)
                var k = i + 2
                while (at(k) == "и" && WEEKDAY_PLURAL[at(k + 1)] != null) { days += WEEKDAY_PLURAL.getValue(at(k + 1)!!); k += 2 }
                return Match(k - i, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY, daysOfWeek = days)))
            }
            when (next) {
                "будням" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY, daysOfWeek = WORKDAYS)))
                "выходным" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.WEEKLY, daysOfWeek = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))))
                "утрам" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY), time = spec.time ?: LocalTime.of(9, 0)))
                "вечерам" -> return Match(2, spec.copy(recurrence = Recurrence(Recurrence.Frequency.DAILY), time = spec.time ?: LocalTime.of(20, 0)))
            }
        }

        // --- Относительные дни ---
        val prepOffset = if (w in DAY_PREPOSITIONS && RELATIVE_DAYS.containsKey(at(i + 1))) 1 else 0
        RELATIVE_DAYS[at(i + prepOffset)]?.let { days ->
            return Match(1 + prepOffset, spec.copy(date = today.plusDays(days.toLong())))
        }

        // --- «через N единиц» ---
        if (w == "через") {
            val next = t.getOrNull(i + 1) ?: return null
            if (next.norm == "полчаса") return Match(2, spec.copy(offset = Duration.ofMinutes(30)))
            val (amount, unitIdx) = if (next.number != null) next.number to i + 2 else 1.0 to i + 1
            val unit = at(unitIdx) ?: return null
            val minutes = when {
                unit.startsWith("мин") -> amount
                unit.startsWith("час") -> amount * 60
                unit == "день" || unit == "дня" || unit == "дней" || unit == "сутки" -> amount * 60 * 24
                unit.startsWith("недел") -> amount * 60 * 24 * 7
                unit.startsWith("месяц") -> amount * 60 * 24 * 30
                unit.startsWith("сек") -> maxOf(1.0, amount / 60)
                else -> return null
            }
            val count = unitIdx - i + 1
            return if (unit.startsWith("месяц") && amount == Math.floor(amount)) {
                Match(count, spec.copy(date = today.plusMonths(amount.toLong())))
            } else {
                Match(count, spec.copy(offset = Duration.ofSeconds((minutes * 60).toLong())))
            }
        }

        // --- День недели: «в пятницу», «в следующий вторник», «до пятницы», «к среде» ---
        if (w in WEEKDAY_PREPOSITIONS) {
            var k = i + 1
            var forceNextWeek = false
            if (at(k)?.startsWith("следующ") == true) { forceNextWeek = true; k++ }
            if (at(k)?.startsWith("эт") == true) k++ // «в эту пятницу»
            val day = WEEKDAY_ANY[at(k)]
            if (day != null) {
                return Match(k - i + 1, spec.copy(date = nextWeekday(today, day, forceNextWeek)))
            }
        }
        WEEKDAY_ACC[w]?.let { day ->
            // Голый день недели без предлога, например «завтра» уже обработано; «пятница 10 утра»
            if (i == 0 || t[i - 1].norm !in setOf("каждый", "каждую", "каждое")) {
                return Match(1, spec.copy(date = nextWeekday(today, day, false)))
            }
        }

        // --- Явная дата: «15 октября», «на 3 мая 2027», «15.10», «15.10.2026» ---
        run {
            var k = i
            if (w in DAY_PREPOSITIONS || w in WEEKDAY_PREPOSITIONS) k++
            val tok = t.getOrNull(k) ?: return@run
            val dotted = Regex("""(\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?""").matchEntire(tok.norm)
            if (dotted != null) {
                val d = dotted.groupValues[1].toInt()
                val mo = dotted.groupValues[2].toInt()
                val y = dotted.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                val date = safeDate(today, d, mo, y) ?: return@run
                return Match(k - i + 1, spec.copy(date = date))
            }
            val day = tok.intValue
            // «до 10 числа», «к 5-му числу», «15-го» — день текущего (или следующего) месяца
            if (day != null && day in 1..31 && (at(k + 1) == "числа" || at(k + 1) == "число" || at(k + 1) == "го" || at(k + 1) == "числу")) {
                var date = runCatching { today.withDayOfMonth(minOf(day, today.lengthOfMonth())) }.getOrNull() ?: return@run
                if (date.isBefore(today)) date = today.plusMonths(1).let { it.withDayOfMonth(minOf(day, it.lengthOfMonth())) }
                return Match(k - i + 2, spec.copy(date = date))
            }
            val month = MONTHS[at(k + 1)]
            if (day != null && day in 1..31 && month != null) {
                var count = k - i + 2
                var year: Int? = null
                val yTok = t.getOrNull(k + 2)
                if (yTok?.intValue != null && yTok.intValue!! in 2000..2100) {
                    year = yTok.intValue; count++
                    if (at(k + 3)?.startsWith("год") == true) count++
                }
                val date = safeDate(today, day, month, year) ?: return@run
                return Match(count, spec.copy(date = date))
            }
        }

        // --- Время ---
        if (w == "полдень" || (w in TIME_PREPOSITIONS && at(i + 1) == "полдень")) {
            return Match(if (w == "полдень") 1 else 2, spec.copy(time = LocalTime.NOON))
        }
        if (w == "полночь" || (w in TIME_PREPOSITIONS && at(i + 1) == "полночь")) {
            return Match(if (w == "полночь") 1 else 2, spec.copy(time = LocalTime.MIDNIGHT))
        }
        run {
            var k = i
            val hasPrep = w in TIME_PREPOSITIONS
            if (hasPrep) k++
            val tok = t.getOrNull(k) ?: return@run
            var hour: Int
            var minute = 0
            var count: Int
            if (tok.isTime) {
                val (h, m) = tok.norm.split(":").map { it.toInt() }
                hour = h; minute = m
                count = k - i + 1
            } else {
                val h = tok.intValue ?: return@run
                if (h !in 0..23) return@run
                hour = h
                count = k - i + 1
                var hadHourWord = false
                if (at(k + 1)?.startsWith("час") == true) { count++; hadHourWord = true }
                val minTok = t.getOrNull(i + count)
                if (minTok?.intValue != null && minTok.intValue!! in 0..59 && at(i + count + 1)?.startsWith("мин") == true) {
                    minute = minTok.intValue!!; count += 2
                } else if (hasPrep && minTok?.intValue != null && minTok.intValue!! in 10..59 && !minTok.text.contains(' ') &&
                    at(i + count + 1) !in Money.currencyWords && at(i + count + 1)?.let { it.startsWith("час") || it.startsWith("дн") || it.startsWith("недел") } != true
                ) {
                    // «в десять тридцать», «в 8 45»
                    minute = minTok.intValue!!; count += 1
                } else if (hasPrep && minTok?.isNumber == true && minTok.text.contains(' ') && minTok.intValue in 10..59) {
                    // «в восемь пятнадцать» → токен «пятнадцать» склеивается отдельно; «двадцать пять» — одним токеном
                    minute = minTok.intValue!!; count += 1
                }
                val partOfDay = at(i + count)
                // Без сильного предлога («в», «к») число считаем временем только с «утра/вечера/часов» —
                // иначе это может быть сумма или количество («на 10 человек»).
                val strongPrep = hasPrep && w in STRONG_TIME_PREPOSITIONS
                if (!strongPrep && !hadHourWord && partOfDay !in PART_OF_DAY) return@run
                // «до 10 числа» — это дата, а не время
                if (at(i + count) == "числа" || at(i + count) == "число" || at(i + count) == "го" || at(i + count) == "числу") return@run
                // «в 5 рублей» — не время.
                if (at(i + count) in Money.currencyWords) return@run
            }
            val part = at(i + count)
            var ambiguous = false
            if (part !in PART_OF_DAY && hour in 1..11) {
                // «вечером в 7», «днём в 3» — время суток названо раньше
                val earlier = spec.time
                if (earlier != null && earlier.hour >= 13) hour += 12 else ambiguous = !tok.isTime
            }
            if (part in PART_OF_DAY) {
                count++
                hour = when (part) {
                    "утра" -> if (hour == 12) 0 else hour
                    "дня" -> if (hour in 1..6) hour + 12 else hour
                    "вечера" -> if (hour in 1..11) hour + 12 else hour
                    "ночи" -> if (hour == 12) 0 else if (hour in 7..11) hour + 12 else hour
                    else -> hour
                }
            }
            if (hour !in 0..23 || minute !in 0..59) return@run
            return Match(count, spec.copy(time = LocalTime.of(hour, minute), ambiguousHour = ambiguous && part !in PART_OF_DAY))
        }
        // «в обед», «после обеда», «перед сном», «в конце дня», «с утра»
        TWO_WORD_TIMES["$w ${at(i + 1)}"]?.let { time ->
            return Match(2, if (spec.time == null) spec.copy(time = time) else spec)
        }
        // «на выходных», «в выходные» — ближайшая суббота
        if ((w == "на" && at(i + 1) == "выходных") || (w == "в" && at(i + 1) == "выходные")) {
            val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).let { if (it == today) it else it }
            return Match(2, spec.copy(date = sat))
        }
        PART_OF_DAY_ADVERBS[w]?.let { time ->
            if (spec.time == null) return Match(1, spec.copy(time = time))
            // «в 7 вечером» — сдвигаем уже названный час во вторую половину дня
            val current = spec.time
            if (time.hour >= 13 && current.hour in 1..11) return Match(1, spec.copy(time = current.plusHours(12), ambiguousHour = false))
            return Match(1, spec.copy(ambiguousHour = false))
        }
        return null
    }

    private fun nextWeekday(today: LocalDate, day: DayOfWeek, forceNextWeek: Boolean): LocalDate {
        val date = today.with(TemporalAdjusters.next(day))
        if (!forceNextWeek) return date
        // «в следующий вторник» — вторник следующей календарной недели.
        val sameWeek = date.with(DayOfWeek.MONDAY) == today.with(DayOfWeek.MONDAY)
        return if (sameWeek) date.plusWeeks(1) else date
    }

    private fun safeDate(today: LocalDate, day: Int, month: Int, year: Int?): LocalDate? {
        if (month !in 1..12) return null
        val y = year ?: today.year
        val date = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: return null
        return if (year == null && date.isBefore(today)) runCatching { LocalDate.of(y + 1, month, day) }.getOrNull() else date
    }

    private fun remainder(text: String, tokens: List<Tok>, consumed: BooleanArray): String {
        if (consumed.none { it }) return text.trim()
        val sb = StringBuilder()
        var cursor = 0
        for ((idx, tok) in tokens.withIndex()) {
            if (!consumed[idx]) continue
            sb.append(text, cursor, tok.start)
            cursor = tok.end
        }
        sb.append(text.substring(cursor))
        return sb.toString()
            .replace(Regex("""\s+([,.!?])"""), "$1")
            .replace(Regex("""(^|\s)(в|во|на|к|до)(?=\s*(?:[,.!?]|$))""", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', ',', '.', ';', '-', '—')
            .trim()
    }

    companion object {
        private val EVERY = setOf("каждый", "каждую", "каждое", "каждые", "каждого")
        private val DAY_PREPOSITIONS = setOf("на", "к", "до", "с")
        private val WEEKDAY_PREPOSITIONS = setOf("в", "во", "на", "к", "до", "с")
        private val TIME_PREPOSITIONS = setOf("в", "во", "к", "до", "около", "на")
        private val STRONG_TIME_PREPOSITIONS = setOf("в", "во", "к", "около")
        private val PART_OF_DAY = setOf("утра", "дня", "вечера", "ночи")
        private val PART_OF_DAY_ADVERBS = mapOf(
            "утром" to LocalTime.of(9, 0), "днем" to LocalTime.of(13, 0),
            "вечером" to LocalTime.of(19, 0), "ночью" to LocalTime.of(23, 0),
        )
        private val TWO_WORD_TIMES = mapOf(
            "в обед" to LocalTime.of(13, 0), "после обеда" to LocalTime.of(14, 0), "перед сном" to LocalTime.of(22, 0),
            "в конце" to null, "с утра" to LocalTime.of(9, 0), "рано утром" to LocalTime.of(7, 0),
            "поздно вечером" to LocalTime.of(22, 0),
        ).filterValues { it != null }.mapValues { it.value!! }
        private val RELATIVE_DAYS = mapOf("сегодня" to 0, "завтра" to 1, "послезавтра" to 2, "вчера" to -1, "позавчера" to -2)
        private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        private val WEEKDAY_ACC = mapOf(
            "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "среду" to DayOfWeek.WEDNESDAY,
            "четверг" to DayOfWeek.THURSDAY, "пятницу" to DayOfWeek.FRIDAY, "субботу" to DayOfWeek.SATURDAY,
            "воскресенье" to DayOfWeek.SUNDAY,
        )
        private val WEEKDAY_ANY: Map<String?, DayOfWeek> = WEEKDAY_ACC + mapOf(
            "среда" to DayOfWeek.WEDNESDAY, "пятница" to DayOfWeek.FRIDAY, "суббота" to DayOfWeek.SATURDAY,
            "понедельника" to DayOfWeek.MONDAY, "вторника" to DayOfWeek.TUESDAY, "среды" to DayOfWeek.WEDNESDAY,
            "четверга" to DayOfWeek.THURSDAY, "пятницы" to DayOfWeek.FRIDAY, "субботы" to DayOfWeek.SATURDAY,
            "воскресенья" to DayOfWeek.SUNDAY,
            "понедельнику" to DayOfWeek.MONDAY, "вторнику" to DayOfWeek.TUESDAY, "среде" to DayOfWeek.WEDNESDAY,
            "четвергу" to DayOfWeek.THURSDAY, "пятнице" to DayOfWeek.FRIDAY, "субботе" to DayOfWeek.SATURDAY,
            "воскресенью" to DayOfWeek.SUNDAY,
        )
        private val WEEKDAY_PLURAL: Map<String?, DayOfWeek> = mapOf(
            "понедельникам" to DayOfWeek.MONDAY, "вторникам" to DayOfWeek.TUESDAY, "средам" to DayOfWeek.WEDNESDAY,
            "четвергам" to DayOfWeek.THURSDAY, "пятницам" to DayOfWeek.FRIDAY, "субботам" to DayOfWeek.SATURDAY,
            "воскресеньям" to DayOfWeek.SUNDAY,
        )
        private val MONTHS: Map<String?, Int> = mapOf(
            "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4, "мая" to 5, "июня" to 6,
            "июля" to 7, "августа" to 8, "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12,
        )
    }
}
