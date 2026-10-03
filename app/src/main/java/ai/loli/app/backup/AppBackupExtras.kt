package ai.loli.app.backup

import ai.loli.app.reminders.GeoReminders
import ai.loli.app.settings.SettingsRepository
import ai.loli.core.backup.BackupExtras

/**
 * Что кроме базы входит в резервную копию: места («дом», «работа») с напоминаниями по месту и имена.
 * Ключи AI, сессия аккаунта и пароль базы сюда не попадают никогда.
 */
class AppBackupExtras(private val geo: GeoReminders, private val settings: SettingsRepository) : BackupExtras {
    override suspend fun export(): Map<String, String> {
        val s = settings.current()
        return geo.exportRaw() + buildMap {
            put("assistantName", s.assistantName)
            if (s.userName.isNotBlank()) put("userName", s.userName)
            if (s.city.isNotBlank()) put("city", s.city)
        }
    }

    override suspend fun restore(values: Map<String, String>) {
        geo.importRaw(values)
        val s = settings.current()
        // Имена из копии подставляются, только если здесь они ещё не заданы.
        if (s.userName.isBlank()) values["userName"]?.let { settings.setUserName(it) }
        if (s.city.isBlank()) values["city"]?.let { settings.setCity(it) }
        if (s.assistantName == "Лоли") values["assistantName"]?.takeIf { it.isNotBlank() }?.let { settings.setAssistantName(it) }
    }
}
