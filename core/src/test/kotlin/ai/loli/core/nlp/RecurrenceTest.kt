package ai.loli.core.nlp

import ai.loli.core.model.Recurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecurrenceTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun encodeDecodeRoundTrip() {
        val r = Recurrence(Recurrence.Frequency.WEEKLY, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), LocalTime.of(9, 0))
        assertEquals("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE;TIME=09:00", r.encode())
        assertEquals(r, Recurrence.decode(r.encode()))
        assertNull(Recurrence.decode("garbage"))
    }

    @Test fun weeklyNext() {
        val r = Recurrence(Recurrence.Frequency.WEEKLY, 1, setOf(DayOfWeek.MONDAY), LocalTime.of(9, 0))
        val fired = Instant.parse("2026-09-28T06:00:00Z") // пн 09:00 МСК
        assertEquals(Instant.parse("2026-10-05T06:00:00Z"), r.nextAfter(fired, zone, fired))
    }

    @Test fun dailyNext() {
        val r = Recurrence(Recurrence.Frequency.DAILY, time = LocalTime.of(8, 0))
        assertEquals(Instant.parse("2026-09-26T05:00:00Z"), r.nextAfter(Instant.parse("2026-09-25T09:00:00Z"), zone))
    }

    @Test fun monthlyClampsToMonthEnd() {
        val r = Recurrence(Recurrence.Frequency.MONTHLY, time = LocalTime.of(10, 0), dayOfMonth = 31)
        assertEquals(Instant.parse("2026-09-30T07:00:00Z"), r.nextAfter(Instant.parse("2026-09-25T09:00:00Z"), zone))
    }

    @Test fun hourlyInterval() {
        val anchor = Instant.parse("2026-09-25T09:00:00Z")
        val r = Recurrence(Recurrence.Frequency.HOURLY, interval = 2)
        assertEquals(Instant.parse("2026-09-25T11:00:00Z"), r.nextAfter(anchor, zone, anchor))
    }
}
