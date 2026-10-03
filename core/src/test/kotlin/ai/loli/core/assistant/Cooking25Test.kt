package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.model.NoteKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2.5.0: режим готовки. */
class Cooking25Test {
    private val recipe = "Ингредиенты: свёкла, капуста, картофель, мясо.\n1. Сварить мясо 40 минут.\n2. Нарезать овощи.\n3. Добавить овощи и варить 20 минут."

    private val calls = mutableListOf<String>()
    private val device = object : DeviceController {
        override suspend fun perform(command: DeviceCommand): DeviceResult { calls += command::class.simpleName.orEmpty(); return DeviceResult("ок") }
    }

    private suspend fun env() = TestEnv(device = device).apply {
        settings = AssistantSettings(useAI = false)
        store.notes.create(NoteKind.NOTE, "Рецепт борща", recipe)
    }

    @Test fun walksThroughSteps() = runTest {
        val e = env()
        val intro = e.engine.handle("давай приготовим борщ")
        assertTrue(intro.text.contains("Готовим: борща") || intro.text.contains("Готовим: Борща") || intro.text.contains("Готовим"), intro.text)
        assertTrue(intro.text.contains("свёкла") && intro.text.contains("Сварить мясо"), intro.text)
        assertTrue(intro.expectFollowUp)
        assertTrue(e.engine.handle("дальше").text.startsWith("Шаг 2 из 3. Нарезать овощи"))
        assertTrue(e.engine.handle("повтори").text.startsWith("Шаг 2 из 3"))
        assertTrue(e.engine.handle("назад").text.startsWith("Шаг 1 из 3"))
        assertTrue(e.engine.handle("шаг 3").text.contains("варить 20 минут"))
        val last = e.engine.handle("дальше")
        assertTrue(last.text.contains("приятного аппетита"), last.text)
        assertTrue(last.endsDialog)
    }

    @Test fun timerFromStepAndStop() = runTest {
        val e = env()
        e.engine.handle("давай приготовим борщ")
        e.engine.handle("таймер")
        assertTrue(calls.any { it == "Timer" }, calls.toString())
        val stop = e.engine.handle("хватит готовить")
        assertTrue(stop.endsDialog)
        // после остановки «дальше» — уже не про готовку
        assertTrue(!e.engine.handle("дальше").text.startsWith("Шаг"))
    }

    @Test fun missingRecipeExplainsHowToAdd() = runTest {
        val e = env()
        val r = e.engine.handle("давай приготовим плов").text
        assertTrue(r.contains("нет рецепта"), r)
    }

    @Test fun unnumberedStepsAreNotIngredients() = runTest {
        val e = TestEnv(device = device).apply {
            settings = AssistantSettings(useAI = false)
            store.notes.create(NoteKind.NOTE, "Омлет", "Ингредиенты: яйца, молоко, соль\nВзбить яйца с молоком.\nВылить на сковороду и жарить 5 минут.")
        }
        val intro = e.engine.handle("давай приготовим омлет").text
        assertTrue(intro.contains("Понадобится: яйца, молоко, соль.") && intro.contains("Всего 2 шага"), intro)
    }

    @Test fun asksForDishAndTakesTheNextPhrase() = runTest {
        val e = env()
        assertTrue(e.engine.handle("давай готовить").text.startsWith("Что готовим"))
        assertTrue(e.engine.handle("борщ").text.contains("Сварить мясо"))
    }
}
