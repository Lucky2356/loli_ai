package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.review.Review
import ai.loli.core.skills.Workout
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.8: тренировка голосом, итоги недели, лента дня. Сегодня пятница 25.09.2026. */
class Review28Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun workoutFlow() = runTest {
        val e = env()
        val intro = e.engine.handle("начни зарядку")
        assertTrue(intro.text.startsWith("Зарядка: 8 упражнений") && intro.text.contains("1 из 8: Ходьба на месте, 1 минута"), intro.text)
        assertTrue(intro.expectFollowUp)
        assertTrue(e.engine.handle("дальше").text.startsWith("2 из 8: Вращения плечами"))
        assertTrue(e.engine.handle("пропусти").text.startsWith("3 из 8"))
        repeat(5) { e.engine.handle("дальше") }
        val end = e.engine.handle("дальше")
        assertTrue(end.text.startsWith("Тренировка окончена: зарядка, 7 упражнений") && end.text.contains("Отметила: зарядка"), end.text)
        assertTrue(end.endsDialog)
        // Сессия закончилась — «дальше» больше не про зарядку.
        assertTrue(!e.engine.handle("дальше").text.contains("из 8"))
    }

    @Test fun workoutStopEarlyAndPrograms() = runTest {
        val e = env()
        assertTrue(e.engine.handle("давай растяжку").text.startsWith("Растяжка"))
        e.engine.handle("дальше")
        assertTrue(e.engine.handle("хватит").text.startsWith("Закончили: 1 упражнение из 6"))
        assertEquals(Workout.ABS, Workout.start("тренировка на пресс"))
        assertNull(Workout.start("я сделала зарядку"))
        assertNull(Workout.start("тренировка завтра в 7"))
        assertTrue(e.engine.handle("какие есть тренировки").text.startsWith("Есть тренировки"))
    }

    @Test fun reviewParse() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(Review.Ask.Week, Review.parse("итоги недели", today))
        assertEquals(Review.Ask.Day(today.minusDays(1)), Review.parse("что я делала вчера", today))
        assertEquals(Review.Ask.Day(LocalDate.of(2026, 9, 5)), Review.parse("что было 5 сентября", today))
        assertNull(Review.parse("что было вчера на работе", today))
    }

    @Test fun dayTimeline() = runTest {
        val e = env()
        e.engine.handle("запиши заметку про отпуск: море в июле")
        e.engine.handle("потратила 500 на продукты")
        e.engine.handle("добавь задачу купить хлеб")
        e.engine.handle("отметь задачу купить хлеб выполненной")
        e.engine.handle("настроение 4")
        val today = e.engine.handle("что я делала сегодня").text
        assertTrue(today.startsWith("Сегодня:"), today)
        assertTrue(today.contains("Заметки:") && today.contains("Траты: 500") && today.contains("Сделано: Купить хлеб") && today.contains("Настроение: 4 из 5"), today)
        e.time.advanceMillis(Duration.ofDays(1).toMillis())
        assertTrue(e.engine.handle("что было вчера").text.startsWith("Вчера:"))
        assertTrue(e.engine.handle("что было 1 сентября").text.contains("ничего не записано"))
    }

    @Test fun weekSummary() = runTest {
        val e = env()
        e.engine.handle("потратила 1000 на кафе")
        e.engine.handle("настроение 5")
        val text = e.engine.handle("итоги недели").text
        assertTrue(text.startsWith("Итоги недели.") && text.contains("Потрачено 1") && text.contains("кафе") && text.contains("Среднее настроение — 5 из 5"), text)
        assertEquals(text, e.engine.weeklySummary())
    }
}
