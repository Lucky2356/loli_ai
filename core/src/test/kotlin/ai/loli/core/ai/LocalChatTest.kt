package ai.loli.core.ai

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalChatTest {
    private class FakeChat : LocalChat {
        val asked = ArrayList<String>()
        override val available = true
        override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int): String {
            asked += history.last().content
            return "Из-за рассеяния света в атмосфере."
        }
    }

    @Test fun questionsGoToLocalModelCommandsDoNot() = runTest {
        val chat = FakeChat()
        val env = TestEnv(localChat = chat).apply { settings = AssistantSettings(useAI = false) }
        val r = env.engine.handle("почему небо голубое")
        assertEquals("Из-за рассеяния света в атмосфере.", r.text)
        assertEquals(listOf("почему небо голубое"), chat.asked)
        env.engine.handle("запиши расход 300 рублей на кофе")
        assertEquals(1, env.store.expenses.all().size)
        env.engine.handle("что у меня на сегодня")
        assertEquals(1, chat.asked.size)
    }

    @Test fun looksLikeChat() {
        listOf("почему небо голубое", "как приготовить плов", "посоветуй фильм на вечер", "что лучше чай или кофе", "расскажи анекдот про программиста", "можно ли есть грибы сырыми", "ты любишь музыку")
            .forEach { assertTrue(LocalChat.looksLikeChat(it), it) }
        listOf("купила хлеб", "мама приедет в субботу", "позвонить врачу")
            .forEach { assertFalse(LocalChat.looksLikeChat(it), it) }
    }
}
