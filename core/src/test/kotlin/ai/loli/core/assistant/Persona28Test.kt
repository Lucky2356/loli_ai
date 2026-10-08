package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.8: Лоли предлагает запомнить, характер, забота о настроении. */
class Persona28Test {
    private fun env(p: Persona = Persona.CARING) = TestEnv().apply { settings = AssistantSettings(useAI = false, persona = p) }

    @Test fun offersToRememberFact() = runTest {
        val e = env()
        val ask = e.engine.handle("у меня кот Барсик")
        assertEquals("Запомнить: «У меня кот Барсик»?", ask.text)
        assertTrue(ask.awaitingAnswer)
        assertTrue(e.engine.handle("да").text.contains("Запомнила"))
        assertEquals("У меня кот Барсик", e.store.memories.all().single().content)
        // Уже помню — второй раз не спрашиваю.
        assertTrue(!e.engine.handle("у меня кот Барсик").text.startsWith("Запомнить"))
    }

    @Test fun declineOffer() = runTest {
        val e = env()
        e.engine.handle("я по профессии дизайнер")
        assertEquals("Хорошо, не запоминаю.", e.engine.handle("нет").text)
        assertTrue(e.store.memories.all().isEmpty())
    }

    @Test fun notFacts() {
        assertNull(FactOffer.offer("у меня кот заболел, что делать"))
        assertNull(FactOffer.offer("у меня сын?"))
        assertNull(FactOffer.offer("мой папа звонил"))
        assertNull(FactOffer.offer("у меня завтра встреча"))
    }

    @Test fun switchPersona() = runTest {
        var chosen: Persona? = null
        val e = TestEnv().apply { settings = AssistantSettings(useAI = false); onPersona = { chosen = it } }
        val engine = e.engine
        assertEquals("Принято. Коротко и по делу.", engine.handle("будь деловой").text)
        assertEquals(Persona.BUSINESS, chosen)
        engine.handle("стань шутливой")
        assertEquals(Persona.PLAYFUL, chosen)
        assertTrue(engine.handle("какой у тебя характер").text.startsWith("Сейчас я заботливая"))
    }

    @Test fun smallTalkByPersona() = runTest {
        assertEquals("Слушаю.", env(Persona.BUSINESS).engine.handle("привет").text)
        assertEquals("Пожалуйста.", env(Persona.BUSINESS).engine.handle("спасибо").text)
        assertTrue(env(Persona.PLAYFUL).engine.handle("как дела").text.startsWith("Лучше всех"))
        assertTrue(env().engine.handle("как дела").text.contains("А у вас как?"))
    }

    @Test fun businessHasNoExclamations() = runTest {
        val text = env(Persona.BUSINESS).engine.handle("добавь задачу купить хлеб").text
        assertTrue(!text.contains('!'), text)
    }

    @Test fun caresAboutLowMood() = runTest {
        val e = env()
        e.engine.handle("настроение 2")
        val second = e.engine.handle("настроение 1").text
        assertTrue(second.contains("Уже не первый трудный день"), second)
        assertTrue(e.engine.handle("привет").text.contains("давай подышим"))
    }
}
