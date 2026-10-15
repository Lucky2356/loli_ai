package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2.13: обращение «Лоли» в голосе, напоминания на сегодня, время словами, напоминания к задачам. Сейчас четверг 15.10.2026, 14:30 МСК. */
class Fixes213Test {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun env() = TestEnv(time = FixedTimeSource(Instant.parse("2026-10-15T11:30:00Z"), zone)).apply { settings = AssistantSettings(useAI = false) }
    private fun at(h: Int, m: Int = 0, day: Int = 15) = LocalDateTime.of(2026, 10, day, h, m)

    private suspend fun TestEnv.reminder() = store.reminders.all().single { it.active }
    private fun TestEnv.local(i: Instant) = i.atZone(zone).toLocalDateTime()

    @Test fun misheardNameIsNotPartOfTheRecord() = runTest {
        for ((phrase, text) in listOf(
            "ноль напомни через 5 минут проверить чай" to "Проверить чай",
            "ноль ноль напомни через 5 минут проверить чай" to "Проверить чай",
            "ну ли напомни через 5 минут проверить чай" to "Проверить чай",
            "лол напомни через 5 минут проверить чай" to "Проверить чай",
        )) {
            val e = env()
            e.engine.handle(phrase)
            assertEquals(text, e.reminder().text, phrase)
        }
        val e = env()
        e.engine.handle("ноль ноль добавь задачу купить хлеб")
        assertEquals("Купить хлеб", e.store.tasks.all().single().title)
    }

    @Test fun zeroInOrdinarySpeechStays() {
        assertEquals("ноль целых пять десятых", AssistantEngine.stripWakeWord("ноль целых пять десятых", "Лоли"))
        assertEquals("ноль градусов на улице", AssistantEngine.stripWakeWord("ноль градусов на улице", "Лоли"))
        // Своё имя ассистента — «ноль» не трогаем.
        assertEquals("ноль напомни позвонить", AssistantEngine.stripWakeWord("ноль напомни позвонить", "Джарвис"))
    }

    @Test fun todayWithoutTimeAsksWhatTimeNotTomorrow() = runTest {
        val e = env()
        val q = e.engine.handle("напомни сегодня позвонить маме").text
        assertTrue(q.startsWith("Во сколько сегодня"), q)
        assertFalse(q.contains("завтра"), q)
        e.engine.handle("в 18")
        assertEquals(at(18), e.local(e.reminder().triggerAt))

        val e2 = env()
        e2.engine.handle("напомни позвонить маме")
        val again = e2.engine.handle("сегодня").text
        assertTrue(again.startsWith("Во сколько сегодня"), again)
        e2.engine.handle("через час")
        assertEquals(at(15, 30), e2.local(e2.reminder().triggerAt))
    }

    @Test fun hintSuggestsTodayDuringTheDay() = runTest {
        val q = env().engine.handle("напомни позвонить маме").text
        assertTrue(q.contains("«в 16:00»"), q)
        assertFalse(q.contains("завтра"), q)
    }

    @Test fun partOfDayStillGoingIsToday() = runTest {
        for ((phrase, expected) in listOf(
            "напомни днём позвонить маме" to at(15),
            "напомни после обеда позвонить маме" to at(15),
            "напомни вечером позвонить маме" to at(19),
            "напомни сегодня до вечера позвонить маме" to at(17, 30),
        )) {
            val e = env()
            e.engine.handle(phrase)
            assertEquals(expected, e.local(e.reminder().triggerAt), phrase)
        }
    }

    @Test fun spokenTimeAfterNa() = runTest {
        for (phrase in listOf(
            "поставь напоминание на семнадцать сорок пять забрать посылку",
            "поставь напоминание на 17:45 забрать посылку",
        )) {
            val e = env()
            e.engine.handle(phrase)
            assertEquals(at(17, 45), e.local(e.reminder().triggerAt), phrase)
            assertEquals("Забрать посылку", e.reminder().text, phrase)
        }
        val e = env()
        e.engine.handle("напомни сегодня на 18 забрать посылку")
        assertEquals(at(18), e.local(e.reminder().triggerAt))
    }

