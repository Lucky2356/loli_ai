package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.health.Habits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2.5.0: дневник дня. */
class Diary25Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun moodDialogSavesScoreAndNote() = runTest {
        val e = env()
        assertEquals("Как настроение от 1 до 5?", e.engine.handle("как прошёл день").text)
        assertTrue(e.engine.handle("четыре").text.contains("4 из 5"))
        val saved = e.engine.handle("гуляли в парке").text
        assertTrue(saved.contains("настроение 4 из 5") && saved.contains("Гуляли в парке"), saved)
        val entry = e.store.habits.since(java.time.Instant.EPOCH, Habits.MOOD).single()
        assertEquals(4.0, entry.amount)
        assertEquals("Гуляли в парке", entry.name)
    }

    @Test fun directMoodAndNoNote() = runTest {
        val e = env()
        assertTrue(e.engine.handle("настроение 5").text.contains("5 из 5"))
        e.engine.handle("как прошёл день"); e.engine.handle("3"); e.engine.handle("нет")
        assertEquals(listOf(5.0, 3.0), e.store.habits.since(java.time.Instant.EPOCH, Habits.MOOD).map { it.amount })
    }

    @Test fun summaryAndWeek() = runTest {
        val e = env()
        e.engine.handle("настроение 4")
        e.engine.handle("запиши расход 300 на кофе")
        val t = e.store.tasks.create("Позвонить")
        e.store.tasks.update(t.copy(done = true, completedAt = e.time.now()))
        val sum = e.engine.handle("итоги дня").text
        assertTrue(sum.contains("Настроение: 4 из 5") && sum.contains("Закрыто задач: 1") && sum.contains("300"), sum)
        val week = e.engine.handle("как я себя чувствовала на этой неделе").text
        assertTrue(week.contains("Среднее настроение за неделю — 4"), week)
    }

    @Test fun commandAfterScoreIsNotSwallowed() = runTest {
        val e = env()
        e.engine.handle("как прошёл день"); e.engine.handle("5")
        e.engine.handle("запиши расход 100 на хлеб")
        assertEquals(1, e.store.expenses.all().size)
        assertEquals("", e.store.habits.since(java.time.Instant.EPOCH, Habits.MOOD).single().name)
    }
}
