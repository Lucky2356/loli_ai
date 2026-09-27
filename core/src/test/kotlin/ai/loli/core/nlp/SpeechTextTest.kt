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

    @Test fun pronunciation() {
        fun say(t: String) = SpeechText.forSpeech(t)
        assertEquals("Будильник на 7 часов.", say("Будильник на 7:00."))
        assertEquals("Встреча в 10 30, потом в 9 ноль 5.", say("Встреча в 10:30, потом в 9:05."))
        assertEquals("Мама: +7 916 123 45 67.", say("Мама: +7 916 123-45-67."))
        assertEquals("Напишите в Телеграм или Вотсап.", say("Напишите в **Telegram** или WhatsApp."))
        assertEquals("Ветер 60 километров в час, до дачи 25 километров.", say("Ветер 60 км/ч, до дачи 25 км."))
        assertEquals("Это было в 2026 года, то есть недавно.", say("Это было в 2026 г., т.е. недавно."))
        assertEquals("Готово! Вот ссылка", say("Готово! 🎉\nВот https://example.com/x"))
        assertEquals("Шаги: Первый. Второй", say("## Шаги:\n1. Первый\n2. Второй"))
        assertEquals("Иду в сад. Потом домой.", say("Иду в сад. Потом домой."))
        assertEquals("Дом номер 5, улица Ленина.", say("Дом № 5, ул. Ленина."))
    }
}
