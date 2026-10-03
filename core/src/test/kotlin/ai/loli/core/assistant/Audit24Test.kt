package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2.4.0: умные напоминания. Сегодня пятница 25.09.2026, 12:00 МСК. */
class Audit24Test {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun nagReminderIsMarkedPersistentAndTextIsClean() = runTest {
        for (phrase in listOf(
            "напомни выпить таблетку через час пока не отмечу",
            "напомни выпить таблетку через час настойчиво",
            "напомни выпить таблетку через час каждые 10 минут пока не отмечу",
            "напомни выпить таблетку через час, не отстану",
        )) {
            val e = env()
            val text = e.engine.handle(phrase).text
            val r = e.store.reminders.all().single()
            assertEquals("Выпить таблетку", r.text, phrase)
            assertEquals(LocalDateTime.of(2026, 9, 25, 13, 0), r.triggerAt.atZone(zone).toLocalDateTime(), phrase)
            assertEquals(listOf(r.id), e.persistent, phrase)
            assertTrue(text.contains("каждые 10 минут"), text)
        }
    }

    @Test fun ordinaryReminderIsNotPersistent() = runTest {
        val e = env()
        e.engine.handle("напомни выпить таблетку через час")
        assertTrue(e.persistent.isEmpty())
    }

    @Test fun recurringReminderIgnoresNag() = runTest {
        val e = env()
        e.engine.handle("напомни каждый день в 9 пить воду настойчиво")
        assertTrue(e.persistent.isEmpty(), "повторяющееся не делаем настойчивым")
        assertEquals("Пить воду", e.store.reminders.all().single().text)
    }

    @Test fun laterMeansInTwoHours() = runTest {
        val e = env()
        e.engine.handle("напомни позже позвонить маме")
        val r = e.store.reminders.all().single()
        assertEquals("Позвонить маме", r.text)
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 0), r.triggerAt.atZone(zone).toLocalDateTime())
        val p = env()
        p.engine.handle("напомни мне попозже полить цветы")
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 0), p.store.reminders.all().single().triggerAt.atZone(zone).toLocalDateTime())
    }

    @Test fun explicitTimeBeatsLater() = runTest {
        val e = env()
        e.engine.handle("напомни позже в 18:00 позвонить")
        assertEquals(LocalDateTime.of(2026, 9, 25, 18, 0), e.store.reminders.all().single().triggerAt.atZone(zone).toLocalDateTime())
    }
}