    @Test fun taskWithTimeGetsReminderThatFollowsTheTask() = runTest {
        val e = env()
        val reply = e.engine.handle("добавь задачу сегодня в 19:00 созвон").text
        assertTrue(reply.contains("Напомню в 19:00"), reply)
        val r = e.reminder()
        assertEquals("Задача: Созвон", r.text)
        assertEquals(at(19), e.local(r.triggerAt))
        assertTrue(e.scheduler.scheduled.any { it.id == r.id })

        e.engine.handle("перенеси задачу созвон на 20:00")
        assertEquals(at(20), e.local(e.reminder().triggerAt))

        e.engine.handle("отметь задачу созвон выполненной")
        assertTrue(e.store.reminders.active().isEmpty())
        assertTrue(TaskReminders.obsolete("Задача: Созвон", e.store.tasks))
    }

    @Test fun taskWithoutTimeHasNoReminder() = runTest {
        val e = env()
        e.engine.handle("добавь задачу на сегодня купить хлеб")
        assertEquals(LocalDate.of(2026, 10, 15), e.store.tasks.all().single().dueDate)
        assertTrue(e.store.reminders.all().isEmpty())
    }

    @Test fun quickAddReminderForToday() {
        val now = Instant.parse("2026-10-15T11:30:00Z")
        val r = QuickAdd.plan(QuickAdd.Kind.REMINDER, "сегодня в 18 позвонить маме", now, zone)
        val ok = r as QuickAdd.Result.Ok
        assertEquals(LocalTime.of(18, 0), (ok.action as AssistantAction.CreateReminder).triggerAt.atZone(zone).toLocalTime())
    }
}

class WakeAliases213Test {
    private val m = ai.loli.core.nlp.WakeWordMatcher("Лоли", aliases = ai.loli.core.nlp.WakeWordMatcher.misheardAliases("Лоли"))

    @Test fun misheardNameWakesOnlyWithCommand() {
        assertEquals("напомни через час чай", m.match("ноль напомни через час чай")?.command)
        assertEquals("какая погода", m.match("ну ли какая погода")?.command)
        assertEquals(null, m.match("ноль"))
        assertEquals("какая погода", m.match("лоли какая погода")?.command)
        assertTrue(ai.loli.core.nlp.WakeWordMatcher.misheardAliases("Джарвис").isEmpty())
    }
}

/** Фразы для компьютера доходят до нужных команд устройству. */
class DesktopPhrases213Test {
    private class Recorder : DeviceController {
        val got = mutableListOf<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult { got += command; return DeviceResult("ок") }
    }

    @Test fun computerCommandsReachDevice() = runTest {
        val cases = listOf<Pair<String, (DeviceCommand) -> Boolean>>(
            "открой калькулятор" to { it is DeviceCommand.OpenApp && it.name.contains("калькулятор", true) },
            "Лоли, открой телеграм" to { it is DeviceCommand.OpenApp && it.name.contains("телеграм", true) },
            "сделай скриншот" to { it == DeviceCommand.Global(GlobalAction.SCREENSHOT) },
            "сделай громче" to { it is DeviceCommand.Volume && it.change == VolumeChange.UP },
            "выключи звук" to { it is DeviceCommand.Volume && it.change == VolumeChange.MUTE },
            "поставь на паузу" to { it is DeviceCommand.Media && it.action == MediaAction.PAUSE },
            "следующий трек" to { it is DeviceCommand.Media && it.action == MediaAction.NEXT },
            "какой заряд батареи" to { it == DeviceCommand.Battery },
            "разбуди меня в 7 утра" to { it is DeviceCommand.Alarm && it.time.hour == 7 },
        )
        for ((phrase, check) in cases) {
            val rec = Recorder()
            val e = TestEnv(device = rec).apply { settings = AssistantSettings(useAI = false) }
            e.engine.handle(phrase)
            val cmd = rec.got.firstOrNull()
            assertTrue(cmd != null && check(cmd), "$phrase → $cmd")
        }
    }
}
