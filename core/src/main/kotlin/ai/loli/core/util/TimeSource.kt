package ai.loli.core.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Источник времени. Абстрагирован для детерминированных тестов. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun zonedNow(): ZonedDateTime = now().atZone(zone())
}

class SystemTimeSource(private val clock: Clock = Clock.systemDefaultZone()) : TimeSource {
    override fun now(): Instant = clock.instant()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedTimeSource(var instant: Instant, private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    fun advanceMillis(ms: Long) { instant = instant.plusMillis(ms) }
}

/**
 * Монотонные метки обновления: гарантирует, что каждая следующая метка строго больше предыдущей
 * (важно для last-write-wins, когда несколько изменений происходят в одну миллисекунду).
 */
class UpdateStamp(private val time: TimeSource) {
    private var last = 0L
    @Synchronized
    fun next(): Instant {
        val now = time.now().toEpochMilli()
        last = if (now > last) now else last + 1
        return Instant.ofEpochMilli(last)
    }
}
