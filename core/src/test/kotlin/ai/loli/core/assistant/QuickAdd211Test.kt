package ai.loli.core.assistant

import ai.loli.core.model.NoteKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.11: быстрое добавление с экранов ПК-версии. Сегодня четверг 08.10.2026, 12:00 МСК. */
class QuickAdd211Test {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = LocalDateTime.of(2026, 10, 8, 12, 0).atZone(zone).toInstant()
    private val today = LocalDate.of(2026, 10, 8)

    private inline fun <reified T : AssistantAction> ok(kind: QuickAdd.Kind, text: String): T {
        val r = QuickAdd.plan(kind, text, now, zone)
        assertIs<QuickAdd.Result.Ok>(r, "$text → $r")
        return assertIs<T>(r.action, text)
    }

    @Test fun reminderWithTime() {
        val r = ok<AssistantAction.CreateReminder>(QuickAdd.Kind.REMINDER, "завтра в 9 позвонить маме")
        assertEquals(LocalDateTime.of(2026, 10, 9, 9, 0), r.triggerAt.atZone(zone).toLocalDateTime())
        assertTrue(r.text.contains("позвонить маме", ignoreCase = true), r.text)
    }

    @Test fun reminderAlreadyWithCommandIsNotDoubled() {
        val r = ok<AssistantAction.CreateReminder>(QuickAdd.Kind.REMINDER, "Напомни через 20 минут выключить духовку")
        assertEquals(LocalDateTime.of(2026, 10, 8, 12, 20), r.triggerAt.atZone(zone).toLocalDateTime())
        assertTrue(r.text.contains("духовк", ignoreCase = true), r.text)
    }

    @Test fun reminderWithoutTimeAsksHow() {
        assertIs<QuickAdd.Result.Hint>(QuickAdd.plan(QuickAdd.Kind.REMINDER, "позвонить маме", now, zone))
        assertIs<QuickAdd.Result.Hint>(QuickAdd.plan(QuickAdd.Kind.REMINDER, "   ", now, zone))
    }

    @Test fun taskKeepsDateAndPlainTitle() {
        val t = ok<AssistantAction.CreateTask>(QuickAdd.Kind.TASK, "купить хлеб завтра")
        assertEquals(today.plusDays(1), t.dueDate)
        assertTrue(t.title.contains("хлеб", ignoreCase = true), t.title)
        val plain = ok<AssistantAction.CreateTask>(QuickAdd.Kind.TASK, "разобрать почту")
        assertTrue(plain.title.contains("почт", ignoreCase = true), plain.title)
        assertNull(plain.dueTime)
    }

    @Test fun taskWithTime() {
        val t = ok<AssistantAction.CreateTask>(QuickAdd.Kind.TASK, "созвон с командой в пятницу в 15:00")
        assertEquals(LocalDate.of(2026, 10, 9), t.dueDate)
        assertEquals(LocalTime.of(15, 0), t.dueTime)
    }

    @Test fun expenseNeedsAmount() {
        val e = ok<AssistantAction.CreateExpense>(QuickAdd.Kind.EXPENSE, "кофе 250")
        assertEquals(25_000L, e.amountMinor)
        assertEquals(today, e.date)
        val y = ok<AssistantAction.CreateExpense>(QuickAdd.Kind.EXPENSE, "такси 640 вчера")
        assertEquals(today.minusDays(1), y.date)
        assertIs<QuickAdd.Result.Hint>(QuickAdd.plan(QuickAdd.Kind.EXPENSE, "кофе", now, zone))
    }

    @Test fun noteAndIdeaAreStoredAsIs() {
        val n = ok<AssistantAction.CreateNote>(QuickAdd.Kind.NOTE, "пароль от вайфая на даче на обороте роутера. Не забыть")
        assertEquals(NoteKind.NOTE, n.kind)
        assertEquals("Пароль от вайфая на даче на обороте роутера", n.title)
        assertEquals("пароль от вайфая на даче на обороте роутера. Не забыть", n.content)
        assertEquals(NoteKind.IDEA, ok<AssistantAction.CreateNote>(QuickAdd.Kind.IDEA, "приложение для рецептов").kind)
    }
}
