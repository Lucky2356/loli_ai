package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.export.CsvExport
import ai.loli.core.review.ChatSearch
import ai.loli.core.skills.Flashcards
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.9: карточки, поиск по разговорам, выгрузка в таблицу, голосовая заметка. */
class Study29Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun flashcardsFlow() = runTest {
        val e = env()
        assertTrue(e.engine.handle("запомни слово apple — яблоко").text.startsWith("Добавила карточку: apple — яблоко"))
        e.engine.handle("добавь карточку столица Франции — Париж")
        e.engine.handle("запомни слово dog - собака, пёс")
        assertTrue(e.engine.handle("сколько у меня карточек").text.startsWith("Карточек: 3"))
        val q = e.engine.handle("проверь меня по словам")
        assertTrue(q.text.contains("1 из 3") && q.awaitingAnswer, q.text)
        // Отвечаем по тому, что спросили.
        var reply = q.text
        var right = 0
        repeat(3) { i ->
            val answer = when {
                reply.contains("apple") -> "яблоко"
                reply.contains("dog") -> "пёс"
                else -> "не знаю"
            }
            if (answer != "не знаю") right++
            reply = e.engine.handle(answer).text
        }
        assertTrue(reply.contains("Итог: $right из 3"), reply)
        // Угаданные ушли во вторую коробку, неугаданная — в первую.
        val boxes = Flashcards(e.store.memories).all().associate { it.front to it.box }
        assertEquals(2, boxes["apple"]); assertEquals(1, boxes["столица Франции"])
    }

    @Test fun answerMatching() {
        assertTrue(Flashcards.correct("Яблоко", "яблоко"))
        assertTrue(Flashcards.correct("пес", "собака, пёс"))
        assertTrue(Flashcards.correct("это париж", "Париж"))
        assertTrue(Flashcards.correct("собоака", "собака"))
        assertTrue(!Flashcards.correct("груша", "яблоко"))
        assertNull(Flashcards.parse("запомни что я люблю чай"))
    }

    @Test fun chatSearch() = runTest {
        val e = env()
        e.engine.handle("запиши заметку ремонт на кухне: поменять плитку")
        e.time.advanceMillis(Duration.ofDays(2).toMillis())
        e.engine.handle("добавь задачу купить краску для ремонта")
        e.engine.handle("какая сегодня дата")
        val r = e.engine.handle("что я говорила про ремонт").text
        assertTrue(r.startsWith("Про «ремонт» вы говорили:") && r.contains("ремонт на кухне") && r.contains("краску для ремонта"), r)
        assertTrue(!r.contains("что я говорила"), r)
        val y = e.engine.handle("что я говорила про ремонт сегодня").text
        assertTrue(y.contains("краску") && !y.contains("плитку"), y)
        assertTrue(e.engine.handle("что я говорила про отпуск").text.startsWith("Не нашла"))
        assertEquals(ChatSearch.Ask(null, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24)), ChatSearch.parse("о чём мы говорили вчера", LocalDate.of(2026, 9, 25)))
    }

    @Test fun csv() {
        assertEquals("'=1+1", CsvExport.cell("=1+1").trim('"'))
        assertEquals("\"а;б\"", CsvExport.cell("а;б"))
        assertEquals("\"он сказал \"\"да\"\"\"", CsvExport.cell("он сказал \"да\""))
    }

    @Test fun exportCommand() = runTest {
        val e = env()
        val n = "выгрузи расходы за прошлый месяц в таблицу"
        assertEquals(AssistantAction.Export("expenses", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), PersonalCommands.parse(n, n, LocalDate.of(2026, 9, 25)))
        assertTrue(e.engine.handle("выгрузи расходы в таблицу").text.startsWith("Выгружать нечего"))
        e.engine.handle("потратила 500 на кафе")
        // В тестах телефона нет: выгрузка доходит до команды телефону, а та отвечает «не умею».
        assertTrue(!e.engine.handle("выгрузи расходы в эксель").text.startsWith("Выгружать нечего"))
    }

    @Test fun voiceMemoCommand() {
        val now = java.time.Instant.parse("2026-09-25T09:00:00Z")
        val z = java.time.ZoneId.of("Europe/Moscow")
        assertEquals(DeviceCommand.VoiceMemo, DevicePhrases.parse("запиши голосовую заметку", now, z)?.command)
        assertEquals(DeviceCommand.VoiceMemo, DevicePhrases.parse("включи диктофон", now, z)?.command)
        assertTrue(!SpecialCommands.isDictationStart("запиши голосовую заметку"))
        assertTrue(SpecialCommands.isDictationStart("надиктую заметку"))
    }
}
