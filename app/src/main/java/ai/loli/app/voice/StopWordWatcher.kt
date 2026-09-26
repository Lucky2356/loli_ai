package ai.loli.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import ai.loli.core.data.LoliJson
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Recognizer

/** Слова, которые всегда останавливают Лоли — и в разговоре, и пока она говорит. */
object StopWords {
    private val PHRASES = setOf(
        "стоп", "стой", "хватит", "замолчи", "замолчи пожалуйста", "тихо", "остановись", "прекрати", "перестань", "молчи",
        "стоп стоп", "всё стоп", "все стоп", "лоли стоп", "стоп лоли", "хватит говорить",
    )

    /** Вся фраза — команда остановки (имя ассистента и «пожалуйста» не мешают). */
    fun isStop(text: String, name: String = "лоли"): Boolean {
        val skip = setOf(RuTokenizer.normalize(name).trim(), "пожалуйста", "лоли")
        val words = RuTokenizer.normalize(text).split(' ', ',', '.', '!', '?').map { it.trim() }.filter { it.isNotEmpty() && it !in skip }
        if (words.isEmpty()) return false
        return words.joinToString(" ") in PHRASES || words.all { it in SINGLE }
    }

    private val SINGLE = setOf("стоп", "стой", "хватит", "замолчи", "тихо", "молчи", "прекрати", "перестань", "остановись")

    /** Словарь для распознавателя: только слова остановки, всё остальное — «[unk]». */
    val GRAMMAR: String = (SINGLE.toList() + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }
}

/**
 * Слушает «стоп», пока Лоли говорит. Отдельная маленькая грамматика Vosk знает только слова остановки,
 * а запись идёт с эхоподавлением — собственный голос Лоли почти не мешает. Всё на устройстве.
 */
class StopWordWatcher(private val context: Context, private val engine: VoskEngine, private val models: VoskModelManager) {

    /** true — пользователь сказал «стоп»; false — слушать нельзя (нет модели/микрофона). Отменяется снаружи. */
    @SuppressLint("MissingPermission")
    suspend fun awaitStop(): Boolean = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext false
        // Встроенная модель распаковывается при первом ответе — со следующего ответа «стоп» работает и во время речи.
        if (!models.ensureReady()) return@withContext false
        val model = engine.model() ?: return@withContext false
        val rate = VoskSpeechProvider.SAMPLE_RATE.toInt()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext false
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            recognizer = Recognizer(model, rate.toFloat(), StopWords.GRAMMAR).apply { setWords(true) }
            // VOICE_COMMUNICATION включает системное эхоподавление: динамик (ответ Лоли) вычитается из микрофона.
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext false
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            record.startRecording()
            val buffer = ShortArray(minBuf / 2)
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (recognizer.acceptWaveForm(buffer, n) && heardStop(recognizer.result)) return@withContext true
            }
            false
        } catch (e: Exception) {
            Logger.w(TAG, "Не удалось слушать «стоп»", e)
            false
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { recognizer?.close() }
        }
    }

    /** Итог фразы: только слово остановки и с уверенностью распознавателя — иначе это эхо или шум. */
    private fun heardStop(json: String): Boolean {
        val obj = runCatching { LoliJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return false
        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!StopWords.isStop(text)) return false
        val words = runCatching { obj["result"]?.jsonArray.orEmpty() }.getOrDefault(emptyList())
        val conf = words.mapNotNull { runCatching { it.jsonObject["conf"]?.jsonPrimitive?.doubleOrNull }.getOrNull() }
        return conf.isEmpty() || conf.average() >= MIN_CONFIDENCE
    }

    companion object {
        private const val TAG = "StopWord"
        private const val MIN_CONFIDENCE = 0.85
    }
}
