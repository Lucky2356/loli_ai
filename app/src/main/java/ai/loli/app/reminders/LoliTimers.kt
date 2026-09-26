package ai.loli.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    @Synchronized
    fun all(): List<ActiveTimer> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ActiveTimer(o.optString("id"), o.optString("label"), Instant.ofEpochMilli(o.optLong("end")))
        }.filter { it.endsAt.isAfter(Instant.now().minusSeconds(5)) }
    }

    @Synchronized
    private fun save(list: List<ActiveTimer>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("end", it.endsAt.toEpochMilli())) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun start(seconds: Int, label: String): ActiveTimer {
        val t = ActiveTimer(Ids.newId(), label.trim(), Instant.now().plusSeconds(seconds.toLong()))
        save(all() + t)
        schedule(t)
        return t
    }

    fun cancel(label: String?): Int {
        val list = all()
        val norm = label?.lowercase()?.replace('ё', 'е')?.trim()?.take(4)
        val gone = if (norm.isNullOrEmpty()) list else list.filter { it.label.lowercase().replace('ё', 'е').startsWith(norm) }.ifEmpty { list.takeIf { it.size == 1 } ?: emptyList() }
        gone.forEach { unschedule(it) }
        save(list - gone.toSet())
        return gone.size
    }

    fun finished(id: String): ActiveTimer? {
        val list = all()
        val t = list.firstOrNull { it.id == id }
        save(list.filter { it.id != id })
        Notifications.cancel(context, notificationId(id))
        return t
    }

    /** После перезагрузки телефона будильники системы сбрасываются — ставим заново. */
    fun rescheduleAll() = all().forEach { schedule(it) }

    private fun pending(t: ActiveTimer, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        context, notificationId(t.id), Intent(context, TimerReceiver::class.java).putExtra(EXTRA_ID, t.id),
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
            .setTimeoutAfter((t.endsAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1000))
            .build()
        Notifications.notifySafely(context, notificationId(t.id), n)
    }

    companion object {
        private const val KEY = "timers"
        const val EXTRA_ID = "timer_id"
        fun notificationId(id: String) = Notifications.TIMER_BASE_ID + (id.hashCode() and 0x3f)
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
