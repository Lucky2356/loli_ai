package ai.loli.core.assistant

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 2.5.0: помодоро и режим сна — разбор фраз и защита от ложных срабатываний. */
class Focus25Test {
    private val today = LocalDate.of(2026, 9, 25)
    private fun cmd(t: String): DeviceCommand? = (LocalCommandParser().parse(t, java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"))
        ?.actions?.filterIsInstance<AssistantAction.Device>()?.firstOrNull()?.command)

    @Test fun focus() {
        assertEquals(DeviceCommand.Focus(true, 25, 5), cmd("работаем 25 минут"))
        assertEquals(DeviceCommand.Focus(true, 25, 5), cmd("помодоро"))
        assertEquals(DeviceCommand.Focus(true, 50, 10), cmd("включи фокус на 50 минут отдых 10"))
        assertEquals(DeviceCommand.Focus(false), cmd("хватит работать"))
        assertEquals(DeviceCommand.Focus(false), cmd("выключи помодоро"))
    }

    @Test fun sleep() {
        assertEquals(DeviceCommand.SleepMode(true, LocalTime.of(23, 0), LocalTime.of(7, 0)), cmd("режим сна с 23 до 7"))
        assertEquals(DeviceCommand.SleepMode(true, LocalTime.of(23, 0), LocalTime.of(7, 0)), cmd("включи режим сна с 11 вечера до 7 утра"))
        assertEquals(DeviceCommand.SleepMode(true, LocalTime.of(22, 30), LocalTime.of(6, 30)), cmd("режим сна с 22:30 до 6:30"))
        assertEquals(DeviceCommand.SleepMode(false), cmd("выключи режим сна"))
    }

    @Test fun notTriggered() {
        assertNull(cmd("работаем"))
        assertNull(cmd("как мне лучше работать"))
    }
}
