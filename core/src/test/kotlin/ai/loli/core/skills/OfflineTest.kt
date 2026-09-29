package ai.loli.core.skills

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DeviceController
import ai.loli.core.assistant.DeviceResult
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.MonthDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Полезное офлайн (2.2): календарь, факты, викторина, поздравления, дни рождения, события. Сегодня 25.09.2026. */
class OfflineTest {
    private val today = LocalDate.of(2026, 9, 25)
    private fun p(t: String) = SkillPhrases.parse(t, today)

    @Test fun almanacData() {
        assertEquals(LocalDate.of(2026, 4, 12), Almanac.easter(2026))
        assertEquals(LocalDate.of(2024, 5, 5), Almanac.easter(2024))
        assertTrue(Almanac.holidays(LocalDate.of(2026, 9, 30)).any { it.contains("Веры") })
        assertTrue(Almanac.holidays(LocalDate.of(2026, 9, 13)).contains("День программиста"))
        assertTrue(Almanac.holidays(LocalDate.of(2026, 11, 29)).contains("День матери"))
        assertEquals(LocalDate.of(2027, 5, 2), Almanac.find("пасха", today))
        assertTrue(Almanac.nameDays("Татьяна").contains(MonthDay.of(1, 25)))
        assertTrue(Almanac.omen(LocalDate.of(2026, 10, 14)).startsWith("Покров"))
    }

    @Test fun almanacPhrases() {
        assertEquals(AlmanacKind.HOLIDAY, (p("какой сегодня праздник") as SkillCommand.Almanac).kind)
        assertEquals(today.plusDays(1), (p("какой праздник завтра") as SkillCommand.Almanac).date)
        assertEquals(AlmanacKind.NEXT_HOLIDAY, (p("когда ближайший праздник") as SkillCommand.Almanac).kind)
        assertEquals(AlmanacKind.WHEN_HOLIDAY, (p("когда пасха") as SkillCommand.Almanac).kind)
        assertEquals(AlmanacKind.NAME_DAY, (p("у кого сегодня именины") as SkillCommand.Almanac).kind)
        assertEquals("татьяны", (p("когда именины у татьяны") as SkillCommand.Almanac).name)
        assertEquals(AlmanacKind.OMEN, (p("какие приметы на сегодня") as SkillCommand.Almanac).kind)
        assertEquals(SkillCommand.FactOfDay(false), p("расскажи интересный факт"))
        assertEquals(SkillCommand.FactOfDay(true), p("факт дня"))
        assertTrue(p("давай викторину").let { it is SkillCommand.StartGame && it.game is Game.Quiz })
        assertTrue(p("давай поиграем в угадай слово").let { it is SkillCommand.StartGame && it.game is Game.GuessWord })
        assertTrue(p("поздравь машу с днём рождения").let { it is SkillCommand.Greet && it.occasion == "с днём рождения" })
        assertFalse(p("когда день рождения у маши") is SkillCommand.Almanac)
    }

    @Test fun quizMatchesNumbersAsWords() {
        assertTrue(Game.answered("ноль", listOf("ноль", "0")))
        assertFalse(Game.answered("сто", listOf("ноль", "0")))
        assertFalse(Game.answered("100", listOf("ноль", "0")))
        assertTrue(Game.answered("пятьдесят шесть", listOf("пятьдесят шесть", "56")))
        assertTrue(Game.answered("в Риме", listOf("Рим")))
    }

    @Test fun triviaIsWellFormed() {
        assertTrue(Trivia.FACTS.size >= 100 && Trivia.QUIZ.size >= 140 && Trivia.WORDS.size >= 40)
        assertEquals(Trivia.QUIZ.size, Trivia.QUIZ.map { it.first }.distinct().size)
        assertTrue(Trivia.QUIZ.all { it.second.isNotEmpty() })
    }

    private class Host : SkillHost {
        override suspend fun location() = null
        override suspend fun contactBirthdays() = listOf("Маша Иванова" to MonthDay.of(9, 28), "Петя" to MonthDay.of(3, 1))
    }

    private class Dev : DeviceController {
        val done = ArrayList<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult { done += command; return DeviceResult("Готово.") }
    }

    @Test fun engineFlows() = runTest {
        val dev = Dev()
        val host = Host()
        val e = TestEnv(device = dev, skillsFactory = { t -> Skills(host, null, t) }).apply { settings = AssistantSettings(useAI = false) }
        e.engine.handle("какой сегодня праздник").text.let { t -> assertTrue(t.isNotBlank(), t) }
        e.engine.handle("когда пасха").text.let { t -> assertTrue(t.contains("2 мая"), t) }
        e.engine.handle("давай викторину").text.let { t -> assertTrue(t.startsWith("Викторина из 10"), t) }
        e.engine.handle("хватит")
        val greet = e.engine.handle("поздравь машу с днём рождения").text
        assertTrue(greet.startsWith("Поздравление:"), greet)
        assertTrue(dev.done.any { it is DeviceCommand.Message && it.who.lowercase() == "машу" }, dev.done.toString())
        val saved = e.engine.handle("запомни, что отпуск 15 июля").text
        assertTrue(saved.startsWith("Запомнила: отпуск"), saved)
        e.engine.handle("сколько дней до отпуска").text.let { t -> assertTrue(t.contains("отпуск"), t) }
        e.engine.handle("сколько дней до пасхи").text.let { t -> assertTrue(t.contains("Пасха"), t) }
        e.engine.handle("сколько дней до нового года").text.let { t -> assertTrue(t.contains("1 января"), t) }
    }

    @Test fun birthdaysFromContacts() = runTest {
        val host = Host()
        val e = TestEnv(skillsFactory = { t -> Skills(host, null, t) }).apply { settings = AssistantSettings(useAI = false) }
        // Контакты — через исполнителя; в TestEnv он без них, поэтому проверяем записанные в Лоли.
        e.engine.handle("день рождения мамы 1 октября")
        val soon = e.engine.handle("у кого скоро день рождения").text
        assertTrue(soon.startsWith("Скоро дни рождения") && soon.contains("мамы", ignoreCase = true), soon)
    }
}
