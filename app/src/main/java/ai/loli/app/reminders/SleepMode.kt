package ai.loli.app.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Режим сна: каждый вечер в [from] включается «Не беспокоить», утром в [to] выключается.
 * Два будильника на ближайшие срабатывания; приёмник после каждого ставит следующий, при загрузке всё планируется заново.
 */
object SleepMode {
    private const val PREFS = "loli_sleep"
    const val ACTION_ENTER = "ai.loli.action.SLEEP_ENTER"
    const val ACTION_LEAVE = "ai.loli.action.SLEEP_LEAVE"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", false)

    fun enable(context: Context, from: LocalTime, to: LocalTime) {
        prefs(context).edit().putBoolean("on", true).putInt("from", from.toSecondOfDay() / 60).putInt("to", to.toSecondOfDay() / 60).commit()
        reschedule(context)
        // Включили посреди «ночи» — тишина нужна сразу, а не завтра вечером.
        if (inWindow(from, to)) setDnd(context, true)
    }

    private fun inWindow(from: LocalTime, to: LocalTime, now: LocalTime = LocalTime.now()): Boolean =
        if (from <= to) now >= from && now < to else now >= from || now < to

    fun disable(context: Context) {
        val p = prefs(context)
        val wasInWindow = p.getBoolean("on", false) &&
            inWindow(LocalTime.ofSecondOfDay(p.getInt("from", 23 * 60) * 60L), LocalTime.ofSecondOfDay(p.getInt("to", 7 * 60) * 60L))
        p.edit().putBoolean("on", false).commit()
        cancel(context, ACTION_ENTER); cancel(context, ACTION_LEAVE)
        // Звук возвращаем только если «Не беспокоить» включили мы (сейчас «ночь» по нашему расписанию), а не пользователь сам.
        if (wasInWindow) setDnd(context, false)
    }

    /** Ставит оба будильника на ближайшие границы; безопасно вызывать сколько угодно раз. */
    fun reschedule(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("on", false)) return
        schedule(context, ACTION_ENTER, LocalTime.ofSecondOfDay(p.getInt("from", 23 * 60) * 60L))
        schedule(context, ACTION_LEAVE, LocalTime.ofSecondOfDay(p.getInt("to", 7 * 60) * 60L))
    }

    private fun pending(context: Context, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java).setAction(action).setData(Uri.parse("loli-sleep://$action")),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(context: Context, action: String) {
        pending(context, action, PendingIntent.FLAG_NO_CREATE)?.let { pi -> context.getSystemService(AlarmManager::class.java)?.cancel(pi); pi.cancel() }
    }

    private fun schedule(context: Context, action: String, at: LocalTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, action) ?: return
        var next = ZonedDateTime.now().with(at)
        if (!next.isAfter(ZonedDateTime.now())) next = next.plusDays(1)
        val ms = next.toInstant().toEpochMilli()
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    fun setDnd(context: Context, on: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        runCatching {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SleepMode.enabled(context)) return
        when (intent.action) {
            SleepMode.ACTION_ENTER -> SleepMode.setDnd(context, true)
            SleepMode.ACTION_LEAVE -> SleepMode.setDnd(context, false)
            else -> return
        }
        // Следующее срабатывание — завтра в то же время.
        SleepMode.reschedule(context)
    }
}
