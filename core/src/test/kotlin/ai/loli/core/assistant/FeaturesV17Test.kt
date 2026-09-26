package ai.loli.core.assistant

import ai.loli.core.ScriptedAI
import ai.loli.core.TestEnv
import ai.loli.core.model.Recurrence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Новые функции 1.7.0: покупки, сценарии, дни рождения, секретные заметки, перевод. */
class FeaturesV17Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun shoppingList() = runTest {
        val env = env()
        env.engine.handle("добавь в покупки молоко, хлеб и яйца")
        assertEquals(listOf("Молоко", "Хлеб", "Яйца"), env.store.shopping.all().map { it.text })
        env.engine.handle("Лоли, запиши сыр в список покупок")
        assertEquals(4, env.store.shopping.all().size)
        val what = env.engine.handle("что купить?")
        assertTrue("Сыр" in what.text && "Молоко" in what.text, what.text)
        env.engine.handle("купила молоко и хлеб")
        assertEquals(setOf("Молоко", "Хлеб"), env.store.shopping.all().filter { it.done }.map { it.text }.toSet())
        assertTrue(env.store.expenses.all().isEmpty(), "покупка из списка — не расход")
        env.engine.handle("вычеркни яйца из покупок")
        env.engine.handle("убери купленное")
        assertEquals(listOf("Сыр"), env.store.shopping.all().map { it.text })
        // Дубликат не создаётся
        env.engine.handle("добавь в покупки сыр")
        assertEquals(1, env.store.shopping.all().size)
    }

    @Test fun boughtWithAmountIsExpense() = runTest {
        val env = env()
        env.engine.handle("добавь в покупки молоко")
        env.engine.handle("купила молоко за 90 рублей")
        assertEquals(1, env.store.expenses.all().size)
    }

    @Test fun routines() = runTest {
        val env = env()
        val r = env.engine.handle("когда я говорю спокойной ночи, добавь задачу проверить почту и напомни через 10 минут выпить воды")
        assertTrue(r.changedData, r.text)
        val saved = env.store.routines.all().single()
        assertEquals("Спокойной ночи", saved.trigger)
        assertEquals(2, saved.commands.size, saved.commands.toString())
        env.engine.handle("Спокойной ночи!")
        assertEquals(listOf("Проверить почту"), env.store.tasks.all().map { it.title })
        assertEquals(listOf("Выпить воды"), env.store.reminders.all().map { it.text })
        assertTrue("спокойной ночи" in env.engine.handle("какие у меня сценарии").text.lowercase())
        env.engine.handle("удали сценарий спокойной ночи")
        assertTrue(env.store.routines.all().isEmpty())
    }

    @Test fun birthdays() = runTest {
        val env = env()
        env.engine.handle("день рождения мамы 5 мая")
        val yearly = env.store.reminders.all().filter { it.recurrence?.frequency == Recurrence.Frequency.YEARLY }
        assertEquals(2, yearly.size)
        assertTrue(env.engine.handle("когда день рождения у мамы?").text.contains("5 мая"))
        env.engine.handle("запомни, что у Саши день рождения 3 октября")
        assertTrue(env.engine.handle("у кого дни рождения в этом месяце").text.let { "Саши" !in it })
        assertTrue(env.engine.handle("покажи все дни рождения").text.contains("Саши"))
        // повторная запись не плодит напоминания
        env.engine.handle("день рождения мамы 6 мая")
        assertEquals(4, env.store.reminders.all().size)
    }

    @Test fun secretNotes() = runTest {
        val env = env()
        env.engine.handle("запиши секретную заметку код от сейфа 1234")
        assertEquals(1, env.store.secrets.all().size)
        assertTrue(env.store.notes.all().isEmpty(), "секретная заметка не попадает в обычные")
    }

    @Test fun translationOfflineAndAI() = runTest {
        val env = env()
        val off = env.engine.handle("как по-английски спасибо")
        assertEquals("Thank you", off.text)
        assertEquals("en", off.speakLanguage)
        val env2 = TestEnv()
        env2.ai = ScriptedAI { "Where is the train station?" }
        val on = env2.engine.handle("переведи на английский где вокзал")
        assertEquals("Where is the train station?", on.text)
        assertEquals("en", on.speakLanguage)
        assertNull(SpecialCommands().translation("добавь задачу купить хлеб"))
        assertEquals("где вокзал", SpecialCommands().translation("переведи на немецкий где вокзал")?.phrase)
    }
}

class DictationTest {
    @Test fun dictation() = kotlinx.coroutines.test.runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        assertTrue(env.engine.handle("надиктую заметку", InputSource.VOICE).dictation)
        assertTrue(!env.engine.handle("надиктую заметку", InputSource.TEXT).dictation)
        assertTrue(SpecialCommands.endsDictation("и не забыть купить краску. Готово"))
        val r = env.engine.saveDictation("идеи для ремонта кухни поменять плитку и не забыть купить краску готово")
        assertTrue(r.changedData)
        val note = env.store.notes.all().single()
        assertEquals("Идеи для ремонта кухни поменять плитку", note.title)
        assertEquals("Идеи для ремонта кухни поменять плитку и не забыть купить краску", note.content)
    }
}
