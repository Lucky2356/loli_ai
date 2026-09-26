package ai.loli.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Вход в приложение по отпечатку, лицу или PIN-коду телефона. Приложение снова запирается,
 * если пробыло в фоне дольше минуты. Используется системный BiometricPrompt — Лоли не видит отпечатков.
 */
class AppLock(private val context: Context) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var backgroundAt = 0L

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> if (backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt > RELOCK_MS) _unlocked.value = false
                else -> Unit
            }
        })
    }

    /** На телефоне настроена блокировка экрана (иначе подтверждать нечем). */
    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun markUnlocked() { _unlocked.value = true }

    /**
     * Системное окно подтверждения. На Android 10+ — отпечаток/лицо с запасным PIN-кодом;
     * на старых версиях — экран PIN-кода/графического ключа ([legacy] запускает его через ActivityResult).
     */
    fun authenticate(activity: Activity, legacy: (Intent) -> Unit) {
        if (!deviceSecure()) { markUnlocked(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle("Лоли")
                .setSubtitle("Подтвердите, что это вы")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = markUnlocked()
            })
        } else {
            @Suppress("DEPRECATION")
            val intent = context.getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent("Лоли", "Подтвердите, что это вы")
            if (intent == null) markUnlocked() else legacy(intent)
        }
    }

    /**
     * Отдельное подтверждение для секретных заметок — даже если приложение уже разблокировано.
     * [legacy] — экран PIN-кода на Android 9 и ниже (результат приходит через ActivityResult).
     */
    fun confirm(activity: Activity, title: String, onSuccess: () -> Unit, legacy: (Intent) -> Unit) {
        if (!deviceSecure()) { onSuccess(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle("Подтвердите, что это вы")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
            })
        } else {
            @Suppress("DEPRECATION")
            val intent = context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, "Подтвердите, что это вы")
            if (intent == null) onSuccess() else legacy(intent)
        }
    }

    private companion object {
        const val RELOCK_MS = 60_000L
    }
}
