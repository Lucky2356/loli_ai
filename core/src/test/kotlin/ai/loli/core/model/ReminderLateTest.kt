package ai.loli.core.model

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderLateTest {
    private val at = Instant.parse("2026-09-30T04:50:00Z")
    private fun r(active: Boolean = true) = Reminder("1", "мясо", at, null, "Europe/Moscow", active, null, at, at)

    @Test fun beforeDueIsNotLate() {
        assertEquals(Duration.ZERO, r().lateBy(at.minusSeconds(30)))
        assertFalse(r().isMissed(at.minusSeconds(30)))
    }

    @Test fun lateByMeasuresDelay() {
        assertEquals(Duration.ofMinutes(76), r().lateBy(at.plus(Duration.ofMinutes(76))))
    }

    @Test fun missedNeedsGraceAndActive() {
        assertFalse(r().isMissed(at.plusSeconds(30)))
        assertTrue(r().isMissed(at.plusSeconds(90)))
        assertFalse(r(active = false).isMissed(at.plusSeconds(3600)))
    }
}
