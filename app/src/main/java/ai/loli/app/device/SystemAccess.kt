package ai.loli.app.device

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ai.loli.app.assist.LoliRecognitionService
import ai.loli.app.assist.LoliVoiceInteractionService
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Системный доступ там, где прошивка ограничивает обычные настройки.
 *
 * На части телефонов (realme, OPPO, OnePlus, Xiaomi и др.) в списке «Цифровой помощник» показываются только
 * одобренные производителем приложения. Android при этом позволяет назначить ассистента командой
 * `cmd role add-role-holder` — её можно выполнить через Shizuku (запускается на самом телефоне через
 * «Отладку по Wi-Fi», компьютер не нужен) или один раз через ADB с компьютера.
 *
 * Заодно Лоли выдаёт себе разрешение WRITE_SECURE_SETTINGS — после этого службу спецвозможностей
 * (кнопки громкости) она включает сама, без «ограниченных настроек» Android 13+.
 */
class SystemAccess(private val context: Context) {
    private val pkg = context.packageName
    private val voiceService = ComponentName(context, LoliVoiceInteractionService::class.java).flattenToString()
    private val recognizer = ComponentName(context, LoliRecognitionService::class.java).flattenToString()
    private val a11yService = ComponentName(context, LoliAccessibilityService::class.java).flattenToString()

    /** Команды для компьютера (ADB) — показываются в инструкции с кнопкой «Скопировать». */
    val adbCommands: String = listOf(
        "adb shell cmd role add-role-holder android.app.role.ASSISTANT $pkg",
        "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
    ).joinToString("\n")

    fun shizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    }.getOrDefault(false)

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = shizukuRunning() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Запрос разрешения Shizuku; true — разрешено. */
    suspend fun requestShizuku(): Boolean {
        if (!shizukuRunning()) return false
        if (shizukuGranted()) return true
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    fun isAssistant(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            Settings.Secure.getString(context.contentResolver, "assistant")?.startsWith("$pkg/") == true
        }
    }.getOrDefault(false)

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(): Boolean {
        if (LoliAccessibilityService.isEnabled) return true
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(a11yService, ignoreCase = true) }
    }

    /**
     * Включить службу Лоли в спецвозможностях без захода в настройки (нужно WRITE_SECURE_SETTINGS).
     * Остальные включённые службы сохраняются.
     */
    fun enableAccessibility(): Boolean {
        if (!canWriteSecureSettings()) return false
        return runCatching {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() }
            if (current.none { it.equals(a11yService, ignoreCase = true) }) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (current + a11yService).joinToString(":"))
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.onFailure { Logger.w(TAG, "Не удалось включить спецвозможности", it) }.getOrDefault(false)
    }

    data class SetupResult(val assistant: Boolean, val accessibility: Boolean, val overlay: Boolean, val message: String)

    /**
     * Всё одним нажатием через Shizuku: ассистент по умолчанию, спецвозможности, работа поверх приложений,
     * снятие «ограниченных настроек» Android 13+.
     */
    suspend fun setupWithShizuku(): SetupResult = withContext(Dispatchers.IO) {
        if (!requestShizuku()) return@withContext SetupResult(isAssistant(), accessibilityOn(), BackgroundLauncher(context).canLaunchFromBackground(),
            "Shizuku не запущен или не дал доступ.")
        shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        shell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        if (!isAssistant()) {
            shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT $pkg 0")
                ?: shell("cmd role add-role-holder android.app.role.ASSISTANT $pkg")
        }
        if (!isAssistant()) {
            // Старые прошивки без службы ролей: напрямую системные настройки ассистента.
            shell("settings put secure assistant $voiceService")
            shell("settings put secure voice_interaction_service $voiceService")
            shell("settings put secure voice_recognition_service $recognizer")
        }
        // Службу спецвозможностей включаем сами: разрешение WRITE_SECURE_SETTINGS уже выдано.
        val a11y = accessibilityOn() || enableAccessibility() || shell(
            "settings put secure enabled_accessibility_services " +
                (Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    .split(':').filter { it.isNotBlank() } + a11yService).distinct().joinToString(":"),
        ) != null
        val assistant = isAssistant()
        val overlay = BackgroundLauncher(context).canLaunchFromBackground()
        val msg = when {
            assistant && a11y -> "Готово: Лоли — ассистент по умолчанию, кнопки громкости включены."
            assistant -> "Лоли — ассистент по умолчанию. Спецвозможности включите вручную."
            else -> "Прошивка не дала сменить ассистента. Попробуйте команду с компьютера (ниже)."
        }
        SetupResult(assistant, a11y, overlay, msg)
    }

    /** Команда оболочки с правами ADB через Shizuku; null — ошибка. */
    private fun shell(command: String): String? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        Logger.i(TAG, "$command → $code ${out.take(200)}")
        if (code == 0 && !out.contains("Exception") && !out.contains("Error", ignoreCase = false)) out else null
    }.onFailure { Logger.w(TAG, "Команда не выполнена: $command", it) }.getOrNull()

    fun shizukuDownload(): Intent =
        if (runCatching { context.packageManager.getPackageInfo("com.android.vending", 0) }.isSuccess) {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        }

    fun openShizuku(): Intent? = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    companion object {
        private const val TAG = "SystemAccess"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
    }
}
