package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2.2.2: найденные при перепроверке ошибки. Сегодня пятница 25.09.2026, 12:00 МСК. */
class Audit222Test {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }
    private suspend fun TestEnv.reminderTimes() = store.reminders.all().map { it.text to it.triggerAt.atZone(zone).toLocalDateTime() }

    @Test fun halfPastSpokenTime() = runTest {
        val e = env()
        e.engine.handle("напомни в половине третьего позвонить")
        assertEquals(listOf("Позвонить" to LocalDateTime.of(2026, 9, 25, 14, 30)), e.reminderTimes())
        val e2 = env()
        e2.engine.handle("напомни завтра в половину девятого утра позвонить")
        assertEquals(LocalDateTime.of(2026, 9, 26, 8, 30), e2.reminderTimes().single().second)
    }

    @Test fun oneOClockNightAndDay() = runTest {
        val night = env()
        night.engine.handle("напомни в час ночи позвонить")
        assertEquals(LocalDateTime.of(2026, 9, 26, 1, 0), night.reminderTimes().single().second)
        val day = env()
        day.engine.handle("напомни в час дня позвонить")
        assertEquals(LocalDateTime.of(2026, 9, 25, 13, 0), day.reminderTimes().single().second)
        val inHour = env()
        inHour.engine.handle("напомни через час позвонить")
        assertEquals(LocalDateTime.of(2026, 9, 25, 13, 0), inHour.reminderTimes().single().second)
    }

    @Test fun reminderTitleKeepsPrepositionForNouns() = runTest {
        val e = env()
        e.engine.handle("напомни мне в 5 вечера про встречу")
        assertEquals("Про встречу", e.reminderTimes().single().first)
        val v = env()
        v.engine.handle("напомни про то чтобы позвонить маме завтра")
        assertEquals("Позвонить маме", v.reminderTimes().single().first)
    }

    @Test fun birthdayAfterRemindWord() = runTest {
        val e = env()
        e.engine.handle("напомни про день рождения Маши 12 октября")
        assertEquals("День рождения Маши", e.reminderTimes().first().first)
    }

    @Test fun rescheduleReminder() = runTest {
        val e = env()
        e.engine.handle("напомни в 7:50 достать мясо")
        e.engine.handle("перенеси напоминание про мясо на 9 вечера").text.let { assertTrue(it.startsWith("Перенесла напоминание"), it) }
        assertEquals(LocalDateTime.of(2026, 9, 26, 21, 0), e.reminderTimes().single().second)
        e.engine.handle("перенеси напоминание на послезавтра")
        assertEquals(LocalDateTime.of(2026, 9, 27, 21, 0), e.reminderTimes().single().second)
        e.engine.handle("сдвинь напоминание про мясо на час")
        assertEquals(LocalDateTime.of(2026, 9, 27, 22, 0), e.reminderTimes().single().second)
        assertEquals(1, e.store.reminders.all().size, "переносится существующее, новое не создаётся")
        assertTrue(e.scheduler.scheduled.last().triggerAt.atZone(zone).toLocalDateTime() == LocalDateTime.of(2026, 9, 27, 22, 0))
    }

    @Test fun rescheduleIntoPastIsRefused() = runTest {
        val e = env()
        e.engine.handle("напомни в 7:50 достать мясо")
        val text = e.engine.handle("перенеси напоминание про мясо на вчера").text
        assertTrue(text.startsWith("Это время уже прошло"), text)
        assertEquals(LocalDateTime.of(2026, 9, 26, 7, 50), e.reminderTimes().single().second)
    }

    @Test fun rescheduleTask() = runTest {
        val e = env()
        e.engine.handle("добавь задачу купить молоко")
        e.engine.handle("перенеси задачу купить молоко на завтра").text.let { assertEquals("Перенесла задачу «Купить молоко» на завтра, 26 сентября.", it) }
        assertEquals(java.time.LocalDate.of(2026, 9, 26), e.store.tasks.all().single().dueDate)
        e.engine.handle("перенеси на понедельник")
        assertEquals(java.time.LocalDate.of(2026, 9, 28), e.store.tasks.all().single().dueDate)
        assertEquals(1, e.store.tasks.all().size)
        assertTrue(e.store.reminders.all().isEmpty())
    }

    @Test fun postponeIsNotSavings() = runTest {
        val e = env()
        e.engine.handle("напомни в 7:50 достать мясо")
        e.engine.handle("отложи напоминание про мясо на 2 часа").text.let { assertTrue(it.startsWith("Перенесла"), it) }
        e.engine.handle("отложи 5000 на отпуск").text.let { assertFalse(it.startsWith("Перенесла"), it) }
    }

    @Test fun taskConfirmationHasNoDoublePreposition() = runTest {
        val e = env()
        val text = e.engine.handle("нужно сдать отчёт до 30 сентября").text
        assertFalse(text.contains("на в "), text)
        assertTrue(text.contains("на среду, 30 сентября"), text)
    }

    @Test fun crossOffShoppingWithoutListName() = runTest {
        val e = env()
        e.engine.handle("добавь в список покупок молоко и хлеб")
        assertTrue(e.engine.handle("убери из списка молоко").text.startsWith("Вычеркнула «Молоко»"))
        assertTrue(e.engine.handle("убери хлеб из списка").text.startsWith("Вычеркнула «Хлеб»"))
    }

    @Test fun deleteNoteAsksInAccusative() = runTest {
        val e = env()
        e.engine.handle("запиши заметку купить подарок")
        val text = e.engine.handle("удали заметку про подарок").text
        assertTrue(text.startsWith("Удалить заметку «"), text)
    }

    private suspend fun TestEnv.expenseAmounts() = store.expenses.all().map { it.amountMinor }

    @Test fun decimalAmountsAreNotSplitByComma() = runTest {
        for ((phrase, minor) in listOf(
            "потратила 1,5к на подарок" to 150_000L,
            "потратил 2,5 тысячи на такси" to 250_000L,
            "потратил 1 200,50 на аптеку" to 120_050L,
            "потратила две с половиной тысячи на одежду" to 250_000L,
            "потратила три с половиной тысячи рублей" to 350_000L,
        )) {
            val e = env()
            e.engine.handle(phrase)
            assertEquals(listOf(minor), e.expenseAmounts(), phrase)
        }
    }

    @Test fun commaStillSeparatesCommands() = runTest {
        val e = env()
        e.engine.handle("потратил 500 на кофе, 300 на такси")
        assertEquals(listOf(50_000L, 30_000L), e.expenseAmounts().sorted().reversed())
    }

    @Test fun limitPhraseDoesNotCreateExpense() = runTest {
        val e = env()
        e.engine.handle("поставь лимит на продукты 20000")
        assertTrue(e.expenseAmounts().isEmpty())
    }

    @Test fun waterInMillilitres() = runTest {
        val e = env()
        assertTrue(e.engine.handle("выпил 500 мл воды").text.startsWith("Записала: +2 стакана"))
        assertTrue(e.engine.handle("выпила полтора литра воды").text.startsWith("Записала: +6 стаканов"))
        assertTrue(e.expenseAmounts().isEmpty(), "вода не должна становиться расходом")
    }

    @Test fun spacedWifiOpensSettings() {
        val cmd = (LocalCommandParser().parse("включи вай фай", java.time.Instant.parse("2026-09-25T09:00:00Z"), zone)?.actions?.single() as AssistantAction.Device).command
        assertEquals(DeviceCommand.OpenSettings(SettingsSection.WIFI), cmd)
    }
}
