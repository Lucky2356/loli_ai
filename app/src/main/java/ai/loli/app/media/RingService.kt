package ai.loli.app.media

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.core.util.Logger

/**
 * Громкий сигнал: сработал таймер Лоли или «Лоли, где ты?». Звучит через поток будильника
 * (слышно и в беззвучном режиме), до касания «Стоп» или минуту. При поиске телефона ещё и мигает фонарик.
 */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restoreVolume: Int? = null
    private var flashing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Лоли"
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val loud = intent?.getBooleanExtra(EXTRA_LOUD, false) == true
        val stop = PendingIntent.getService(this, 0, Intent(this, RingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Коснитесь, чтобы остановить" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(0, "Стоп", stop)
            .build()
        try {
            ServiceCompat.startForeground(this, Notifications.RING_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            Logger.w(TAG, "Не удалось показать сигнал", e)
            Notifications.notifySafely(this, Notifications.RING_ID, n)
        }
        play(loud)
        if (loud) flash()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopAll() }, if (loud) 60_000L else 90_000L)
        return START_NOT_STICKY
    }

    private fun play(loud: Boolean) {
        player?.release()
        val audio = getSystemService(AudioManager::class.java)
        if (loud && audio != null) {
            restoreVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Logger.w(TAG, "Сигнал не проигрывается", it) }.getOrNull()
    }

    /** Мигание фонариком, чтобы найти телефон в темноте. */
    private fun flash() {
        val cm = getSystemService(CameraManager::class.java) ?: return
        val id = runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull() ?: return
        flashing = true
        var on = false
        val tick = object : Runnable {
            override fun run() {
                if (!flashing) { runCatching { cm.setTorchMode(id, false) }; return }
                on = !on
                runCatching { cm.setTorchMode(id, on) }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(tick)
    }

    private fun stopAll() {
        flashing = false
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        restoreVolume?.let { v -> runCatching { getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        restoreVolume = null
        runCatching {
            getSystemService(CameraManager::class.java)?.let { cm -> cm.cameraIdList.forEach { id -> runCatching { cm.setTorchMode(id, false) } } }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Notifications.cancel(this, Notifications.RING_ID)
        stopSelf()
    }

    override fun onDestroy() {
        flashing = false
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Ring"
        private const val ACTION_STOP = "ai.loli.ring.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_LOUD = "loud"

        fun start(context: Context, title: String, text: String, loud: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RingService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_LOUD, loud),
            )
        }.onFailure { Logger.w(TAG, "Сигнал не запустился", it) }.isSuccess

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RingService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
