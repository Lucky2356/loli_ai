package ai.loli.app.media

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.core.skills.RadioCatalog
import ai.loli.core.skills.RadioStation
import ai.loli.core.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Интернет-радио Лоли: поток играет в фоне с уведомлением «Пауза / Стоп / Следующая».
 * Медиасессия принимает кнопки гарнитуры и голосовые «пауза», «продолжи», «следующая».
 */
class RadioService : Service() {
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var station: RadioStation? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "LoliRadio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { station?.let { play(it) } }
                override fun onPause() = pause()
                override fun onStop() = stopAll()
                override fun onSkipToNext() = next()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_PAUSE -> { if (player?.isPlaying == true) pause() else station?.let { play(it) }; return START_NOT_STICKY }
            ACTION_NEXT -> { next(); return START_NOT_STICKY }
        }
        val name = intent?.getStringExtra(EXTRA_NAME)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (name == null || url == null) { if (player == null) stopSelf(); return START_NOT_STICKY }
        play(RadioStation(name, url))
        return START_NOT_STICKY
    }

    private fun play(s: RadioStation) {
        station = s
        current = s
        foreground(s, playing = true)
        player?.release()
        val audio = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        if (audio != null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) pause() }
                .build()
            focus = req
            audio.requestAudioFocus(req)
        }
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setOnPreparedListener { mp ->
                mp.start()
                state(PlaybackState.STATE_PLAYING)
                pendingResult?.complete(true)
            }
            setOnErrorListener { _, what, extra ->
                Logger.w(TAG, "Поток не играет: $what/$extra")
                pendingResult?.complete(false)
                stopAll()
                true
            }
            runCatching {
                setDataSource(s.url)
                prepareAsync()
            }.onFailure { pendingResult?.complete(false); stopAll() }
        }
        state(PlaybackState.STATE_BUFFERING)
    }

    private fun pause() {
        player?.let { runCatching { it.pause() } }
        state(PlaybackState.STATE_PAUSED)
        station?.let { foreground(it, playing = false) }
    }

    private fun next() {
        val list = RadioCatalog.POPULAR.map { it.second }
        val i = list.indexOfFirst { it.url == station?.url }
        play(list[(i + 1).mod(list.size)])
    }

    private fun state(s: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder().setState(s, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT)
                .build(),
        )
    }

    private fun action(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, RadioService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun foreground(s: RadioStation, playing: Boolean) {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(s.name)
            .setContentText(if (playing) "Радио · Лоли" else "Пауза")
            .setOngoing(playing)
            .setSilent(true)
            .addAction(0, if (playing) "Пауза" else "Играть", action(ACTION_PAUSE))
            .addAction(0, "Следующая", action(ACTION_NEXT))
            .addAction(0, "Стоп", action(ACTION_STOP))
            .setDeleteIntent(action(ACTION_STOP))
            .build()
        try {
            ServiceCompat.startForeground(this, Notifications.RADIO_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            Logger.w(TAG, "Радио не может работать в фоне", e)
        }
    }

    private fun stopAll() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        current = null
        focus?.let { f -> getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }
        state(PlaybackState.STATE_STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        current = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Radio"
        private const val ACTION_STOP = "ai.loli.radio.STOP"
        private const val ACTION_PAUSE = "ai.loli.radio.PAUSE"
        private const val ACTION_NEXT = "ai.loli.radio.NEXT"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"

        @Volatile var current: RadioStation? = null
            private set
        @Volatile private var pendingResult: CompletableDeferred<Boolean>? = null

        /** Запускает станцию и ждёт, пока поток начнёт играть (до 12 секунд). */
        suspend fun play(context: Context, station: RadioStation): Boolean {
            val result = CompletableDeferred<Boolean>()
            pendingResult = result
            val ok = runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RadioService::class.java).putExtra(EXTRA_NAME, station.name).putExtra(EXTRA_URL, station.url))
            }.isSuccess
            if (!ok) return false
            return withTimeoutOrNull(12_000) { result.await() } ?: (current?.url == station.url)
        }

        fun stop(context: Context): Boolean {
            val was = current != null
            if (was) runCatching { context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP)) }
            return was
        }
    }
}
