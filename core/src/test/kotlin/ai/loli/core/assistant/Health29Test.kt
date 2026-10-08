package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.personal.DeadlineBook
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.9: давление, вес, сон, счётчики, сроки, цели накоплений, отправка списка. Сегодня пятница 25.09.2026. */
class Health29Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun bloodPressure() = runTest {
        val e = env()
        assertEquals("Записала давление 130 на 85, пульс 70.", e.engine.handle("давление 130 на 85, пульс 70").text)
        assertTrue(e.engine.handle("у меня давление 150 на 95").text.contains("Выше нормы"))
        val r = e.engine.handle("какое у меня давление").text
        assertTrue(r.startsWith("Последнее давление — 150 на 95") && r.contains("в среднем 140 на 90"), r)
        assertTrue(e.engine.handle("мой пульс").text.contains("последний 70"))
    }

    @Test fun weightAndSleep() = runTest {
        val e = env()
        assertEquals("Записала вес 70 кг.", e.engine.handle("вес 70").text)
        e.time.advanceMillis(Duration.ofDays(3).toMillis())
        val second = e.engine.handle("я вешу 68,5").text
        assertTrue(second.startsWith("Записала вес 68,5 кг.") && second.contains("−1,5 кг"), second)
        assertTrue(e.engine.handle("мой вес за месяц").text.contains("−1,5 кг"))
        assertTrue(e.engine.handle("спала 5 часов").text.contains("Маловато"))
        assertEquals("Записала сон: 7 с половиной часов.", e.engine.handle("поспала 7 с половиной часов").text)
        assertTrue(e.engine.handle("сколько я спала на этой неделе").text.startsWith("Сон за неделю: в среднем 6,3 ч"))
    }

    @Test fun meters() = runTest {
        val e = env()
        val first = e.engine.handle("показания: холодная вода 345, горячая вода 210, свет 12500").text
        assertTrue(first.startsWith("Записала показания: Холодная вода 345, Горячая вода 210, Электричество 12500.") && first.contains("напоминай сдавать"), first)
        e.time.advanceMillis(Duration.ofDays(30).toMillis())
        val second = e.engine.handle("показания холодная вода 352").text
        assertTrue(second.contains("+7 м³"), second)
        assertTrue(e.engine.handle("мои показания").text.contains("израсходовано 7 м³"))
        e.engine.handle("напоминай сдавать показания 20 числа")
        assertEquals(20, e.store.reminders.all().single().recurrence?.dayOfMonth)
        // Питьевая вода — по-прежнему привычка, а не счётчик.
        assertTrue(e.engine.handle("выпила стакан воды").text.contains("стакан"))
    }

    @Test fun deadlines() = runTest {
        val e = env()
        val t = e.engine.handle("гарантия на телевизор до мая 2027").text
        assertTrue(t.startsWith("Запомнила: гарантия на телевизор — до 31 мая 2027. Напомню за месяц и в сам день."), t)
        assertEquals(2, e.store.reminders.all().size)
        e.engine.handle("молоко до понедельника")
        val soon = e.engine.handle("что скоро истекает").text
        assertTrue(soon.contains("Молоко — до 28 сентября (через 3 дня)") && !soon.contains("телевизор"), soon)
        assertTrue(e.engine.handle("когда кончается гарантия на телевизор").text.startsWith("Гарантия на телевизор — до 31 мая 2027"))
        assertTrue(e.engine.handle("паспорт истекает в 2030").text.contains("до 1 января 2030"))
        e.engine.handle("удали гарантию на телевизор")
        assertTrue(e.store.reminders.all().none { it.text.contains("телевизор") })
    }

    @Test fun deadlineDates() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(LocalDate.of(2027, 5, 31), DeadlineBook.date("мая 2027", today))
        assertEquals(LocalDate.of(2026, 12, 31), DeadlineBook.date("конца декабря", today))
        assertEquals(LocalDate.of(2030, 1, 1), DeadlineBook.date("2030", today))
        assertNull(DeadlineBook.parse("работаю до пятницы", today))
        assertNull(DeadlineBook.parse("отпуск до понедельника", today))
    }

    @Test fun goals() = runTest {
        val e = env()
        assertTrue(e.engine.handle("коплю на отпуск 100 тысяч").text.startsWith("Завела цель «Отпуск»: 100000 ₽"))
        assertTrue(e.engine.handle("отложила 15000").text.contains("15000 ₽ из 100000 ₽ (15%), осталось 85000 ₽"))
        e.engine.handle("хочу накопить 50000 на новый телефон")
        assertTrue(e.engine.handle("отложила 5000").text.startsWith("На какую цель?"))
        assertTrue(e.engine.handle("отложила 5000 на телефон").text.contains("«Новый телефон»: 5000 ₽ из 50000 ₽ (10%)"))
        assertTrue(e.engine.handle("сколько осталось до отпуска").text.contains("осталось 85000 ₽"))
        assertTrue(e.engine.handle("сняла 5000 из отпуска").text.contains("10000 ₽ из 100000 ₽"))
        val all = e.engine.handle("мои накопления").text
        assertTrue(all.startsWith("Накопления:") && all.contains("Отпуск") && all.contains("Новый телефон"), all)
    }

    @Test fun sendListParse() {
        val n = "отправь список покупок маше"
        assertEquals(AssistantAction.SendList("Покупки", "Маше", null), PersonalCommands.parse("отправь список покупок Маше", n))
        assertEquals(AssistantAction.SendList("Фильмы", null, "телеграм"), PersonalCommands.parse("скинь список фильмов в телеграм", "скинь список фильмов в телеграм"))
        assertNull(PersonalCommands.parse("отправь список покупок", "отправь список покупок"))
    }
}
