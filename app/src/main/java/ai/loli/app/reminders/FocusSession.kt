package ai.loli.app.reminders

import android.content.Context

/**
 * Помодоро цепочкой таймеров Лоли: работа — перерыв — работа… После каждых четырёх рабочих отрезков перерыв длиннее.
 * Состояние хранится в настройках, поэтому цепочка переживает перезапуск процесса; следующий таймер запускает [TimerReceiver].
 */
object FocusSession {
    private const val PREFS = "loli_focus"
    private const val LONG_REST = 15

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun active(context: Context): Boolean = prefs(context).getString("timer", null) != null

    fun start(context: Context, workMinutes: Int, restMinutes: Int) {
        stop(context)
        val t = LoliTimers(context).start(workMinutes * 60, WORK)
        prefs(context).edit().putString("timer", t.id).putString("phase", "work").putInt("work", workMinutes)
            .putInt("rest", restMinutes).putInt("done", 0).commit()
    }

    fun stop(context: Context): Boolean {
        val p = prefs(context)
        val id = p.getString("timer", null) ?: return false
        LoliTimers(context).cancelOne(id)
        p.edit().clear().commit()
        return true
    }

    /** Закончился таймер [id]: если он из цепочки, запускает следующий и возвращает заголовок сигнала; иначе null. */
    fun onFinished(context: Context, id: String): String? {
        val p = prefs(context)
        if (p.getString("timer", null) != id) return null
        val work = p.getInt("work", 25)
        val rest = p.getInt("rest", 5)
        return if (p.getString("phase", "work") == "work") {
            val done = p.getInt("done", 0) + 1
            val minutes = if (done % 4 == 0) LONG_REST else rest
            val t = LoliTimers(context).start(minutes * 60, REST)
            p.edit().putString("timer", t.id).putString("phase", "rest").putInt("done", done).commit()
            "Перерыв $minutes мин — отдохните"
        } else {
            val t = LoliTimers(context).start(work * 60, WORK)
            p.edit().putString("timer", t.id).putString("phase", "work").commit()
            "За работу! $work мин"
        }
    }

    private const val WORK = "Работа"
    private const val REST = "Перерыв"
}
