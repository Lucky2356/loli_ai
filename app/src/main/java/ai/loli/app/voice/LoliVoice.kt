package ai.loli.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import ai.loli.core.util.Logger
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Голос Лоли» — встроенный офлайн-синтез русской речи (sherpa-onnx + Piper VITS).
 * Не зависит от синтезатора телефона: одинаково звучит на Vivo, Xiaomi, Huawei и любом другом.
 *
 * Текст делится на фразы: первая звучит почти сразу, следующие синтезируются, пока играет предыдущая.
 */
class LoliVoice(
    private val models: LoliVoiceModels,
    private val voiceId: () -> String,
    private val rate: () -> Float,
    private val pitch: () -> Float,
) {
    private val lock = Mutex()
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var loadedId: String? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    fun isAvailable(): Boolean = models.isReady(voiceId())

    private fun load(id: String): OfflineTts? {
        if (loadedId == id) engine?.let { return it }
        engine?.let { runCatching { it.release() } }
        engine = null; loadedId = null
        if (!models.isReady(id)) return null
        val dir = models.dir(id)
        val vits = OfflineTtsVitsModelConfig(
            model = File(dir, models.voice(id).modelFile).absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath,
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"),
        )
        return runCatching { OfflineTts(config = config) }
            .onFailure { Logger.w(TAG, "Не удалось загрузить голос $id", it) }
            .getOrNull()
            ?.also { engine = it; loadedId = id }
    }

    /** Заранее загрузить модель (первая фраза потом звучит без задержки). */
    suspend fun warmUp() = withContext(Dispatchers.Default) { lock.withLock { load(voiceId()) } }

    /** Проговаривает текст; false — голос не скачан или не загрузился. */
    suspend fun speak(text: String, voice: String = voiceId(), rateOverride: Float? = null, pitchOverride: Float? = null): Boolean {
        if (text.isBlank()) return true
        stopped = false
        return lock.withLock {
            val tts = withContext(Dispatchers.Default) { load(voice) } ?: return@withLock false
            val speed = (rateOverride ?: rate()).coerceIn(0.5f, 2f)
            val p = (pitchOverride ?: pitch()).coerceIn(0.5f, 2f)
            val sentences = split(text)
            // Синтез следующей фразы идёт параллельно с воспроизведением текущей.
            val audio = Channel<FloatArray>(capacity = 2)
            coroutineScope {
                launch(Dispatchers.Default) {
                    try {
                        for (s in sentences) {
                            if (stopped) break
                            val out = runCatching { tts.generate(s, 0, speed) }.getOrNull() ?: continue
                            audio.send(out.samples)
                        }
                    } finally {
                        audio.close()
                    }
                }
                withContext(Dispatchers.IO) {
                    val t = newTrack(tts.sampleRate(), p)
                    track = t
                    try {
                        t.play()
                        for (samples in audio) {
                            ensureActive()
                            if (stopped) break
                            var off = 0
                            while (off < samples.size && !stopped) {
                                val n = t.write(samples, off, samples.size - off, AudioTrack.WRITE_BLOCKING)
                                if (n <= 0) break
                                off += n
                            }
                        }
                        // Дождаться, пока буфер доиграет.
                        if (!stopped) waitDrained(t)
                    } finally {
                        runCatching { t.stop() }
                        runCatching { t.release() }
                        track = null
                    }
                }
            }
            true
        }
    }

    fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    fun release() {
        stop()
        engine?.let { runCatching { it.release() } }
        engine = null; loadedId = null
    }

    private fun newTrack(sampleRate: Int, pitch: Float): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Высота голоса — средствами воспроизведения (у модели своей настройки высоты нет).
        if (kotlin.math.abs(pitch - 1f) > 0.01f) runCatching { t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f) }
        return t
    }

    private suspend fun waitDrained(t: AudioTrack) {
        var lastPos = -1
        var still = 0
        while (!stopped && still < 3) {
            val pos = runCatching { t.playbackHeadPosition }.getOrDefault(0)
            if (pos == lastPos) still++ else still = 0
            lastPos = pos
            kotlinx.coroutines.delay(80)
        }
    }

    companion object {
        private const val TAG = "LoliVoice"

        /** Делит текст на фразы по знакам препинания; длинные фразы — по запятым. */
        fun split(text: String): List<String> {
            val parts = text.replace(Regex("""\s+"""), " ").trim()
                .split(Regex("""(?<=[.!?…:;])\s+|\n+"""))
                .flatMap { s -> if (s.length > 220) s.split(Regex("""(?<=,)\s+""")) else listOf(s) }
                .map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }
            return parts.ifEmpty { listOf(text.trim()) }
        }
    }
}
