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
            val ticker = ReminderTicker(c) { shown += it.full }
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

    @Test fun newerVersionComparison() {
        assertTrue(UpdateChecker.newer("2.12.0", "2.11.0"))
        assertTrue(UpdateChecker.newer("2.11.10", "2.11.9"))
        assertTrue(UpdateChecker.newer("3.0", "2.99.99"))
        assertFalse(UpdateChecker.newer("2.11.0", "2.11.0"))
        assertFalse(UpdateChecker.newer("2.10.5", "2.11.0"))
        assertFalse(UpdateChecker.newer("2.11.0-beta.1", "2.11.0"))
    }

    @Test fun newSettingsSurviveRestart() {
        val file = File(dir, "settings.properties")
        val s = DesktopSettings(file)
        s.update { it.copy(voiceMode = "windows", loliVoice = "irina", loliSpeed = 120, accent = "teal", reminderPopup = false, reminderSound = false) }
        s.flush()
        val v = DesktopSettings(file).value
        assertEquals("windows", v.voiceMode)
        assertEquals("irina", v.loliVoice)
        assertEquals(120, v.loliSpeed)
        assertEquals("teal", v.accent)
        assertFalse(v.reminderPopup)
        assertFalse(v.reminderSound)
    }

    @Test fun backupMovesDataBetweenComputers() = runBlocking {
        val a = DesktopContainer(File(dir, "a"))
        val b = DesktopContainer(File(dir, "b"))
        try {
            a.store.notes.create(ai.loli.core.model.NoteKind.NOTE, "Пароль от Wi-Fi", "на обороте роутера")
            a.store.tasks.create("Купить хлеб")
            a.settings.update { it.copy(userName = "Аня", city = "Казань") }
            val bytes = a.backup.export("пароль-123".toCharArray(), includeChat = false)
            val report = b.backup.restore(bytes, "пароль-123".toCharArray())
            assertEquals(2, report.total)
            assertEquals("Пароль от Wi-Fi", b.store.notes.all().single().title)
            assertEquals("Аня", b.settings.value.userName)
            assertEquals("Казань", b.settings.value.city)
            val wrong = runCatching { b.backup.restore(bytes, "не тот пароль".toCharArray()) }
            assertTrue(wrong.isFailure)
        } finally {
            a.close(); b.close()
        }
    }

    @Test fun alertsShowSnoozeAndDismiss() = runBlocking {
        val c = DesktopContainer(dir)
        try {
            c.settings.update { it.copy(reminderSound = false) }
            c.alerts.show("Напоминание", "Выпить таблетку", null, routine = false)
            val alert = c.alerts.alerts.value.single()
            c.alerts.snooze(alert, java.time.Duration.ofMinutes(10))
            assertTrue(c.alerts.alerts.value.isEmpty())
            val r = c.store.reminders.active().single()
            assertEquals("Выпить таблетку", r.text)
            assertTrue(r.triggerAt.isAfter(Instant.now().plusSeconds(500)))
            repeat(6) { c.alerts.show("Напоминание", "№$it", null, routine = false) }
            assertEquals(4, c.alerts.alerts.value.size, "больше четырёх сразу не показываем")
            c.alerts.dismissAll()
            assertTrue(c.alerts.alerts.value.isEmpty())
        } finally {
            c.close()
        }
    }

    @Test fun builtInRussianVoiceSynthesizes() {
        // В CI голос по умолчанию лежит в desktop/resources/common/loli-voice (scripts/prepare-desktop-resources.ps1).
        val bundled = File("resources/common")
        assumeTrue("нет вшитого голоса (запуск без ресурсов установщика)", File(bundled, "loli-voice").isDirectory)
        System.setProperty("compose.application.resources.dir", bundled.absolutePath)
        val voice = ai.loli.desktop.voice.LoliVoice(dir)
        assertTrue(voice.isReady(ai.loli.desktop.voice.LoliVoice.DEFAULT), "вшитый голос найден")
        try {
            val start = System.nanoTime()
            val (samples, rate) = assertNotNull(voice.synthesize("Привет! Я Лоли. Напоминаю: в три часа созвон с командой.", ai.loli.desktop.voice.LoliVoice.DEFAULT))
            val seconds = samples.size.toDouble() / rate
            println("Голос Лоли: ${"%.1f".format(seconds)} с речи за ${(System.nanoTime() - start) / 1_000_000} мс, $rate Гц")
            assertTrue(seconds > 2.0, "синтезировано слишком мало: $seconds с")
            assertTrue(samples.any { kotlin.math.abs(it) > 0.05f }, "в синтезе тишина")
        } finally {
            voice.release()
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
