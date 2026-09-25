package ai.loli.core.data

import ai.loli.core.TestEnv
import ai.loli.core.model.NoteKind
import ai.loli.core.model.Recurrence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {
    @Test fun notesCrudAndSoftDelete() = runTest {
        val env = TestEnv()
        val n = env.store.notes.create(NoteKind.NOTE, " Покупки ", "")
        assertEquals("Покупки", n.title)
        env.store.notes.append(n.id, "молоко")
        env.store.notes.append(n.id, "хлеб")
        assertEquals("• молоко\n• хлеб", env.store.notes.get(n.id)!!.content)
        assertEquals(1, env.store.notes.observe(NoteKind.NOTE).first().size)
        assertTrue(env.store.notes.delete(n.id))
        assertNull(env.store.notes.get(n.id))
        assertTrue(env.store.notes.all().isEmpty())
        // Tombstone остаётся для синхронизации.
        assertTrue(env.store.notes.dirtyRows().single().deleted)
    }

    @Test fun changesNotifyListeners() = runTest {
        val env = TestEnv()
        val changed = mutableListOf<String>()
        env.store.changes.addListener { changed += it }
        env.store.tasks.create("a")
        env.store.expenses.create(100, "RUB", "Другое", "", env.time.today())
        assertEquals(listOf("tasks", "expenses"), changed)
    }

    @Test fun recurringReminderMovesToNextOccurrence() = runTest {
        val env = TestEnv()
        val first = Instant.parse("2026-09-28T06:00:00Z")
        val r = env.store.reminders.create("Проверить почту", first, Recurrence(Recurrence.Frequency.WEEKLY, daysOfWeek = setOf(java.time.DayOfWeek.MONDAY), time = LocalTime.of(9, 0)), "Europe/Moscow")
        val next = env.store.reminders.markFired(r.id, first)!!
        assertTrue(next.active)
        assertEquals(Instant.parse("2026-10-05T06:00:00Z"), next.triggerAt)
        val once = env.store.reminders.create("Разово", first, null, "Europe/Moscow")
        assertFalse(env.store.reminders.markFired(once.id, first)!!.active)
        assertEquals(1, env.store.reminders.active().size)
    }

    @Test fun wipeClearsEverything() = runTest {
        val env = TestEnv()
        env.store.notes.create(NoteKind.IDEA, "x", "")
        env.store.memories.create("y", "fact")
        env.store.wipe()
        assertEquals(0, env.store.pendingChanges())
        assertTrue(env.store.notes.all().isEmpty())
    }
}
