package ai.loli.core.assistant

import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Определение намерений без AI (офлайн-режим). */
class LocalCommandParserTest {
    private val p = LocalCommandParser()
    private val now = Instant.parse("2026-09-25T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private fun action(s: String) = assertNotNull(p.parse(s, now, zone), "не распознано: $s").actions.single()

    @Test fun expense() {
        val a = assertIs<AssistantAction.CreateExpense>(action("сегодня потратила 850 рублей на продукты"))
        assertEquals(85_000, a.amountMinor)
        assertEquals("RUB", a.currency)
        assertEquals("Продукты", a.category)
        assertEquals("продукты", a.description)
        assertEquals(LocalDate.of(2026, 9, 25), a.date)
    }

    @Test fun expenseYesterdayAndWords() {
        val a = assertIs<AssistantAction.CreateExpense>(action("вчера потратил тысячу двести рублей на такси"))
        assertEquals(120_000, a.amountMinor)
        assertEquals("Транспорт", a.category)
        assertEquals(LocalDate.of(2026, 9, 24), a.date)
        val b = assertIs<AssistantAction.CreateExpense>(action("Я потратила 500 рублей на кофе"))
        assertEquals("Кафе и рестораны", b.category)
    }

    @Test fun expenseQueries() {
        val a = assertIs<AssistantAction.QueryExpenses>(action("сколько я потратила на продукты в этом месяце"))
        assertEquals(PeriodPreset.THIS_MONTH, a.preset)
        assertEquals("Продукты", a.category)
        assertEquals(PeriodPreset.YESTERDAY, assertIs<AssistantAction.QueryExpenses>(action("сколько я потратила вчера")).preset)
        assertEquals(ReportMode.TOP, assertIs<AssistantAction.QueryExpenses>(action("какие были самые большие расходы")).mode)
        val week = assertIs<AssistantAction.QueryExpenses>(action("покажи расходы за последнюю неделю"))
        assertEquals(ReportMode.LIST, week.mode)
        assertEquals(PeriodPreset.LAST_7_DAYS, week.preset)
    }

    @Test fun reminders() {
        val a = assertIs<AssistantAction.CreateReminder>(action("напомни мне завтра в 10 утра позвонить клиенту"))
        assertEquals("Позвонить клиенту", a.text)
        assertEquals(Instant.parse("2026-09-26T07:00:00Z"), a.triggerAt)
        val b = assertIs<AssistantAction.CreateReminder>(action("напомни через два часа проверить духовку"))
        assertEquals(now.plusSeconds(7200), b.triggerAt)
        val c = assertIs<AssistantAction.CreateReminder>(action("каждый понедельник в 9 утра напоминай проверить почту"))
        assertNotNull(c.recurrence)
        assertEquals("Проверить почту", c.text)
    }

    @Test fun reminderWithoutTimeAsksWhen() {
        val plan = assertNotNull(p.parse("напомни купить молоко", now, zone))
        assertTrue(plan.actions.isEmpty())
        assertEquals("Купить молоко", assertIs<SlotRequest.ReminderTime>(plan.slot).text)
    }

    @Test fun tasks() {
        assertEquals("Купить продукты", assertIs<AssistantAction.CreateTask>(action("добавь задачу купить продукты")).title)
        assertEquals("Проверить почту", assertIs<AssistantAction.CreateTask>(action("добавь в задачи проверить почту")).title)
        val due = assertIs<AssistantAction.CreateTask>(action("добавь задачу на завтра в 15:00 отправить отчёт"))
        assertEquals(LocalDate.of(2026, 9, 26), due.dueDate)
        assertEquals(LocalTime.of(15, 0), due.dueTime)
        assertEquals("Отправить отчёт", due.title)
        assertEquals(TaskFilter.TODAY, assertIs<AssistantAction.QueryTasks>(action("покажи мои задачи на сегодня")).filter)
        assertEquals(TaskFilter.OVERDUE, assertIs<AssistantAction.QueryTasks>(action("какие задачи у меня просрочены")).filter)
        val done = assertIs<AssistantAction.CompleteTask>(action("отметь задачу купить продукты выполненной"))
        assertEquals("купить продукты", done.target.query)
    }

    @Test fun notesIdeasMemory() {
        val idea = assertIs<AssistantAction.CreateNote>(action("у меня появилась идея сделать приложение для учёта продуктов в холодильнике"))
        assertEquals(NoteKind.IDEA, idea.kind)
        assertTrue(idea.title.startsWith("Сделать приложение"))
        val note = assertIs<AssistantAction.CreateNote>(action("создай заметку «Идеи для дня рождения»"))
        assertEquals("Идеи для дня рождения", note.title)
        val append = assertIs<AssistantAction.AppendNote>(action("добавь туда игру Secret Identity"))
        assertEquals("Игру Secret Identity", append.content)
        assertTrue(append.target.isEmpty)
        val toIdea = assertIs<AssistantAction.AppendNote>(action("добавь к идее холодильника возможность сканировать штрихкоды"))
        assertTrue(toIdea.splitQueryFromContent)
        assertEquals(setOf(RecordType.IDEA), toIdea.target.types)
        val mem = assertIs<AssistantAction.Remember>(action("запомни, что я хочу когда-нибудь съездить в Японию"))
        assertEquals("Я хочу когда-нибудь съездить в Японию", mem.content)
        val search = assertIs<AssistantAction.Search>(action("найди мою заметку про отпуск"))
        assertEquals("отпуск", search.query)
        assertEquals(setOf(RecordType.NOTE), search.types)
    }

    @Test fun unknownReturnsNull() {
        assertNull(p.parse("абракадабра кукареку", now, zone))
    }

    @Test fun confirmationWords() {
        assertTrue(LocalCommandParser.isYes("Да"))
        assertTrue(LocalCommandParser.isYes("да, удаляй"))
        assertTrue(LocalCommandParser.isNo("нет"))
        assertTrue(LocalCommandParser.isNo("не надо"))
        assertTrue(!LocalCommandParser.isYes("да нет"))
        assertEquals(1, LocalCommandParser.ordinal("вторую", 3))
        assertEquals(2, LocalCommandParser.ordinal("последнюю", 3))
    }
}
