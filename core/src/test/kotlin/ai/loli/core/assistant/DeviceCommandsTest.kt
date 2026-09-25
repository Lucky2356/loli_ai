package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCommandsTest {
    private val now = Instant.parse("2026-09-25T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private val parser = LocalCommandParser()

    private fun device(phrase: String): DeviceCommand? =
        (parser.parse(phrase, now, zone)?.actions?.singleOrNull() as? AssistantAction.Device)?.command

    @Test fun timers() {
        assertEquals(DeviceCommand.Timer(300), device("поставь таймер на 5 минут"))
        assertEquals(DeviceCommand.Timer(600), device("засеки десять минут"))
        assertEquals(DeviceCommand.Timer(1800), device("таймер на полчаса"))
        assertEquals(DeviceCommand.Timer(5400), device("поставь таймер на 1 час 30 минут"))
        assertEquals(DeviceCommand.Timer(45), device("таймер 45 секунд"))
    }

    @Test fun alarms() {
        assertEquals(DeviceCommand.Alarm(LocalTime.of(7, 0)), device("разбуди меня в 7"))
        assertEquals(DeviceCommand.Alarm(LocalTime.of(6, 30)), device("поставь будильник на 6:30"))
        assertEquals(DeviceCommand.Alarm(LocalTime.of(19, 0)), device("будильник на 7 вечера"))
        val weekdays = device("поставь будильник на 8 по будням") as DeviceCommand.Alarm
        assertEquals(LocalTime.of(8, 0), weekdays.time)
        assertTrue(DayOfWeek.MONDAY in weekdays.days && DayOfWeek.SATURDAY !in weekdays.days)
        assertEquals(DeviceCommand.Alarm(LocalTime.of(7, 30)), device("разбуди меня в половину восьмого"))
    }

    @Test fun phoneControls() {
        assertEquals(DeviceCommand.Flashlight(true), device("включи фонарик"))
        assertEquals(DeviceCommand.Flashlight(false), device("выключи фонарик"))
        assertEquals(DeviceCommand.Battery, device("сколько заряда"))
        assertEquals(DeviceCommand.Media(MediaAction.NEXT), device("следующий трек"))
        assertEquals(DeviceCommand.Media(MediaAction.PAUSE), device("поставь на паузу"))
        assertEquals(DeviceCommand.Volume(VolumeChange.UP), device("сделай погромче"))
        assertEquals(DeviceCommand.Volume(VolumeChange.SET, 50), device("громкость на 50 процентов"))
        assertEquals(DeviceCommand.OpenSettings(SettingsSection.WIFI), device("открой настройки wifi"))
        assertEquals(DeviceCommand.OpenSettings(SettingsSection.BLUETOOTH), device("включи блютуз"))
    }

    @Test fun appsCallsSearchAndRoutes() {
        assertEquals(DeviceCommand.OpenApp("телеграм"), device("открой телеграм"))
        assertEquals(DeviceCommand.OpenApp("калькулятор"), device("запусти калькулятор"))
        assertEquals(DeviceCommand.Call("маме"), device("позвони маме"))
        assertEquals(DeviceCommand.Message("саше", "я задержусь"), device("напиши саше что я задержусь"))
        assertEquals(DeviceCommand.WebSearch("рецепт борща"), device("найди в интернете рецепт борща"))
        assertEquals(DeviceCommand.Navigate("вокзала"), device("построй маршрут до вокзала"))
    }

    @Test fun moreVoiceCommands() {
        assertEquals(DeviceCommand.Global(GlobalAction.SCREENSHOT), device("сделай скриншот"))
        assertEquals(DeviceCommand.Global(GlobalAction.HOME), device("домой"))
        assertEquals(DeviceCommand.Global(GlobalAction.LOCK), device("заблокируй экран"))
        assertEquals(DeviceCommand.Global(GlobalAction.NOTIFICATIONS), device("открой уведомления"))
        assertEquals(DeviceCommand.Camera(selfie = true), device("сделай селфи"))
        assertEquals(DeviceCommand.Camera(video = true), device("сними видео"))
        assertEquals(DeviceCommand.OpenUrl("https://habr.com"), device("открой сайт habr.com"))
        assertEquals(DeviceCommand.Play("рецепт пиццы", youtube = true), device("найди на ютубе рецепт пиццы"))
        assertEquals(DeviceCommand.Play("queen"), device("включи песню queen"))
        assertEquals(DeviceCommand.DoNotDisturb(true), device("включи режим не беспокоить"))
        assertEquals(DeviceCommand.Brightness(70), device("яркость на 70"))
        assertEquals(DeviceCommand.OpenSettings(SettingsSection.AIRPLANE), device("включи режим полета"))
        assertEquals(DeviceCommand.AddContact("Саша", "89001234567"), device("добавь контакт саша 8 900 123 45 67"))
        assertEquals(DeviceCommand.Share("телеграм", "привет всем"), device("отправь в телеграм привет всем"))
        val event = device("добавь в календарь встречу с машей завтра в 15:00") as DeviceCommand.CalendarEvent
        assertEquals("Встречу с машей", event.title)
        assertEquals(Instant.parse("2026-09-26T12:00:00Z"), event.start)
        assertEquals(DeviceCommand.Media(MediaAction.PLAY), device("включи музыку"))
    }

    @Test fun worldTime() {
        val a = DevicePhrases.answer("сколько времени в токио", LocalDate.of(2026, 9, 25))
        assertTrue(a != null && a.startsWith("В Токио сейчас"), a)
    }

    @Test fun lockedScreenAllowsAddingButHidesData() = runTest {
        var locked = true
        val env = TestEnv(lockPolicy = { if (locked) LockPolicy() else null }).apply { settings = AssistantSettings(useAI = false) }
        val add = env.engine.handle("потратила 500 рублей на такси")
        assertEquals(1, env.store.expenses.all().size, add.text)
        env.engine.handle("запомни что мой пин от домофона 1234")
        val ask = env.engine.handle("сколько я потратила сегодня")
        assertTrue(ask.text.contains("Разблокируйте"), ask.text)
        val del = env.engine.handle("удали последний расход")
        assertTrue(del.text.contains("Разблокируйте"), del.text)
        assertEquals(1, env.store.expenses.all().size)
        locked = false
        assertTrue(env.engine.handle("сколько я потратила сегодня").text.contains("500"))
    }

    @Test fun lockPolicyIsConfigurable() {
        val strict = LockPolicy(create = true, basicDevice = false, calls = false)
        assertTrue(strict.allows(AssistantAction.CreateNote(ai.loli.core.model.NoteKind.NOTE, "Купить хлеб", "")))
        assertTrue(!strict.allows(AssistantAction.Device(DeviceCommand.Call("маме"))))
        assertTrue(!strict.allows(AssistantAction.Device(DeviceCommand.Timer(60))))
        assertTrue(LockPolicy().allows(AssistantAction.Device(DeviceCommand.Call("маме"))))
        assertTrue(!LockPolicy().allows(AssistantAction.Device(DeviceCommand.OpenApp("сбербанк"))))
        assertTrue(LockPolicy(apps = true).allows(AssistantAction.Device(DeviceCommand.OpenApp("телеграм"))))
    }

    @Test fun recordsAreNotMistakenForDeviceCommands() {
        assertNull(device("открой заметки"))
        assertNull(device("напиши заметку: купить хлеб"))
        assertNull(device("напомни через 5 минут выключить плиту"))
        assertNull(device("потратила 500 рублей на такси"))
    }

    @Test fun offlineAnswers() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals("До 1 января 2027 — 98 дней.", DevicePhrases.answer("сколько дней до нового года", today))
        assertTrue(DevicePhrases.answer("какой день недели 8 марта", today)!!.contains("понедельник"))
        assertEquals("5 км = 3,107 миль.", DevicePhrases.answer("переведи 5 километров в мили", today))
        assertEquals("100° F = 37,778° C.", DevicePhrases.answer("100 градусов фаренгейта в цельсии", today))
        assertTrue(DevicePhrases.answer("подбрось монетку", today, Random(1)) in setOf("Орёл!", "Решка!"))
        assertTrue(DevicePhrases.answer("брось кубик", today)!!.startsWith("Выпало"))
        assertTrue(DevicePhrases.answer("случайное число от 1 до 10", today)!!.startsWith("Пусть будет"))
    }

    @Test fun engineRunsDeviceCommandThroughController() = runTest {
        val performed = mutableListOf<DeviceCommand>()
        val env = TestEnv(device = object : DeviceController {
            override suspend fun perform(command: DeviceCommand): DeviceResult {
                performed += command
                return DeviceResult("Таймер на ${DevicePhrases.describeDuration((command as DeviceCommand.Timer).seconds)} запущен.")
            }
        }).apply { settings = AssistantSettings(useAI = false) }
        val reply = env.engine.handle("поставь таймер на 5 минут и запиши расход 200 на кофе")
        assertIs<DeviceCommand.Timer>(performed.single())
        assertTrue(reply.text.contains("Таймер на 5 минут запущен"), reply.text)
        assertEquals(1, env.store.expenses.all().size)
    }

    @Test fun withoutDeviceSupportAnswersHonestly() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        assertTrue(env.engine.handle("включи фонарик").text.contains("не умею"))
    }
}
