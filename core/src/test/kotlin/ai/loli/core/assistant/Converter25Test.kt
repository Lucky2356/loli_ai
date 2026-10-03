package ai.loli.core.assistant

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 2.5.0: кухонные меры и температура в одну сторону. */
class Converter25Test {
    private val today = LocalDate.of(2026, 9, 25)
    private fun ask(t: String) = DevicePhrases.answer(t, today)

    @Test fun kitchenMeasures() {
        assertEquals("Стакан муки — примерно 160 граммов.", ask("стакан муки в граммах"))
        assertEquals("2 стакана сахара — примерно 400 граммов.", ask("два стакана сахара в граммах"))
        assertEquals("Столовая ложка соли — примерно 30 граммов.", ask("сколько грамм в столовой ложке соли"))
        assertEquals("3 чайные ложки сахара — примерно 24 грамма.", ask("три чайные ложки сахара в граммах"))
    }

    @Test fun temperatureOneWay() {
        assertEquals("72° F = 22,222° C.", ask("72 по фаренгейту"))
        assertEquals("20° C = 68° F.", ask("20 градусов по цельсию"))
    }

    @Test fun unrelatedStaysUntouched() {
        assertNull(ask("стакан воды в граммах это много"))
        assertEquals("5 миль = 8,047 км.", ask("переведи 5 миль в километры"))
    }
}
