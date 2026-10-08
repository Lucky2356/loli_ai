package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.8: «где лежит», долги, свои списки и чек-листы. */
class Personal28Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    // --- Вещи

    @Test fun putAndWhere() = runTest {
        val e = env()
        val saved = e.engine.handle("положила паспорт в верхний ящик комода").text
        assertTrue(saved.contains("Паспорт — положили в верхний ящик комода"), saved)
        assertEquals("Паспорт — положили в верхний ящик комода.", e.engine.handle("где мой паспорт?").text)
        assertEquals("Паспорт — положили в верхний ящик комода.", e.engine.handle("куда я положила паспорт").text)
    }

    @Test fun accusativeItemAndNewPlaceReplacesOld() = runTest {
        val e = env()
        e.engine.handle("я положил зарядку в рюкзак")
        e.engine.handle("запомни, что зарядка лежит на полке в прихожей")
        assertEquals(1, e.store.memories.all().size, e.store.memories.all().toString())
        assertEquals("Зарядка — лежит на полке в прихожей.", e.engine.handle("где зарядка").text)
    }

    @Test fun unknownThing() = runTest {
        val e = env()
        assertTrue(e.engine.handle("где мои ключи").text.startsWith("Не знаю, где ключи"))
        // «Где находится Казань» — не о вещи: не отвечаем «не знаю, где».
        assertTrue(!e.engine.handle("где находится Казань").text.startsWith("Не знаю, где"))
    }

    @Test fun thingPhrasesNotSwallowed() {
        assertNull(PersonalCommands.parse("поставила будильник на 7", "поставила будильник на 7"))
        assertNull(PersonalCommands.parse("положила 500 рублей на карту", "положила 500 рублей на карту"))
        assertNull(PersonalCommands.parse("что лежит в холодильнике", "что лежит в холодильнике"))
        assertNull(PersonalCommands.where("где я работаю"))
        assertNull(PersonalCommands.where("где ближайшая аптека"))
        assertNull(PersonalCommands.where("где ты"))
    }

    // --- Долги

    @Test fun debtsInAndOut() = runTest {
        val e = env()
        assertTrue(e.engine.handle("Саша должен мне 500 рублей").text.contains("Саша, 500 ₽ — вам должны"))
        assertTrue(e.engine.handle("я должна Маше 200").text.contains("Маше, 200 ₽ — должны вы"))
        e.engine.handle("одолжила Пете 2 тысячи")
        val all = e.engine.handle("кто мне должен").text
        assertTrue(all.contains("Вам должны:") && all.contains("Саша — 500 ₽") && all.contains("Пете — 2000 ₽"), all)
        assertTrue(all.contains("Вы должны:") && all.contains("Маше — 200 ₽"), all)
        assertTrue(e.engine.handle("сколько мне должен Саша").text.contains("500 ₽ — должны вам"))
    }

    @Test fun debtsAddUpAndSettle() = runTest {
        val e = env()
        e.engine.handle("Саша должен мне 500")
        val more = e.engine.handle("я дала Саше 300 в долг").text
        assertTrue(more.contains("Всего по Саша: 800 ₽ должны вам"), more)
        val part = e.engine.handle("Саша вернул 200").text
        assertTrue(part.contains("Отметила возврат: 200 ₽") && part.contains("Осталось: 600 ₽"), part)
        val full = e.engine.handle("Саша вернул долг").text
        assertTrue(full.contains("вы в расчёте"), full)
        assertTrue(e.engine.handle("кто мне должен").text.startsWith("Долгов нет"))
    }

    @Test fun debtWithReminder() = runTest {
        val e = env()
        val text = e.engine.handle("Саша должен мне 500, напомни через неделю").text
        assertTrue(text.contains("Напомню"), text)
        assertTrue(e.store.reminders.all().single().text.contains("Напомнить Саша про долг 500 ₽"))
    }

    @Test fun iReturned() = runTest {
        val e = env()
        e.engine.handle("я заняла у Маши 1000")
        val text = e.engine.handle("я вернула Маше 400").text
        assertTrue(text.contains("Осталось: 600 ₽ должны вы"), text)
    }

    @Test fun notDebts() {
        assertNull(PersonalCommands.parse("я должна позвонить маме", "я должна позвонить маме"))
        assertNull(PersonalCommands.parse("я должен сделать 5 отжиманий", "я должен сделать 5 отжиманий"))
    }

    // --- Списки

    @Test fun customListFlow() = runTest {
        val e = env()
        val ask = e.engine.handle("создай список фильмов")
        assertTrue(ask.text.contains("Завела список «Фильмы». Что в него добавить?"), ask.text)
        assertTrue(e.engine.handle("Интерстеллар и Начало").text.contains("Добавила в список «Фильмы»"))
        e.engine.handle("добавь в список фильмов Дюна")
        e.engine.handle("добавь Матрица в список фильмов")
        val list = e.engine.handle("что в списке фильмов").text
        assertTrue(list.startsWith("Фильмы (4)"), list)
        assertTrue(e.engine.handle("вычеркни Дюна из списка фильмов").text.contains("Осталось: 3"))
        val lists = e.engine.handle("какие у меня списки").text
        assertTrue(lists.contains("Фильмы — 3 пункта"), lists)
    }

    @Test fun templates() = runTest {
        val e = env()
        val text = e.engine.handle("собери список в отпуск").text
        assertTrue(text.contains("Собрала список «В поездку»") && text.contains("паспорт"), text)
        assertTrue(e.engine.handle("что взять на море").text.contains("«На море»"))
        val bought = e.engine.handle("вычеркни паспорт").text
        assertTrue(bought.contains("Вычеркнула"), bought)
    }

    @Test fun listNames() {
        assertEquals("Фильмы", PersonalCommands.listName("фильмов"))
        assertEquals("Подарки", PersonalCommands.listName("подарков"))
        assertEquals("Желания", PersonalCommands.listName("желаний"))
        assertEquals("Книг", PersonalCommands.listName("книг"))
    }
}
