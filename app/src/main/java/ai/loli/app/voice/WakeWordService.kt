package ai.loli.app.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.app.ui.MainActivity
import ai.loli.core.assistant.InputSource
import ai.loli.core.nlp.WakeWordMatcher
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Фоновое ожидание имени ассистента («Лоли, …»).
 *
 * Как это работает и почему так:
 *  - Android разрешает сторонним приложениям постоянный доступ к микрофону в фоне только из foreground service
 *    типа `microphone` с постоянным уведомлением; запускать его можно, пока приложение на экране (Android 14+).
 *  - Системный «аппаратный» hotword (HotwordDetectionService) доступен только предустановленным ассистентам.
 *  - Поэтому используется офлайн-распознавание Vosk прямо на устройстве: звук не покидает телефон,
 *    а имя ассистента можно поменять на любое слово.
 *  - Команда после имени распознаётся тем же Vosk («Лоли, запиши расход 500 рублей»);
 *    если после имени пауза — звучит сигнал, и следующая фраза считается командой.
 */
class WakeWordService : LifecycleService() {

    private enum class Mode { WAITING, COMMAND, PROCESSING }

    private val container get() = (application as LoliApp).container
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var matcher = WakeWordMatcher("Лоли")
    @Volatile private var mode = Mode.WAITING
    @Volatile private var commandDeadline = 0L
    /** Сколько реплик подряд прошло без повторного обращения по имени. */
    private var followUps = 0
    private var tone: ToneGenerator? = null
    /** Распознавание уже запускается — повторный startService не должен создавать второй микрофон. */
    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // appScope, а не lifecycleScope: запись не должна отмениться вместе с сервисом.
            container.appScope.launch { container.settings.setWakeWord(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Logger.w(TAG, "Нет разрешения на микрофон — фоновое прослушивание не запущено")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.LISTENING_ID, notification(getString(R.string.wake_command_starting)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Android 14+: запуск из фона запрещён — пользователь включит прослушивание, открыв приложение.
            Logger.w(TAG, "Не удалось запустить foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!initialized) {
            initialized = true
            lifecycleScope.launch { startRecognition() }
            lifecycleScope.launch { watchdog() }
            lifecycleScope.launch {
                // Экранное распознавание забирает микрофон — освобождаем его и возвращаемся после.
                container.voice.uiListening.collect { busy -> if (busy) stopVosk() else if (speechService == null && running) startVosk() }
            }
        }
        return START_STICKY
    }

    private suspend fun startRecognition() {
        val name = container.settings.current().assistantName
        matcher = WakeWordMatcher(name)
        updateNotification(getString(R.string.wake_listening, name))
        val model = withContext(Dispatchers.Default) { if (container.voskModels.ensureReady()) container.voskEngine.model() else null }
        if (model == null) {
            Logger.w(TAG, "Модель Vosk не загружена")
            updateNotification(getString(R.string.wake_model_missing))
            stopSelf()
            return
        }
        recognizer = try {
            Recognizer(model, VoskSpeechProvider.SAMPLE_RATE)
        } catch (e: Exception) {
            Logger.e(TAG, "Не удалось создать распознаватель", e)
            stopSelf()
            return
        }
        startVosk()
    }

    private fun startVosk() {
        val rec = recognizer ?: return
        if (speechService != null) return
        try {
            rec.reset()
            speechService = SpeechService(rec, VoskSpeechProvider.SAMPLE_RATE).also { it.startListening(listener) }
            container.voice.wakeHoldsMic.value = true
        } catch (e: Exception) {
            Logger.e(TAG, "Не удалось открыть микрофон", e)
        }
    }

    private fun stopVosk() {
        speechService?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        speechService = null
        container.voice.wakeHoldsMic.value = false
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Пока Лоли отвечает, микрофон слушает только «стоп».
            if (mode == Mode.PROCESSING && StopWords.isStop(voskText(hypothesis, "partial"), matcherName)) userStopped()
        }

        override fun onResult(hypothesis: String?) {
            val text = voskText(hypothesis, "text")
            if (text.isEmpty()) return
            when (mode) {
                Mode.WAITING -> {
                    followUps = 0
                    val match = matcher.match(text)
                    when {
                        match != null && match.command.split(" ").count { it.isNotBlank() } >= 1 -> dispatch(match.command)
                        match != null || matcher.containsWakeWord(text) -> if (!openAssistWindow()) enterCommandMode()
                    }
                }
                Mode.COMMAND -> dispatch(matcher.match(text)?.command?.ifBlank { null } ?: text)
                Mode.PROCESSING -> if (StopWords.isStop(text, matcherName)) userStopped()
            }
        }

        override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
        override fun onError(exception: Exception?) { Logger.w(TAG, "Ошибка Vosk: ${exception?.message}") }
        override fun onTimeout() = Unit
    }

    /**
     * Только имя без команды: если можно — открываем окно ассистента поверх текущего приложения
     * (оно само слушает дальше и показывает ответы). Иначе — сигнал и ждём команду здесь.
     */
    private fun openAssistWindow(): Boolean {
        if (!container.launcher.canLaunch()) return false
        val intent = Intent(this, ai.loli.app.assist.AssistActivity::class.java).setAction(Intent.ACTION_ASSIST)
        return container.launcher.launch(intent)
    }

    private fun enterCommandMode() {
        mode = Mode.COMMAND
        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
        beep()
        updateNotification(getString(R.string.wake_command))
    }

    @Volatile private var stoppedByUser = false
    private val matcherName get() = container.settings.settings.value.assistantName

    /** «Стоп» во время ответа: замолкаем и возвращаемся к ожиданию имени. */
    private fun userStopped() {
        if (stoppedByUser) return
        stoppedByUser = true
        container.voice.stop()
    }

    private fun dispatch(command: String) {
        mode = Mode.PROCESSING
        stoppedByUser = false
        // Микрофон не выключаем: во время ответа распознаётся только «стоп», остальное (в том числе голос Лоли) игнорируется.
        updateNotification(getString(R.string.wake_thinking))
        lifecycleScope.launch {
            val reply = try {
                container.voice.handleRecognized(command, InputSource.WAKE_WORD)
            } catch (e: Exception) {
                Logger.e(TAG, "Ошибка обработки команды", e)
                null
            }
            delay(300)
            // Сбрасываем хвост собственного ответа; на время сброса распознавание на паузе (без гонки потоков).
            speechService?.setPause(true)
            recognizer?.reset()
            speechService?.setPause(false)
            val s = container.settings.settings.value
            val wantsMore = reply != null && reply.expectFollowUp && (s.dialogModeEnabled || reply.awaitingAnswer || reply.awaitingConfirmation)
            if (wantsMore && !stoppedByUser && followUps < MAX_FOLLOW_UPS) {
                followUps++
                enterCommandMode()
            } else {
                backToWaiting()
            }
        }
    }

    private suspend fun watchdog() {
        while (true) {
            delay(1000)
            if (mode == Mode.COMMAND && System.currentTimeMillis() > commandDeadline) backToWaiting()
        }
    }

    /** Возврат к ожиданию имени: диалог закончен (тишина, лимит), ассистент выходит из режима диалога. */
    private fun backToWaiting() {
        mode = Mode.WAITING
        followUps = 0
        lifecycleScope.launch { container.voice.endDialog() }
        updateNotification(getString(R.string.wake_listening, container.settings.settings.value.assistantName))
    }

    private fun beep() {
        runCatching {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.wake_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        Notifications.notifySafely(this, Notifications.LISTENING_ID, notification(text))
    }

    override fun onDestroy() {
        running = false
        stopVosk()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { tone?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val ACTION_STOP = "ai.loli.action.STOP_WAKE"
        private const val COMMAND_WINDOW_MS = 8_000L
        private const val MAX_FOLLOW_UPS = 12

        @Volatile var running = false
            private set

        /** Запускать только когда приложение на экране (ограничение Android 14+ для микрофона). */
        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
                .onFailure { Logger.w(TAG, "Не удалось запустить сервис", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
