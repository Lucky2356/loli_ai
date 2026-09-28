package ai.loli.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import ai.loli.app.R
import ai.loli.app.media.RingService
import ai.loli.core.skills.ActiveTimer
import ai.loli.core.util.Ids
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Свои таймеры Лоли — не зависят от приложения «Часы» (на части прошивок оно не принимает команды).
 * Уведомление с обратным отсчётом, громкий сигнал по окончании; «сколько осталось», «отмени таймер».
 */
class LoliTimers(private val context: Context) {
    private val prefs = context.getSharedPreferences("loli_timers", Context.MODE_PRIVATE)

    /** Все сохранённые таймеры, включая только что закончившиеся (их название нужно сигналу). */
    private fun stored(): List<ActiveTimer> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ActiveTimer(o.optString("id"), o.optString("label"), Instant.ofEpochMilli(o.optLong("end")), o.optInt("seconds", 0))
        }
    }

    fun all(): List<ActiveTimer> = synchronized(LOCK) { stored().filter { it.endsAt.isAfter(Instant.now()) } }

    private fun save(list: List<ActiveTimer>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("end", it.endsAt.toEpochMilli()).put("seconds", it.seconds)) }
        // commit: приёмник сигнала может сразу завершить процесс.
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    fun start(seconds: Int, label: String): ActiveTimer = synchronized(LOCK) {
        val t = ActiveTimer(Ids.newId(), label.trim(), Instant.now().plusSeconds(seconds.toLong()), seconds)
        save(stored() + t)
        schedule(t)
        t
    }

    fun cancel(label: String?): Int = synchronized(LOCK) {
        val list = all()
        val norm = label?.lowercase()?.replace('ё', 'е')?.trim()?.take(4)
        val gone = if (norm.isNullOrEmpty()) list else list.filter { it.label.lowercase().replace('ё', 'е').startsWith(norm) }.ifEmpty { list.takeIf { it.size == 1 } ?: emptyList() }
        gone.forEach { unschedule(it) }
        save(stored() - gone.toSet())
        gone.size
    }

    fun cancelOne(id: String): Boolean = synchronized(LOCK) {
        val t = stored().firstOrNull { it.id == id } ?: return false
        unschedule(t)
        save(stored().filter { it.id != id })
        true
    }

    fun finished(id: String): ActiveTimer? = synchronized(LOCK) {
        val list = stored()
        val t = list.firstOrNull { it.id == id }
        save(list.filter { it.id != id })
        Notifications.cancel(context, notificationId(id))
        t
    }

    /**
     * После перезагрузки телефона будильники системы сбрасываются — ставим заново.
     * Таймеры, закончившиеся, пока телефон был выключен, не пропадают молча: показываем уведомление.
     */
    fun rescheduleAll(afterBoot: Boolean = false) = synchronized(LOCK) {
        val now = Instant.now()
        // Обычный запуск: будильники системы живы, закончившийся таймер ещё прозвенит сам (неточный будильник
        // может опоздать) — просто планируем всё заново тем же адресом, без «пока телефон был выключен».
        if (!afterBoot) { stored().forEach { schedule(it) }; return@synchronized }
        val (done, active) = stored().partition { !it.endsAt.isAfter(now) }
        done.forEach { t ->
            val n = NotificationCompat.Builder(context, Notifications.CHANNEL_ALARMS)
                .setSmallIcon(R.drawable.ic_stat_loli)
                .setContentTitle(if (t.label.isBlank()) "Таймер закончился" else "Таймер «${t.label}» закончился")
                .setContentText("Пока телефон был выключен.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()
            Notifications.notifySafely(context, notificationId(t.id), n)
        }
        if (done.isNotEmpty()) save(active)
        active.forEach { schedule(it) }
    }

    /** У каждого таймера свой адрес: иначе похожие id дали бы один и тот же PendingIntent. */
    private fun pending(t: ActiveTimer, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, TimerReceiver::class.java).setData(Uri.parse("loli-timer://${t.id}")).putExtra(EXTRA_ID, t.id),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun schedule(t: ActiveTimer) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(t) ?: return
        val at = t.endsAt.toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        showCountdown(t)
    }

    private fun unschedule(t: ActiveTimer) {
        pending(t, PendingIntent.FLAG_NO_CREATE)?.let { pi -> context.getSystemService(AlarmManager::class.java)?.cancel(pi); pi.cancel() }
        Notifications.cancel(context, notificationId(t.id))
    }

    private fun showCountdown(t: ActiveTimer) {
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(if (t.label.isBlank()) "Таймер" else "Таймер «${t.label}»")
            .setWhen(t.endsAt.toEpochMilli())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter((t.endsAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1000))
            .build()
        Notifications.notifySafely(context, notificationId(t.id), n)
    }

    companion object {
        private const val KEY = "timers"
        const val EXTRA_ID = "timer_id"
        /** Экземпляров несколько (приложение и приёмник сигнала) — чтение и запись списка общие. */
        private val LOCK = Any()
        fun notificationId(id: String) = Notifications.TIMER_BASE_ID + (id.hashCode() and 0xfff)
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(LoliTimers.EXTRA_ID) ?: return
        val t = LoliTimers(context).finished(id)
        val title = if (t?.label.isNullOrBlank()) "Время вышло!" else "Время вышло: ${t!!.label}"
        RingService.start(context, title, "Таймер Лоли. Коснитесь, чтобы остановить.", loud = false)
    }
}
