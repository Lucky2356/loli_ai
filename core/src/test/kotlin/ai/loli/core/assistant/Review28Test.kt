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

/** 2.8: сценарии по расписанию и парковка. */
class Scheduled28Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun createScheduledRoutine() = runTest {
        val e = env()
        val text = e.engine.handle("по будням в 7:30 читай сводку и включай радио").text
        assertTrue(text.startsWith("Готово! По будням в 07:30 выполню сама: что у меня на сегодня; включи радио"), text)
        val r = e.store.reminders.all().single()
        assertEquals("Сценарий: По будням в 07:30", r.text)
        assertEquals(ai.loli.core.model.Recurrence.Frequency.WEEKLY, r.recurrence?.frequency)
        assertTrue(e.engine.handle("мои сценарии").text.contains("По будням в 07:30 (сам)"))
        // Напоминание не про сценарий — обычное.
        e.engine.handle("каждый день в 9 напоминай пить воду")
        assertEquals(2, e.store.reminders.all().size)
        assertTrue(e.store.routines.all().size == 1)
    }

    @Test fun runsScheduledRoutineWhileLocked() = runTest {
        val e = TestEnv(lockPolicy = { LockPolicy.SAFE }).apply { settings = AssistantSettings(useAI = false) }
        // Создать сценарий на блокировке нельзя.
        assertTrue(!e.engine.handle("каждый день в 8 читай сводку").text.startsWith("Готово"))
        e.store.routines.save("Каждый день в 08:00", listOf("что у меня на сегодня"))
        val out = e.engine.runScheduledRoutine("Сценарий: Каждый день в 08:00")
        assertTrue(out != null && out.text.contains("План на") || out!!.text.contains("ничего не запланировано"), out.text)
        assertNull(e.engine.runScheduledRoutine("Сценарий: нет такого"))
        assertNull(e.engine.runScheduledRoutine("Позвонить маме"))
    }

    @Test fun deleteScheduledRemovesReminder() = runTest {
        val e = env()
        e.engine.handle("каждое утро в 7 включай радио")
        val trigger = e.store.routines.all().single().trigger
        e.engine.handle("удали сценарий $trigger")
        assertTrue(e.store.reminders.all().isEmpty())
    }

    @Test fun parkingPhrases() {
        val now = java.time.Instant.parse("2026-09-25T09:00:00Z")
        val z = java.time.ZoneId.of("Europe/Moscow")
        fun p(s: String) = (DevicePhrases.parse(s, now, z)?.command as? DeviceCommand.Parking)
        assertEquals(DeviceCommand.Parking(true, ""), p("запомни где я припарковалась"))
        assertEquals(DeviceCommand.Parking(true, "на третьем уровне, место 45"), p("я припарковался на третьем уровне, место 45"))
        assertEquals(DeviceCommand.Parking(false), p("где моя машина"))
        assertEquals(DeviceCommand.Parking(false), p("где я оставила машину"))
        assertNull(p("я поставила машину на ремонт"))
    }
}
