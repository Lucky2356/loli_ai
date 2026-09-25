package ai.loli.core.nlp

import ai.loli.core.voice.SpeechText
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {
    @Test fun listsAreShortenedForVoice() {
        val text = "Задачи (8):\n" + (1..8).joinToString("\n") { "• задача $it" }
        val spoken = SpeechText.forSpeech(text)
        assertEquals("Задачи (8): задача 1. задача 2. задача 3. задача 4. задача 5. и ещё 3.", spoken)
    }
}
