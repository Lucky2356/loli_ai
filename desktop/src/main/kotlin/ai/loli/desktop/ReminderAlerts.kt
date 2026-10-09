package ai.loli.desktop

import ai.loli.core.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Сработавшие напоминания на экране: окно поверх всех программ со звуком и кнопками
 * «Готово», «+10 мин», «Через час», «Завтра» — как уведомление на телефоне. Не зависит от того,
 * разрешены ли уведомления Windows и включён ли режим «Не беспокоить».
 */
class ReminderAlerts(private val c: DesktopContainer) {
    data class Alert(
        val key: String,
        val title: String,
        val text: String,
        /** «Было в 14:30» — если напоминание пришло с опозданием (компьютер был выключен). */
        val note: String?,
        /** Сценарий по расписанию: откладывать нечего, только закрыть. */
        val routine: Boolean,
        val firedAt: Instant,
    )

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    fun show(title: String, text: String, note: String?, routine: Boolean) {
        val alert = Alert(UUID.randomUUID().toString(), title, text, note, routine, c.time.now())
        // Больше четырёх сразу не показываем: старые уходят, их видно в «Планах → Прошедшие».
        _alerts.update { (it + alert).takeLast(4) }
        if (c.settings.value.reminderSound) Chime.play()
    }

    fun dismiss(key: String) = _alerts.update { list -> list.filterNot { it.key == key } }

    fun dismissAll() { _alerts.value = emptyList() }

    /** Отложить: новое разовое напоминание с тем же текстом. */
    suspend fun snooze(alert: Alert, by: Duration) {
        dismiss(alert.key)
        runCatching {
            c.store.reminders.create(alert.text, c.time.now().plus(by), null, c.time.zone().id)
        }.onFailure { Logger.w("Reminders", "Не удалось отложить напоминание", it) }
    }

    /** «Завтра» — в то же время суток, когда оно сработало. */
    suspend fun tomorrow(alert: Alert) = snooze(alert, Duration.ofDays(1))

    /** Короткий мягкий сигнал из двух нот — без звуковых файлов, не зависит от системных звуков. */
    object Chime {
        fun play() {
            Thread({
                runCatching {
                    val rate = 44_100f
                    val format = AudioFormat(rate, 16, 1, true, false)
                    val notes = listOf(880.0 to 0.18, 1318.5 to 0.32)
                    val samples = notes.flatMap { (freq, seconds) ->
                        val n = (rate * seconds).toInt()
                        (0 until n).map { i ->
                            val t = i / rate
                            // Мягкая атака и затухание, без щелчков.
                            val env = minOf(1.0, i / (rate * 0.01)) * exp(-3.5 * t / seconds)
                            (sin(2 * PI * freq * t) * env * 0.35 * Short.MAX_VALUE).toInt()
                        }
                    }
                    val bytes = ByteArray(samples.size * 2)
                    samples.forEachIndexed { i, v -> bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
                    AudioSystem.getSourceDataLine(format).use { line ->
                        line.open(format); line.start()
                        line.write(bytes, 0, bytes.size)
                        line.drain()
                    }
                }.onFailure { Logger.w("Reminders", "Сигнал не проиграл", it) }
            }, "loli-chime").apply { isDaemon = true }.start()
        }
    }
}
