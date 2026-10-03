package ai.loli.app.backup

import android.content.Context

/** Когда напомнить о копии: нет аккаунта (данные только на телефоне), копии не было 30 дней, приложению больше недели. */
object BackupNudge {
    private const val PREFS = "loli_ui"
    private const val LAST = "last_backup_at"
    private const val HIDDEN_UNTIL = "backup_nudge_hidden_until"
    private const val DAY = 24 * 3600_000L

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST, System.currentTimeMillis()).apply()
    }

    fun lastBackupAt(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST, 0L)

    fun hideForWeek(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(HIDDEN_UNTIL, System.currentTimeMillis() + 7 * DAY).apply()
    }

    fun due(context: Context, signedIn: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (signedIn) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now < prefs.getLong(HIDDEN_UNTIL, 0L)) return false
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(now)
        if (now - installed < 7 * DAY) return false
        return now - prefs.getLong(LAST, 0L) > 30 * DAY
    }
}
