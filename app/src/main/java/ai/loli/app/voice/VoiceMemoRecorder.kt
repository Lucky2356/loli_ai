package ai.loli.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import ai.loli.core.domain.NoteRepository
import ai.loli.core.model.NoteKind
import ai.loli.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Голосовая заметка со звуком: запись в WAV (16 кГц, моно) и одновременная расшифровка офлайн-моделью Vosk.
 * Файл хранится только на телефоне (папка приложения), в заметку попадает текст и метка «[аудио: имя.wav]».
 * Запись заканчивается кнопкой, после 4 секунд тишины (если уже говорили) или через 5 минут.
 */
class VoiceMemoRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notes: () -> NoteRepository,
    private val vosk: VoskEngine,
    private val models: VoskModelManager,
    /** Подождать, пока Лоли договорит ответ, — иначе её голос попадёт в запись. */
    private val busySpeaking: () -> Boolean,
) {
    sealed interface State {
        data object Idle : State
        data class Recording(val seconds: Int, val level: Float, val text: String) : State
        data object Saving : State
        data class Saved(val title: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    @Volatile private var stopRequested = false
    private var job: Job? = null
    private var player: MediaPlayer? = null

    val dir: File get() = File(context.filesDir, "voice").apply { mkdirs() }

    fun start() {
        if (job?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.Failed("Нет доступа к микрофону. Разрешите его в настройках Лоли.")
            return
        }
        stopRequested = false
        job = scope.launch(Dispatchers.IO) {
            var waited = 0
            while (busySpeaking() && waited < 60) { delay(100); waited++ }
            delay(300)
            runCatching { record() }.onFailure {
                Logger.e(TAG, "Запись не удалась", it)
                _state.value = State.Failed("Не получилось записать: микрофон занят. Попробуйте ещё раз.")
            }
        }
    }

    fun stop() { stopRequested = true }

    fun dismiss() { if (_state.value !is State.Recording && _state.value !is State.Saving) _state.value = State.Idle }

    @SuppressLint("MissingPermission")
    private suspend fun record() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("AudioRecord") }
        val stamp = LocalDateTime.now()
        val file = File(dir, "memo-" + stamp.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".wav")
        val model = if (models.ensureReady()) vosk.model() else null
        val recognizer = model?.let { runCatching { Recognizer(it, RATE.toFloat()) }.getOrNull() }
        val text = StringBuilder()
        var bytes = 0L
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(44)) // заголовок допишем в конце, когда известна длина
            val buf = ByteArray(RATE / 5 * 2) // 200 мс
            rec.startRecording()
            var silentMs = 0
            var spoke = false
            val started = System.currentTimeMillis()
            try {
                while (scope.isActive && !stopRequested) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    out.write(buf, 0, n)
                    bytes += n
                    val level = rms(buf, n)
                    if (level > SPEECH_LEVEL) { spoke = true; silentMs = 0 } else silentMs += n / 2 * 1000 / RATE
                    recognizer?.let { r ->
                        if (r.acceptWaveForm(buf, n)) voskText(r.result, "text").takeIf { it.isNotEmpty() }?.let { text.append(it).append(' ') }
                    }
                    val partial = recognizer?.let { voskText(it.partialResult, "partial") }.orEmpty()
                    val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
                    _state.value = State.Recording(secs, (level / 6000f).coerceIn(0f, 1f), (text.toString() + partial).trim())
                    if (spoke && silentMs >= SILENCE_STOP_MS) break
                    if (!spoke && secs >= NO_SPEECH_STOP_S) break
                    if (secs >= MAX_SECONDS) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            writeHeader(out, bytes)
        }
        _state.value = State.Saving
        recognizer?.let { r -> voskText(r.finalResult, "text").takeIf { it.isNotEmpty() }?.let { text.append(it) }; r.close() }
        val transcript = text.toString().trim().replaceFirstChar { it.uppercase() }
        if (bytes < RATE) { // меньше полсекунды — ничего не сказали
            file.delete()
            _state.value = State.Failed("Ничего не услышала — заметку не сохраняю.")
            return
        }
        val title = "Голосовая заметка " + stamp.format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru")))
        val body = (transcript.ifEmpty { "(без расшифровки — офлайн-модель речи не установлена)" }) + "\n\n" + MARK_START + file.name + MARK_END
        withContext(Dispatchers.IO) { notes().create(NoteKind.NOTE, title, body) }
        _state.value = State.Saved(title)
    }

    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            sum += s * s.toDouble(); i += 2
        }
        return Math.sqrt(sum / maxOf(1, n / 2)).toFloat()
    }

    private fun writeHeader(out: RandomAccessFile, dataLen: Long) {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.seek(0)
        out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
        out.write(le32(RATE.toLong())); out.write(le32(RATE * 2L)); out.write(le16(2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(dataLen))
    }

    /** Прослушать запись из заметки; повторное нажатие — стоп. */
    fun toggle(file: File): Boolean {
        player?.let { p -> runCatching { p.stop(); p.release() }; player = null; return false }
        if (!file.exists()) return false
        return runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { runCatching { it.release() }; player = null }
                prepare(); start()
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "VoiceMemo"
        private const val RATE = 16000
        private const val SPEECH_LEVEL = 900f
        private const val SILENCE_STOP_MS = 4000
        private const val NO_SPEECH_STOP_S = 12
        private const val MAX_SECONDS = 300
        const val MARK_START = "[аудио: "
        const val MARK_END = "]"

        /** Имя файла записи из текста заметки или null. */
        fun audioName(content: String): String? =
            Regex("""\[аудио: (memo-[\w-]+\.wav)]""").find(content)?.groupValues?.get(1)
    }
}
