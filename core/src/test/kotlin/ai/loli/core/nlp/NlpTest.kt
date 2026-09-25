package ai.loli.core.nlp

import ai.loli.core.model.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuNumbersTest {
    private fun num(s: String) = RuTokenizer.tokenize(s).firstOrNull { it.isNumber }?.number

    @Test fun digits() {
        assertEquals(850.0, num("850 рублей"))
        assertEquals(1200.0, num("1 200 руб"))
        assertEquals(12.5, num("12,50 ₽"))
    }

    @Test fun words() {
        assertEquals(850.0, num("восемьсот пятьдесят рублей"))
        assertEquals(1200.0, num("тысяча двести"))
        assertEquals(1500.0, num("полторы тысячи"))
        assertEquals(2000.0, num("2 тысячи рублей"))
        assertEquals(21.0, num("двадцать один"))
        assertEquals(15.0, num("пятнадцать"))
        assertEquals(2.0, num("через пару часов"))
    }

    @Test fun separateNumbersNotMerged() {
        val nums = RuTokenizer.tokenize("в 10 30 минут").filter { it.isNumber }.map { it.number }
        assertEquals(listOf(10.0, 30.0), nums)
    }
}

class RuDateTimeParserTest {
    private val parser = RuDateTimeParser()
    private val today = LocalDate.of(2026, 9, 25) // пятница
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-09-25T09:00:00Z") // 12:00 МСК

    @Test fun tomorrowMorning() {
        val r = parser.parse("напомни завтра в 10 утра позвонить клиенту", today)
        assertEquals(today.plusDays(1), r.spec.date)
        assertEquals(LocalTime.of(10, 0), r.spec.time)
        assertEquals("напомни позвонить клиенту", r.remainder)
    }

    @Test fun wordsTime() {
        val r = parser.parse("завтра в десять утра позвонить клиенту", today)
        assertEquals(LocalTime.of(10, 0), r.spec.time)
        assertEquals("позвонить клиенту", r.remainder)
    }

    @Test fun eveningHours() {
        assertEquals(LocalTime.of(19, 0), parser.parse("в 7 вечера", today).spec.time)
        assertEquals(LocalTime.of(15, 0), parser.parse("в 3 дня", today).spec.time)
        assertEquals(LocalTime.of(18, 30), parser.parse("в 18:30", today).spec.time)
        assertEquals(LocalTime.NOON, parser.parse("в полдень", today).spec.time)
    }

    @Test fun relativeOffset() {
        val r = parser.parse("через два часа проверить духовку", today)
        assertEquals(Duration.ofHours(2), r.spec.offset)
        assertEquals("проверить духовку", r.remainder)
        assertEquals(Duration.ofMinutes(30), parser.parse("через полчаса", today).spec.offset)
        assertEquals(Duration.ofMinutes(15), parser.parse("через 15 минут", today).spec.offset)
        assertEquals(Duration.ofHours(1), parser.parse("через час", today).spec.offset)
    }

    @Test fun weekdays() {
        assertEquals(LocalDate.of(2026, 9, 28), parser.parse("в понедельник", today).spec.date)
        assertEquals(LocalDate.of(2026, 10, 2), parser.parse("в пятницу", today).spec.date)
        assertEquals(LocalDate.of(2026, 9, 29), parser.parse("в следующий вторник", today).spec.date) // вторник следующей недели
        assertEquals(LocalDate.of(2026, 10, 2), parser.parse("до пятницы", today).spec.date)
    }

    @Test fun explicitDates() {
        assertEquals(LocalDate.of(2026, 10, 15), parser.parse("15 октября", today).spec.date)
        assertEquals(LocalDate.of(2027, 3, 1), parser.parse("1 марта", today).spec.date) // прошедшая дата → следующий год
        assertEquals(LocalDate.of(2026, 12, 31), parser.parse("31.12", today).spec.date)
    }

    @Test fun weeklyRecurrence() {
        val r = parser.parse("каждый понедельник в 9 утра напоминай проверить почту", today)
        val rec = assertNotNull(r.spec.recurrence)
        assertEquals(Recurrence.Frequency.WEEKLY, rec.frequency)
        assertEquals(setOf(DayOfWeek.MONDAY), rec.daysOfWeek)
        assertEquals(LocalTime.of(9, 0), r.spec.time)
        val trigger = parser.resolveTrigger(r.spec, now, zone)
        assertEquals(Instant.parse("2026-09-28T06:00:00Z"), trigger)
    }

    @Test fun workdaysAndDaily() {
        val r = parser.parse("по будням в 8:30", today)
        assertEquals(5, r.spec.recurrence?.daysOfWeek?.size)
        assertEquals(Recurrence.Frequency.DAILY, parser.parse("каждый день в 22:00", today).spec.recurrence?.frequency)
    }

    @Test fun resolveTimeOnlyInPastMovesToTomorrow() {
        val spec = parser.parse("в 9 утра", today).spec
        assertEquals(Instant.parse("2026-09-26T06:00:00Z"), parser.resolveTrigger(spec, now, zone))
    }

    @Test fun moneyIsNotTime() {
        assertNull(parser.parse("потратила 500 рублей", today).spec.time)
        assertNull(parser.parse("купить продукты на 10 человек", today).spec.time)
    }
}

class RuStemmerTest {
    @Test fun formsShareStem() {
        assertEquals(RuStemmer.stem("холодильник"), RuStemmer.stem("холодильнике"))
        assertEquals(RuStemmer.stem("продукты"), RuStemmer.stem("продуктов"))
        assertEquals(TextAnalysis.stems("идея"), TextAnalysis.stems("идеи"))
        assertEquals(RuStemmer.stem("приложение"), RuStemmer.stem("приложения"))
    }
}

class WakeWordMatcherTest {
    private val m = WakeWordMatcher("Лоли")

    @Test fun exactAndCommand() {
        val r = assertNotNull(m.match("Лоли, запиши расход 500 рублей"))
        assertEquals("запиши расход 500 рублей", r.command)
    }

    @Test fun fuzzyVariants() {
        assertNotNull(m.match("лолли запиши"))
        assertNotNull(m.match("лоле сколько я потратила"))
        assertNotNull(m.match("эй лоли привет"))
    }

    @Test fun rejectsOtherWords() {
        assertNull(m.match("молоко купить"))
        assertNull(m.match("позвони маме"))
        assertFalse(m.containsWakeWord("я пошла в магазин"))
    }

    @Test fun customName() {
        val j = WakeWordMatcher("Джарвис")
        assertEquals("который час", j.match("Джарвис, который час")?.command)
        assertTrue(j.containsWakeWord("окей джарвис"))
    }
}

class ExpenseCategoriesTest {
    @Test fun categorize() {
        assertEquals("Продукты", ExpenseCategories.categorize("продукты"))
        assertEquals("Кафе и рестораны", ExpenseCategories.categorize("кофе"))
        assertEquals("Транспорт", ExpenseCategories.categorize("такси до работы"))
        assertEquals(ExpenseCategories.OTHER, ExpenseCategories.categorize("всякое"))
    }

    @Test fun normalizeKeepsUnknownAiCategory() {
        assertEquals("Продукты", ExpenseCategories.normalize("food"))
        assertEquals("Продукты", ExpenseCategories.normalize("продукты"))
        assertEquals("Хобби", ExpenseCategories.normalize("хобби"))
    }

    @Test fun moneyFormat() {
        assertEquals("1 200 ₽", Money.format(120_000, "RUB"))
        assertEquals("12,5 $", Money.format(1250, "USD"))
    }
}
