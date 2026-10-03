package ai.loli.app.reminders

import android.content.Context

/**
 * «Настойчивые» напоминания («напомни выпить таблетку пока не отмечу»): после срабатывания повторяются каждые
 * [INTERVAL_MINUTES] минут, пока не нажато «Готово», но не больше [MAX_REPEATS] раз. Хранятся только на этом телефоне
 * (в базе и на сервере этого признака нет), текста напоминания здесь нет — только id и счётчик.
 */
class NagStore(context: Context) {
    private val prefs = context.getSharedPreferences("loli_nags", Context.MODE_PRIVATE)

    fun mark(id: String) = prefs.edit().putInt(id, 0).apply()

    /** Сколько повторов уже было; null — напоминание не настойчивое. */
    fun repeats(id: String): Int? = if (prefs.contains(id)) prefs.getInt(id, 0) else null

    fun bump(id: String): Int = ((repeats(id) ?: 0) + 1).also { prefs.edit().putInt(id, it).apply() }

    fun clear(id: String) = prefs.edit().remove(id).apply()

    companion object {
        const val INTERVAL_MINUTES = 10L
        const val MAX_REPEATS = 6
    }
}
