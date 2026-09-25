package ai.loli.core.assistant

import ai.loli.core.model.NoteKind
import ai.loli.core.model.Recurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionParserTest {
    private val now = Instant.parse("2026-09-25T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private val parser = ActionParser(now, zone, mapOf("#1" to "11111111-1111-1111-1111-111111111111"))

    @Test fun expenseIntent() {
        val plan = parser.parse("""{"reply":"Записала","actions":[{"type":"create_expense","amount":850,"currency":"RUB","category":"food","description":"продукты","date":"2026-09-25"}]}""")
        val a = assertIs<AssistantAction.CreateExpense>(plan.actions.single())
        assertEquals(85_000, a.amountMinor)
        assertEquals("Продукты", a.category)
        assertEquals(LocalDate.of(2026, 9, 25), a.date)
    }

    @Test fun codeFencedJsonIsAccepted() {
        val plan = parser.parse("```json\n{\"reply\":\"ok\",\"actions\":[{\"type\":\"create_note\",\"kind\":\"idea\",\"title\":\"Холодильник\"}]}\n```")
        assertEquals(NoteKind.IDEA, assertIs<AssistantAction.CreateNote>(plan.actions.single()).kind)
    }

    @Test fun plainTextAnswerExecutesNothing() {
        val plan = parser.parse("Привет! Чем помочь?")
        assertTrue(plan.actions.isEmpty())
        assertEquals("Привет! Чем помочь?", plan.reply)
    }

    @Test fun invalidActionsRejectedWithReason() {
        val plan = parser.parse(
            """{"reply":"","actions":[
              {"type":"create_expense","amount":-5},
              {"type":"create_expense"},
              {"type":"launch_rockets"},
              {"type":"create_reminder","text":"x","datetime":"2020-01-01T10:00"},
              {"type":"append_note","target":"#99","content":"text"},
              {"type":"create_task","title":"Проверить почту"}
            ]}""",
        )
        assertEquals(1, plan.actions.size)
        assertIs<AssistantAction.CreateTask>(plan.actions.single())
        assertEquals(5, plan.rejected.size)
    }

    @Test fun handleMappedToId() {
        val plan = parser.parse("""{"actions":[{"type":"append_note","target":"#1","content":"сканирование штрихкодов"}]}""")
        val a = assertIs<AssistantAction.AppendNote>(plan.actions.single())
        assertEquals("11111111-1111-1111-1111-111111111111", a.target.id)
    }

    @Test fun unknownHandleWithQueryFallsBackToSearch() {
        val plan = parser.parse("""{"actions":[{"type":"append_note","target":"#7","query":"холодильник","content":"штрихкоды"}]}""")
        val a = assertIs<AssistantAction.AppendNote>(plan.actions.single())
        assertNull(a.target.id)
        assertEquals("холодильник", a.target.query)
    }

    @Test fun reminderInMinutesAndRecurrence() {
        val r1 = assertIs<AssistantAction.CreateReminder>(parser.parse("""{"actions":[{"type":"create_reminder","text":"духовка","in_minutes":120}]}""").actions.single())
        assertEquals(now.plusSeconds(7200), r1.triggerAt)

        val r2 = assertIs<AssistantAction.CreateReminder>(
            parser.parse("""{"actions":[{"type":"create_reminder","text":"почта","recurrence":{"frequency":"weekly","days":["MO"],"time":"09:00"}}]}""").actions.single(),
        )
        assertEquals(Recurrence.Frequency.WEEKLY, r2.recurrence?.frequency)
        assertEquals(setOf(DayOfWeek.MONDAY), r2.recurrence?.daysOfWeek)
        assertEquals(Instant.parse("2026-09-28T06:00:00Z"), r2.triggerAt)
    }

    @Test fun reminderLocalDateTimeUsesUserZone() {
        val r = assertIs<AssistantAction.CreateReminder>(parser.parse("""{"actions":[{"type":"create_reminder","text":"позвонить клиенту","datetime":"2026-09-26T10:00"}]}""").actions.single())
        assertEquals(Instant.parse("2026-09-26T07:00:00Z"), r.triggerAt)
    }

    @Test fun destructiveFlag() {
        val plan = parser.parse("""{"actions":[{"type":"delete_expenses","period":"this_month"},{"type":"create_note","title":"a"}]}""")
        assertTrue(plan.actions[0].isDestructive)
        assertTrue(!plan.actions[1].isDestructive)
    }

    @Test fun futureExpenseRejected() {
        val plan = parser.parse("""{"actions":[{"type":"create_expense","amount":100,"date":"2027-01-01"}]}""")
        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.rejected.size)
    }
}
