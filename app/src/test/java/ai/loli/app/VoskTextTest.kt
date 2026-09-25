package ai.loli.app

import ai.loli.app.voice.voskText
import ai.loli.core.nlp.WakeWordMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VoskTextTest {
    @Test fun parsesHypotheses() {
        assertEquals("лоли запиши расход", voskText("""{"text" : "лоли запиши расход"}""", "text"))
        assertEquals("лоли", voskText("""{"partial" : "лоли"}""", "partial"))
        assertEquals("", voskText("not json", "text"))
        assertEquals("", voskText(null, "text"))
    }

    @Test fun wakeWordFromVoskTranscript() {
        val text = voskText("""{"text" : "лолли запиши расход пятьсот рублей"}""", "text")
        assertEquals("запиши расход пятьсот рублей", assertNotNull(WakeWordMatcher("Лоли").match(text)).command)
    }
}
