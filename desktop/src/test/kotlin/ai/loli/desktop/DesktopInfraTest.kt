package ai.loli.desktop

import ai.loli.core.assistant.QuickAdd
import ai.loli.core.util.Logger
import ai.loli.desktop.voice.SpeechOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверки ПК-версии без окна: запускаются в CI на Windows (синтезатор, DPAPI) и на любой системе. */
class DesktopInfraTest {
    private val dir: File = Files.createTempDirectory("loli-test").toFile()
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test fun settingsSurviveRestart() {
        val file = File(dir, "settings.properties")
        val s = DesktopSettings(file)
        s.update { it.copy(userName = "Аня", windowWidth = 1400, windowX = 120, windowMaximized = true, speechRate = 3) }
        s.flush()
        val again = DesktopSettings(file).value
        assertEquals("Аня", again.userName)
        assertEquals(1400, again.windowWidth)
        assertEquals(120, again.windowX)
        assertNull(again.windowY)
        assertTrue(again.windowMaximized)
        assertEquals(3, again.speechRate)
    }

    @Test fun settingsAreWrittenInBackground() {
        val file = File(dir, "settings.properties")
        val s = DesktopSettings(file)
        repeat(20) { i -> s.update { it.copy(city = "Казань$i") } }
        Thread.sleep(1500)
        assertEquals("Казань19", DesktopSettings(file).value.city)
    }

    @Test fun secretsRoundTripAndAreNotPlainText() {
        val file = File(dir, "secrets.properties")
        val store = SecretStore(file)
        assertTrue(store.put("ai_key_openai", "sk-test-1234567890abcdef"))
        assertEquals("sk-test-1234567890abcdef", store.get("ai_key_openai"))
        assertEquals("sk-test-1234567890abcdef", SecretStore(file).get("ai_key_openai"))
        if (windows) assertFalse(file.readText().contains("c2stdGVzdC0xMjM0NTY3ODkwYWJjZGVm"), "в Windows ключ должен быть зашифрован DPAPI")
        assertTrue(store.put("ai_key_openai", null))
        assertNull(SecretStore(file).get("ai_key_openai"))
        assertNull(store.get("ai_key_openai"))
    }

    @Test fun secondInstanceAsksFirstToShow() {
        val shown = CountDownLatch(1)
        val first = assertNotNull(SingleInstance.acquire(dir) { shown.countDown() })
        try {
            assertNull(SingleInstance.acquire(dir) { })
            assertTrue(shown.await(5, TimeUnit.SECONDS), "первая копия должна получить просьбу показать окно")
        } finally {
            first.release()
        }
        val again = assertNotNull(SingleInstance.acquire(dir) { }, "после выхода блокировка освобождается")
        again.release()
    }

    @Test fun logRotatesAndRedactsKeys() {
        val sink = FileLogSink(File(dir, "logs"), maxBytes = 2_000)
        val old = Logger.sink
        Logger.sink = sink
        try {
            repeat(60) { Logger.w("Test", "строка $it ключ sk-proj-ABCDEFGHIJKLMNOP") }
        } finally {
            Logger.sink = old
        }
        val log = File(dir, "logs/loli.log").readText()
        assertTrue(File(dir, "logs/loli.1.log").exists())
        assertFalse(log.contains("ABCDEFGHIJKLMNOP"))
        assertTrue(log.contains("строка 59"))
    }

    @Test fun quickAddAndReminderTicker() = runBlocking {
        val c = DesktopContainer(dir)
        try {
            val (ok, text) = c.quickAdd(QuickAdd.Kind.TASK, "купить хлеб завтра")
            assertTrue(ok, text)
            assertEquals(1, c.store.tasks.all().size)
            assertFalse(c.quickAdd(QuickAdd.Kind.REMINDER, "позвонить маме").first)
            assertTrue(c.quickAdd(QuickAdd.Kind.EXPENSE, "кофе 250").first)
            assertEquals(25_000L, c.store.expenses.all().single().amountMinor)

            // Просроченное напоминание показывается один раз, с пометкой «Было в…».
            c.store.reminders.create("Позвонить маме", Instant.now().minusSeconds(600), null, c.time.zone().id)
            val shown = ArrayList<String>()
            val ticker = ReminderTicker(c) { _, t -> shown += t }
            ticker.tick()
            ticker.tick()
            assertEquals(1, shown.size, shown.toString())
            assertTrue(shown.single().startsWith("Позвонить маме\nБыло в "), shown.single())
            assertTrue(c.store.reminders.active().isEmpty())

            c.store.conversations.add("t", ai.loli.core.model.MessageRole.USER, "привет")
            c.clearChat()
            assertTrue(c.store.conversations.recent().isEmpty())
        } finally {
            c.close()
        }
    }

    @Test fun windowsSpeechWorkerAnswersAndSpeaks() {
        assumeTrue("только Windows", windows)
        val out = SpeechOutput()
        try {
            assertTrue(out.ping(), "постоянный синтезатор PowerShell должен запуститься и ответить")
            assertTrue(out.muteForTest())
            val start = System.nanoTime()
            out.speak("Проверка синтезатора. Кавычки ' \" и знаки | ; \$(Get-Date) не исполняются.", "", 0)
            assertTrue(out.ping(), "после фразы синтезатор жив")
            println("Голоса Windows: ${out.voices()} · фраза за ${(System.nanoTime() - start) / 1_000_000} мс")
            out.stop()
            assertTrue(out.ping(), "после «замолчать» поднимается новый синтезатор")
        } finally {
            out.shutdown()
        }
    }
}
