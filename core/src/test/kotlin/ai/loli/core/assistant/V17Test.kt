package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Ошибки, найденные на втором телефоне (1.7.0): задачи с обращением, даты «назад», напоминания «через». */
class V17Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }
    private val p = LocalCommandParser()
    private val now = Instant.parse("2026-09-25T09:00:00Z") // пт 12:00 МСК
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 25)
    private fun one(s: String) = assertNotNull(p.parse(s, now, zone), s).actions.single()

    @Test fun taskWithNameAndInfinitive() = runTest {
        for (phrase in listOf(
            "Лоли поставить задачу сходить на стрижку",
            "Лоли, поставь задачу сходить на стрижку",
            "Лолли поставить задачу сходить на стрижку",
            "Лоля, заведи задачу сходить на стрижку",
            "Лоли, добавь задачу: сходить на стрижку",
            "поставить задачу сходить на стрижку",
            "поставь, Лоли, задачу сходить на стрижку",
            "запланируй сходить на стрижку",
            "мне надо сходить на стрижку, запиши задачу",
            "создай задачку сходить на стрижку",
            "поставь в задачи сходить на стрижку",
            "нужно поставить задачу сходить на стрижку",
        )) {
            val env = env()
            env.engine.handle(phrase)
            assertEquals(listOf("Сходить на стрижку"), env.store.tasks.all().map { it.title }, phrase)
        }
    }

    @Test fun daysAgo() {
        fun expDate(s: String) = assertIs<AssistantAction.CreateExpense>(one(s), s).date
        assertEquals(today.minusDays(2), expDate("потратил 500 на такси 2 дня назад"))
        assertEquals(today.minusDays(2), expDate("2 дня назад потратил 500 на такси"))
        assertEquals(today.minusDays(3), expDate("три дня назад потратил 300 на кофе"))
        assertEquals(today.minusDays(2), expDate("пару дней назад потратил 300 на кофе"))
        assertEquals(today.minusWeeks(1), expDate("неделю назад потратил 1000 на продукты"))
        assertEquals(today.minusDays(5), expDate("потратил 1000 на продукты пять дней тому назад"))
        assertEquals(LocalDate.of(2026, 9, 18), expDate("в прошлую пятницу потратил 700 на обед"))
        assertEquals(LocalDate.of(2026, 9, 16), expDate("на прошлой неделе в среду потратил 700 на обед"))
    }

    @Test fun daysForward() {
        fun due(s: String) = assertIs<AssistantAction.CreateTask>(one(s), s).dueDate
        assertEquals(today.plusWeeks(3), due("добавь задачу сдать отчёт через 3 недели"))
        assertEquals(today.plusDays(10), due("добавь задачу сдать отчёт через десять дней"))
        assertEquals(LocalDate.of(2026, 10, 2), due("добавь задачу сдать отчёт в следующую пятницу"))
        assertEquals(LocalDate.of(2026, 9, 30), due("добавь задачу сдать отчёт на следующей неделе в среду"))
    }

    @Test fun relativeReminders() {
        fun rem(s: String) = assertIs<AssistantAction.CreateReminder>(one(s), s)
        for (s in listOf(
            "напомни через 10 минут поставить чайник",
            "напомни поставить чайник через 10 минут",
            "через 10 минут напомни поставить чайник",
            "через десять минут напомни поставить чайник",
            "Напомни мне через 10 минут, что надо поставить чайник",
            "напомни через 10 минут о том, что нужно поставить чайник",
        )) {
            val r = rem(s)
            assertEquals("Поставить чайник", r.text, s)
            assertEquals(now.plusSeconds(600), r.triggerAt, s)
        }
        assertEquals(now.plusSeconds(1800), rem("напомни через полчаса выключить плиту").triggerAt)
        assertEquals(now.plusSeconds(4800), rem("напомни через час двадцать выключить плиту").triggerAt)
        assertEquals(now.plusSeconds(5400), rem("напомни через полтора часа выключить плиту").triggerAt)
    }

    @Test fun relativeReminderWithName() = runTest {
        val env = env()
        val reply = env.engine.handle("Лоли, напомни через 10 минут поставить чайник")
        assertEquals("Поставить чайник", env.store.reminders.all().single().text)
        kotlin.test.assertTrue("12:10" in reply.text, reply.text)
    }
}

class DeclensionTest {
    @Test fun plural() {
        assertEquals("1 задача", RuFormat.count(1, "задача", "задачи", "задач"))
        assertEquals("22 задачи", RuFormat.count(22, "задача", "задачи", "задач"))
        assertEquals("11 задач", RuFormat.count(11, "задача", "задачи", "задач"))
        assertEquals("115 задач", RuFormat.count(115, "задача", "задачи", "задач"))
    }

    @Test fun speechUnits() {
        val s = ai.loli.core.voice.SpeechText
        assertEquals("Записала: 1 рубль на кофе", s.declineUnits("Записала: 1 ₽ на кофе"))
        assertEquals("Итого 1 234 рубля", s.declineUnits("Итого 1 234 ₽"))
        assertEquals("850 рублей и 21 доллар", s.declineUnits("850 ₽ и 21 $"))
        assertEquals("12,50 рубля", s.declineUnits("12,50 ₽"))
        assertEquals("через 3 дня", s.declineUnits("через 3 дн."))
    }

    @Test fun recurrenceWords() {
        val r = ai.loli.core.model.Recurrence(ai.loli.core.model.Recurrence.Frequency.DAILY, interval = 3)
        assertEquals("каждые 3 дня", r.describeRu())
        assertEquals("каждые 5 часов", ai.loli.core.model.Recurrence(ai.loli.core.model.Recurrence.Frequency.HOURLY, interval = 5).describeRu())
        assertEquals("каждый 21 день", ai.loli.core.model.Recurrence(ai.loli.core.model.Recurrence.Frequency.DAILY, interval = 21).describeRu())
    }
}
