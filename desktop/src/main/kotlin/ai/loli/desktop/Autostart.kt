package ai.loli.desktop

import ai.loli.core.util.Logger
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Запуск вместе с Windows: напоминания приходят, только пока Лоли запущена, поэтому удобно,
 * чтобы она сама стартовала при входе и тихо сидела в трее. Запись в реестре текущего пользователя
 * (HKCU\…\Run) — права администратора не нужны, отключается здесь же или в «Автозагрузке» диспетчера задач.
 */
object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Loli"
    const val TRAY_FLAG = "--tray"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Путь к Loli.exe; null — запуск не из установленной программы (из исходников), автозапуск недоступен. */
    private val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    val supported: Boolean get() = windows && launcher != null

    fun isEnabled(): Boolean = supported && runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
    }.getOrDefault(false)

    /** true — получилось. */
    fun set(enabled: Boolean): Boolean {
        val exe = launcher ?: return false
        if (!windows) return false
        return runCatching {
            if (enabled) {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME, "\"$exe\" $TRAY_FLAG")
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
            }
            true
        }.onFailure { Logger.w("App", "Не удалось изменить автозапуск", it) }.getOrDefault(false)
    }

    /** После обновления путь к программе мог смениться — переписываем запись, если она есть. */
    fun refresh() {
        if (isEnabled()) set(true)
    }
}
