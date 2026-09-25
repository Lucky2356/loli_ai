package ai.loli.app.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ai.loli.core.util.Logger

/**
 * Открытие других приложений (часы, звонилка, карты, любое приложение) — в том числе когда Лоли свёрнута.
 *
 * Android 10+ запрещает открывать окна из фона. Официальное исключение — разрешение
 * «Поверх других приложений» (SYSTEM_ALERT_WINDOW). На Android 15 для него дополнительно нужно видимое окно
 * приложения, поэтому на мгновение добавляется невидимое окно размером 1×1 пиксель.
 */
class BackgroundLauncher(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun isForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun canLaunchFromBackground(): Boolean = Settings.canDrawOverlays(context)

    /** Можно ли сейчас открыть окно (приложение на экране или есть разрешение). */
    fun canLaunch(): Boolean = isForeground() || canLaunchFromBackground()

    /** true — окно открыто; false — открыть нельзя (нет приложения или запрет Android). */
    fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isForeground()) return start(intent)
        if (!canLaunchFromBackground()) return false
        val anchor = addAnchorWindow()
        return try {
            start(intent)
        } finally {
            anchor?.let { v -> main.postDelayed({ removeWindow(v) }, 1500) }
        }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Logger.w(TAG, "Android не разрешил открыть окно", e)
        false
    }

    private fun addAnchorWindow(): View? = try {
        val wm = context.getSystemService(WindowManager::class.java)
        val view = View(context)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, params)
        view
    } catch (e: Exception) {
        Logger.w(TAG, "Не удалось добавить служебное окно", e)
        null
    }

    private fun removeWindow(view: View) {
        runCatching { context.getSystemService(WindowManager::class.java).removeView(view) }
    }

    companion object {
        private const val TAG = "Launcher"

        /** Экран системных настроек, где включается «Поверх других приложений». */
        fun overlaySettings(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    }
}
