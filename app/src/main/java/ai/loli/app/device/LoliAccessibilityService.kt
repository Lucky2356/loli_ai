package ai.loli.app.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import ai.loli.app.LoliApp
import ai.loli.app.assist.AssistActivity
import ai.loli.app.settings.KeyTrigger
import ai.loli.core.assistant.GlobalAction

/**
 * Спецвозможности Лоли: только системные кнопки по голосу — «назад», «домой», «недавние», «скриншот»,
 * «заблокируй экран», «открой уведомления». Содержимое экрана служба НЕ читает (canRetrieveWindowContent=false).
 * Включается пользователем вручную: Настройки → Спецвозможности → Лоли.
 */
class LoliAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Системная «кнопка/жест спецвозможностей» и быстрое включение (удержание обеих кнопок громкости),
        // если в настройках телефона они назначены на Лоли.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) = summon()
                })
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private val handler = Handler(Looper.getMainLooper())
    private var upDownAt = 0L
    private var downDownAt = 0L
    private var holdFired = false
    private var pendingDown: Runnable? = null
    private var lastDownTap = 0L

    /**
     * Вызов Лоли кнопками громкости. Остальные нажатия не теряются: если жест не состоялся,
     * громкость меняется так же, как без Лоли.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val trigger = (application as LoliApp).container.settings.settings.value.keyTrigger
        if (trigger == KeyTrigger.NONE) return false
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!up && !down) return false
        // Во время звонка и входящего вызова кнопки громкости — только для звонка (например, выключить звонок).
        val audio = getSystemService(AudioManager::class.java)
        if (audio != null && audio.mode != AudioManager.MODE_NORMAL) return false
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val now = SystemClock.uptimeMillis()
        return when (trigger) {
            KeyTrigger.HOLD_VOLUME_UP -> {
                if (!up) return false
                if (pressed && event.repeatCount == 0) {
                    holdFired = false
                    handler.postDelayed(holdRunnable, HOLD_MS)
                } else if (!pressed) {
                    handler.removeCallbacks(holdRunnable)
                    if (!holdFired) adjust(AudioManager.ADJUST_RAISE)
                }
                true
            }
            KeyTrigger.DOUBLE_VOLUME_DOWN -> {
                if (!down) return false
                // Удержание «−» — обычное плавное уменьшение громкости.
                if (pressed && event.repeatCount > 0) {
                    pendingDown?.let { handler.removeCallbacks(it) }
                    pendingDown = null
                    lastDownTap = 0
                    adjust(AudioManager.ADJUST_LOWER)
                    return true
                }
                if (pressed && event.repeatCount == 0) {
                    if (now - lastDownTap < DOUBLE_MS) {
                        pendingDown?.let { handler.removeCallbacks(it) }
                        pendingDown = null
                        lastDownTap = 0
                        summon()
                    } else {
                        lastDownTap = now
                        val r = Runnable { pendingDown = null; adjust(AudioManager.ADJUST_LOWER) }
                        pendingDown = r
                        handler.postDelayed(r, DOUBLE_MS)
                    }
                }
                true
            }
            KeyTrigger.BOTH_VOLUME -> {
                if (pressed) {
                    if (up) upDownAt = now else downDownAt = now
                    if (upDownAt > 0 && downDownAt > 0 && kotlin.math.abs(upDownAt - downDownAt) < BOTH_MS) {
                        upDownAt = 0; downDownAt = 0
                        summon()
                        return true
                    }
                } else {
                    if (up) upDownAt = 0 else downDownAt = 0
                }
                false // обычная регулировка громкости не блокируется
            }
            KeyTrigger.NONE -> false
        }
    }

    private val holdRunnable = Runnable { holdFired = true; summon() }

    private fun adjust(direction: Int) {
        runCatching {
            getSystemService(AudioManager::class.java)
                .adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        }
    }

    /** Открыть окно ассистента поверх текущего экрана (в том числе на экране блокировки). */
    private fun summon() {
        val c = (application as LoliApp).container
        val intent = Intent(this, AssistActivity::class.java).setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!c.launcher.launch(intent)) runCatching { startActivity(intent) }
    }
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val HOLD_MS = 550L
        private const val DOUBLE_MS = 350L
        private const val BOTH_MS = 300L

        @Volatile private var instance: LoliAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        /** null — служба не включена; false — действие недоступно на этой версии Android. */
        fun perform(action: GlobalAction): Boolean? {
            val service = instance ?: return null
            val code = when (action) {
                GlobalAction.BACK -> GLOBAL_ACTION_BACK
                GlobalAction.HOME -> GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
                GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                GlobalAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                GlobalAction.POWER_MENU -> GLOBAL_ACTION_POWER_DIALOG
                GlobalAction.SPLIT_SCREEN -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                GlobalAction.LOCK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
                GlobalAction.SCREENSHOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            }
            return service.performGlobalAction(code)
        }
    }
}
