package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2.2.3: ещё одна перепроверка. Сегодня пятница 25.09.2026, 12:00 МСК. */
class Audit223Test {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }
    private suspend fun TestEnv.times() = store.reminders.all().map { it.triggerAt.atZone(zone).toLocalDateTime() }.sorted()

    @Test fun todayWeekdayBeforeNamedHourMeansToday() = runTest {
        val e = env()
        e.engine.handle("напомни в пятницу в 18:00 встреча")
        assertEquals(listOf(LocalDateTime.of(2026, 9, 25, 18, 0)), e.times())
    }

    @Test fun todayWeekdayAfterNamedHourMeansNextWeek() = runTest {
        val e = env()
        e.engine.handle("напомни в пятницу в 10:00 встреча")
        assertEquals(listOf(LocalDateTime.of(2026, 10, 2, 10, 0)), e.times())
    }

    @Test fun nextFridayStaysNextWeek() = runTest {
        val e = env()
        e.engine.handle("напомни в следующую пятницу в 18:00 встреча")
        assertEquals(listOf(LocalDateTime.of(2026, 10, 2, 18, 0)), e.times())
    }

    @Test fun otherWeekdayUnchanged() = runTest {
        val e = env()
        e.engine.handle("напомни в субботу в 18:00 встреча")
        assertEquals(listOf(LocalDateTime.of(2026, 9, 26, 18, 0)), e.times())
    }

    @Test fun twoTimesMakeTwoReminders() = runTest {
        val e = env()
        e.engine.handle("напомни в 7 утра и в 7 вечера пить таблетки")
        assertEquals(listOf(LocalDateTime.of(2026, 9, 25, 19, 0), LocalDateTime.of(2026, 9, 26, 7, 0)), e.times())
        assertEquals(setOf("Пить таблетки"), e.store.reminders.all().map { it.text }.toSet())
    }

    @Test fun deleteAllRemindersAsksOnceThenCancelsAll() = runTest {
        val e = env()
        e.engine.handle("напомни в 7:50 достать мясо")
        e.engine.handle("напомни завтра в 9 позвонить маме")
        val ask = e.engine.handle("удали все напоминания").text
        assertTrue(ask.startsWith("Удалить 2 активных напоминания"), ask)
        assertEquals(2, e.store.reminders.active().size, "до ответа ничего не удалено")
        e.engine.handle("да")
        assertTrue(e.store.reminders.active().isEmpty())
    }

    @Test fun deleteAllTasksAsksOnceThenDeletes() = runTest {
        val e = env()
        e.engine.handle("добавь задачу купить молоко")
        e.engine.handle("добавь задачу позвонить в банк")
        val ask = e.engine.handle("удали все задачи").text
        assertTrue(ask.startsWith("Удалить 2 задачи в работе"), ask)
        e.engine.handle("нет")
        assertEquals(2, e.store.tasks.all().size)
        e.engine.handle("удали все задачи")
        e.engine.handle("да")
        assertTrue(e.store.tasks.all().isEmpty())
    }

    @Test fun deleteAllWithNothingSaysSo() = runTest {
        val e = env()
        assertTrue(e.engine.handle("удали все напоминания").text.contains("удалять нечего"))
    }

    @Test fun postponeWithoutObjectShiftsLastReminder() = runTest {
        val e = env()
        e.engine.handle("напомни в 7:50 достать мясо")
        e.engine.handle("отложи 10 минут").text.let { assertTrue(it.startsWith("Перенесла"), it) }
        assertEquals(listOf(LocalDateTime.of(2026, 9, 26, 8, 0)), e.times())
    }

    @Test fun savingsPhraseIsStillSavings() = runTest {
        val e = env()
        e.engine.handle("напомни в 7:50 достать мясо")
        e.engine.handle("отложи 5000 на отпуск")
        assertEquals(listOf(LocalDateTime.of(2026, 9, 26, 7, 50)), e.times())
    }

    @Test fun payInfinitiveIsATaskNotAnExpense() = runTest {
        for (phrase in listOf("оплатить кредит до конца месяца", "оплатить счета", "заплатить за интернет")) {
            val e = env()
            val text = e.engine.handle(phrase).text
            assertTrue(text.startsWith("Добавила задачу"), "$phrase → $text")
            assertEquals(1, e.store.tasks.all().size, phrase)
            assertTrue(e.store.expenses.all().isEmpty(), phrase)
        }
        val past = env()
        assertTrue(past.engine.handle("оплатил кредит").text.startsWith("Сколько потратили"))
    }

    @Test fun endOfMonthAndWeek() = runTest {
        val e = env()
        e.engine.handle("оплатить кредит до конца месяца")
        assertEquals(java.time.LocalDate.of(2026, 9, 30), e.store.tasks.all().single().dueDate)
        assertEquals("Оплатить кредит", e.store.tasks.all().single().title)
        val w = env()
        w.engine.handle("в конце недели оплатить счета")
        assertEquals(java.time.LocalDate.of(2026, 9, 25), w.store.tasks.all().single().dueDate)
    }

    @Test fun onceAWeekIsWeeklyRecurrence() = runTest {
        val e = env()
        e.engine.handle("напомни раз в неделю полить цветы")
        assertEquals(ai.loli.core.model.Recurrence.Frequency.WEEKLY, e.store.reminders.all().single().recurrence?.frequency)
        val d = env()
        d.engine.handle("раз в день пить воду")
        assertEquals(ai.loli.core.model.Recurrence.Frequency.DAILY, d.store.reminders.all().single().recurrence?.frequency)
    }

    @Test fun agendaHeaderHasNoDoublePreposition() = runTest {
        val e = env()
        val text = e.engine.handle("что у меня в понедельник").text
        assertTrue(text.startsWith("На понедельник, 28 сентября"), text)
    }

    @Test fun taskStatusQueriesAreUnderstood() = runTest {
        val e = env()
        e.engine.handle("добавь задачу купить молоко на вчера")
        assertTrue(e.engine.handle("просроченные задачи").text.startsWith("Просроченные задачи (1)"))
        assertTrue(e.engine.handle("невыполненные задачи").text.startsWith("Активные задачи (1)"))
        e.engine.handle("задача купить молоко выполнена")
        assertTrue(e.store.tasks.all().single().done)
        assertEquals(1, e.store.tasks.all().size, "выполненная задача не создаёт новую")
        assertTrue(e.engine.handle("выполненные задачи").text.startsWith("Выполненные задачи (1)"))
    }

    @Test fun ideasQuestionPointsToRecords() = runTest {
        val e = env()
        assertTrue(e.engine.handle("какие у меня идеи").text.startsWith("Идеи — на вкладке"))
    }

    @Test fun sayToMomThatIsAMessage() {
        val parser = LocalCommandParser()
        val now = java.time.Instant.parse("2026-09-25T09:00:00Z")
        val cmd = (parser.parse("скажи маме что я приеду вечером", now, zone)?.actions?.single() as AssistantAction.Device).command
        assertEquals(DeviceCommand.Message("маме", "я приеду вечером"), cmd)
        val notMsg = parser.parse("скажи мне что делать", now, zone)?.actions?.singleOrNull()
        assertTrue(notMsg !is AssistantAction.Device, "«скажи мне» — не сообщение")
    }
}
