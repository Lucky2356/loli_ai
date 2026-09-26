package ai.loli.app.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Подсказки под оболочку телефона: у Xiaomi, Samsung, Huawei, Oppo системные окна называются по-разному,
 * а у некоторых есть свои запреты (автозапуск), без которых напоминания и вызов голосом не работают в фоне.
 */
class OemHints(
    val brand: String,
    val overlay: String,
    val battery: String,
    /** Подсказка для экрана автозапуска; null — у этой оболочки его нет. */
    val autostart: String?,
) {
    companion object {
        private fun maker() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()

        fun current(): OemHints {
            val m = maker()
            return when {
                listOf("xiaomi", "redmi", "poco").any { it in m } -> OemHints(
                    "Xiaomi",
                    overlay = "Включите «Отображать всплывающие окна» и «Показывать на экране блокировки». Если их нет — «Другие разрешения».",
                    battery = "Выберите «Нет ограничений» в разделе «Контроль активности» / «Экономия заряда».",
                    autostart = "Включите «Автозапуск» для Лоли — иначе MIUI выключает напоминания и вызов голосом.",
                )
                "samsung" in m -> OemHints(
                    "Samsung",
                    overlay = "Включите переключатель «Разрешить» напротив Лоли.",
                    battery = "Нажмите «Разрешить». Если спросят режим — «Без ограничений».",
                    autostart = null,
                )
                listOf("huawei", "honor").any { it in m } -> OemHints(
                    "Huawei",
                    overlay = "Включите «Разрешить наложение поверх других окон».",
                    battery = "Нажмите «Разрешить», затем в «Запуск приложений» выберите «Управлять вручную» и включите все три переключателя.",
                    autostart = "Найдите Лоли, выключите «Управлять автоматически» и включите все переключатели.",
                )
                listOf("oppo", "realme", "oneplus", "vivo").any { it in m } -> OemHints(
                    Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                    overlay = "Включите «Разрешить отображение поверх других приложений».",
                    battery = "Нажмите «Разрешить», в настройках батареи выберите «Разрешить фоновую активность».",
                    autostart = "Разрешите Лоли автозапуск — иначе напоминания могут не приходить.",
                )
                else -> OemHints(
                    Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                    overlay = "Найдите в списке Лоли и включите переключатель.",
                    battery = "В окне нажмите «Разрешить».",
                    autostart = null,
                )
            }
        }

        /** Экран автозапуска производителя; если не открылся — страница приложения в настройках. */
        fun openAutostart(context: Context) {
            val candidates = listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            for (cn in candidates) {
                val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
