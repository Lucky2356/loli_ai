package ai.loli.desktop.voice

import ai.loli.core.data.LoliJson
import ai.loli.core.util.Logger
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Распознавание речи без интернета: микрофон → Vosk (та же русская модель, что на телефоне).
 * Модель вшита в установщик; если её нет (запуск из исходников) — скачивается один раз в папку данных.
 */
class SpeechInput(private val dataDir: File) {
    sealed interface ModelState {
        data object Ready : ModelState
        data object Missing : ModelState
        data class Downloading(val percent: Int) : ModelState
        data class Failed(val message: String) : ModelState
    }

    @Volatile private var model: Model? = null
    @Volatile private var stopRequested = false

    private fun bundled(): File? = System.getProperty("compose.application.resources.dir")?.let { File(it, MODEL_DIR) }?.takeIf(::complete)
    private val downloaded: File get() = File(dataDir, MODEL_DIR)
    private fun complete(dir: File) = File(dir, "am/final.mdl").exists() && File(dir, "conf/model.conf").exists()

    fun modelDir(): File? = bundled() ?: downloaded.takeIf(::complete)

    fun hasMicrophone(): Boolean = runCatching { AudioSystem.isLineSupported(javax.sound.sampled.DataLine.Info(TargetDataLine::class.java, FORMAT)) }.getOrDefault(false)

    /** Скачивает модель (~45 МБ) с сайта Vosk, если её нет ни в установщике, ни в папке данных. */
    fun ensureModel(progress: (Int) -> Unit): Boolean {
        if (modelDir() != null) return true
        return runCatching {
            val tmp = File(dataDir, "$MODEL_DIR.zip.part")
            val conn = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); read += n
                        if (total > 0) progress((read * 100 / total).toInt())
                    }
                }
            }
            val target = downloaded
            target.deleteRecursively()
            val root = dataDir.canonicalFile
            ZipInputStream(tmp.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // В архиве всё лежит в папке модели; кладём содержимое в нашу папку и не даём выйти за её пределы.
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(target, rel).canonicalFile
                    if (!out.path.startsWith(root.path)) continue
                    if (e.isDirectory) out.mkdirs() else { out.parentFile.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            tmp.delete()
            complete(target)
        }.onFailure { Logger.w(TAG, "Модель речи не скачалась", it) }.getOrDefault(false)
    }

    @Synchronized
    private fun loadModel(): Model? {
        model?.let { return it }
        val dir = modelDir() ?: return null
        return runCatching {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath).also { model = it }
        }.onFailure { Logger.e(TAG, "Модель речи не загрузилась", it) }.getOrNull()
    }

    fun stop() { stopRequested = true }

    /**
     * Слушает одну фразу: заканчивает после паузы 1,2 с (если уже говорили), через 8 с тишины или по [stop].
     * [onPartial] — текст по ходу и уровень громкости 0…1. Возвращает распознанный текст (может быть пустым).
     */
    fun listen(onPartial: (String, Float) -> Unit): String {
        val m = loadModel() ?: throw IllegalStateException("Модель речи не установлена")
        stopRequested = false
        val line = AudioSystem.getTargetDataLine(FORMAT)
        line.open(FORMAT, RATE / 5 * 2)
        val text = StringBuilder()
        Recognizer(m, RATE.toFloat()).use { rec ->
            line.start()
            try {
                val buf = ByteArray(RATE / 10 * 2) // 100 мс
                var silentMs = 0
                var spoke = false
                var totalMs = 0
                while (!stopRequested) {
                    val n = line.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    totalMs += 100
                    val level = rms(buf, n)
                    if (level > SPEECH_LEVEL) { spoke = true; silentMs = 0 } else silentMs += 100
                    if (rec.acceptWaveForm(buf, n)) json(rec.result, "text").takeIf { it.isNotEmpty() }?.let { text.append(it).append(' ') }
                    onPartial((text.toString() + json(rec.partialResult, "partial")).trim(), (level / 5000f).coerceIn(0f, 1f))
                    if (spoke && silentMs >= END_SILENCE_MS) break
                    if (!spoke && totalMs >= NO_SPEECH_MS) break
                    if (totalMs >= MAX_MS) break
                }
            } finally {
                runCatching { line.stop(); line.close() }
            }
            json(rec.finalResult, "text").takeIf { it.isNotEmpty() }?.let { text.append(it) }
        }
        return text.toString().trim()
    }

    private fun json(s: String?, key: String): String =
        runCatching { LoliJson.parseToJsonElement(s ?: "").jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("").trim()

    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            sum += v.toDouble() * v; i += 2
        }
        return Math.sqrt(sum / maxOf(1, n / 2)).toFloat()
    }

    companion object {
        private const val TAG = "SpeechInput"
        const val MODEL_DIR = "vosk-model-ru"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        private const val RATE = 16000
        private val FORMAT = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        private const val SPEECH_LEVEL = 700f
        private const val END_SILENCE_MS = 1200
        private const val NO_SPEECH_MS = 8000
        private const val MAX_MS = 30_000
    }
}
