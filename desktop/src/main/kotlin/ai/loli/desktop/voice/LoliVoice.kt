package ai.loli.desktop.voice

import ai.loli.core.data.LoliJson
import ai.loli.core.util.Logger
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * «Голос Лоли» на компьютере — тот же офлайн-синтез русской речи, что на телефоне (sherpa-onnx + Piper VITS).
 * Не зависит от голосов Windows: русский звучит по-русски, даже если в системе только английские голоса.
 * Голос по умолчанию вшит в установщик; остальные скачиваются из релиза voices-v1 и проверяются по sha256.
 */
class LoliVoice(private val dataDir: File) {
    data class Voice(val id: String, val title: String, val subtitle: String, val sha256: String, val bytes: Long) {
        val url get() = "https://github.com/Lucky2356/loli_ai/releases/download/voices-v1/loli-voice-ru-$id.zip"
        val folder get() = "vits-piper-ru_RU-$id-medium"
        val modelFile get() = "ru_RU-$id-medium.onnx"
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val states = VOICES.associate { it.id to MutableStateFlow<State>(if (isReady(it.id)) State.Ready else State.Missing) }
    fun state(id: String): StateFlow<State> = states[id] ?: states.getValue(DEFAULT)

    @Volatile private var engine: OfflineTts? = null
    @Volatile private var loadedId: String? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var stopped = false
    private val lock = Any()

    fun voice(id: String): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first { it.id == DEFAULT }

    private fun bundledRoot(): File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "loli-voice") }
    private val downloadedRoot = File(dataDir, "voices")

    /** Папка голоса: вшитая в установщик или скачанная; null — голоса нет. */
    fun dir(id: String): File? = listOfNotNull(bundledRoot(), downloadedRoot)
        .map { File(File(it, id), voice(id).folder) }
        .firstOrNull { complete(it, id) }

    private fun complete(d: File, id: String) =
        File(d, voice(id).modelFile).isFile && File(d, "tokens.txt").isFile && File(d, "espeak-ng-data").isDirectory

    fun isReady(id: String): Boolean = dir(id) != null

    /** Выбранный голос, а если его нет — любой готовый (обычно вшитый). */
    fun effective(id: String): String = if (isReady(id)) id else VOICES.firstOrNull { isReady(it.id) }?.id ?: id

    fun anyReady(): Boolean = VOICES.any { isReady(it.id) }

    /** Скачивание голоса (~65 МБ) с проверкой sha256. Блокирующий вызов — из фонового потока. */
    fun download(id: String): Boolean {
        val v = voice(id)
        val state = states.getValue(v.id)
        if (isReady(v.id)) { state.value = State.Ready; return true }
        val zip = File(downloadedRoot, "${v.id}.zip.part")
        val target = File(downloadedRoot, v.id)
        return try {
            downloadedRoot.mkdirs()
            state.value = State.Downloading(0)
            fetch(v, zip) { state.value = State.Downloading(it) }
            if (sha256(zip) != v.sha256) error("файл повреждён — попробуйте ещё раз")
            target.deleteRecursively(); target.mkdirs()
            unzip(zip, target)
            zip.delete()
            if (!isReady(v.id)) error("голос не распаковался")
            state.value = State.Ready
            true
        } catch (e: Exception) {
            Logger.w(TAG, "Голос ${v.id} не скачан", e)
            zip.delete(); target.deleteRecursively()
            state.value = State.Failed(
                when (e) {
                    is java.net.UnknownHostException -> "нет интернета"
                    is java.net.SocketTimeoutException -> "сервер не отвечает"
                    else -> e.message ?: "ошибка загрузки"
                },
            )
            false
        }
    }

    /** Удалить скачанный голос (вшитый в установщик не удаляется). */
    fun delete(id: String) {
        synchronized(lock) { if (loadedId == id) { engine?.let { runCatching { it.release() } }; engine = null; loadedId = null } }
        File(downloadedRoot, id).deleteRecursively()
        states[id]?.value = if (isReady(id)) State.Ready else State.Missing
    }

    private fun load(id: String): OfflineTts? {
        if (loadedId == id) engine?.let { return it }
        engine?.let { runCatching { it.release() } }
        engine = null; loadedId = null
        val dir = dir(id) ?: return null
        val modelFile = File(dir, voice(id).modelFile)
        // Параметры звучания, с которыми модель обучали, — из её .onnx.json (как на телефоне).
        val inference = runCatching {
            LoliJson.parseToJsonElement(File(modelFile.path + ".json").readText()).jsonObject["inference"]?.jsonObject
        }.getOrNull()
        fun param(key: String, default: Double) = inference?.get(key)?.jsonPrimitive?.doubleOrNull ?: default
        val vits = OfflineTtsVitsModelConfig.builder()
            .setModel(modelFile.absolutePath)
            .setTokens(File(dir, "tokens.txt").absolutePath)
            .setDataDir(File(dir, "espeak-ng-data").absolutePath)
            .setNoiseScale(param("noise_scale", 0.667).toFloat())
            .setNoiseScaleW(param("noise_w", 0.8).toFloat())
            .setLengthScale(param("length_scale", 1.0).toFloat().coerceIn(0.5f, 2f))
            .build()
        val model = OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(2).setDebug(false).setProvider("cpu").build()
        val config = OfflineTtsConfig.builder().setModel(model).build()
        return runCatching { OfflineTts(config) }
            .onFailure { Logger.e(TAG, "Голос $id не загрузился", it) }
            .getOrNull()
            ?.also { engine = it; loadedId = id }
    }

    /** Заранее загрузить модель (1–2 с), чтобы первая фраза прозвучала сразу. */
    fun warmUp(id: String) { synchronized(lock) { load(effective(id)) } }

    /**
     * Произносит текст и ждёт окончания (или [stop]). [speed] 0.5…2. false — голос не готов, нужно другое озвучивание.
     * Следующая фраза синтезируется, пока играет текущая.
     */
    fun speak(text: String, id: String, speed: Float): Boolean {
        synchronized(lock) { return speakLocked(text, id, speed) }
    }

    private fun speakLocked(text: String, id: String, speed: Float): Boolean {
        if (text.isBlank()) return true
        stopped = false
        val tts = load(effective(id)) ?: return false
        val rate = tts.sampleRate
        val parts = split(text)
        val queue = ArrayBlockingQueue<ShortArray>(2)
        val end = ShortArray(0)
        val producer = Thread({
            try {
                for (s in parts) {
                    if (stopped) break
                    val audio = runCatching { tts.generate(s, 0, speed.coerceIn(0.5f, 2f)) }.getOrNull() ?: continue
                    val pcm = toPcm(audio.samples)
                    while (!stopped && !queue.offer(pcm, 100, TimeUnit.MILLISECONDS)) Unit
                }
            } finally {
                while (!queue.offer(end, 100, TimeUnit.MILLISECONDS)) { if (stopped) { queue.clear() } }
            }
        }, "loli-voice-synth").apply { isDaemon = true; start() }
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val out = try {
            AudioSystem.getSourceDataLine(format).also { it.open(format, rate); it.start() }
        } catch (e: Exception) {
            Logger.w(TAG, "Нет устройства для звука", e)
            stopped = true; producer.join(2000)
            return false
        }
        line = out
        try {
            while (true) {
                val pcm = queue.poll(200, TimeUnit.MILLISECONDS) ?: if (stopped) break else continue
                if (pcm === end || stopped) break
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) { bytes[2 * i] = pcm[i].toInt().toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                var off = 0
                while (off < bytes.size && !stopped) {
                    val n = out.write(bytes, off, minOf(4096, bytes.size - off))
                    if (n <= 0) break
                    off += n
                }
            }
            if (!stopped) out.drain()
        } finally {
            runCatching { out.stop(); out.close() }
            line = null
            stopped = true
            producer.join(2000)
        }
        return true
    }

    /** Только синтез, без звука: для проверки голоса (тесты на Windows без звуковой карты). */
    internal fun synthesize(text: String, id: String): Pair<FloatArray, Int>? = synchronized(lock) {
        val tts = load(effective(id)) ?: return@synchronized null
        val audio = tts.generate(text, 0, 1f)
        audio.samples to audio.sampleRate
    }

    /** Замолчать сейчас же. */
    fun stop() {
        stopped = true
        line?.let { runCatching { it.stop(); it.flush() } }
    }

    fun release() {
        stop()
        synchronized(lock) { engine?.let { runCatching { it.release() } }; engine = null; loadedId = null }
    }

    private fun toPcm(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }

    private fun fetch(v: Voice, out: File, progress: (Int) -> Unit) {
        val conn = (URI(v.url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LoliAssistant")
        }
        if (conn.responseCode !in 200..299) error("сервер ответил ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: v.bytes
        var read = 0L
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n); read += n
                    progress((read * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File, target: File) {
        val base = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(target, e.name)
                // Защита от zip slip: только внутри папки голоса.
                if (!out.canonicalPath.startsWith(base)) error("некорректный архив")
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    companion object {
        private const val TAG = "LoliVoice"
        /** Вшит в установщик (scripts/prepare-desktop-resources.ps1). */
        const val DEFAULT = "denis"

        val VOICES = listOf(
            Voice("denis", "Денис", "Мужской", "8bcfc5cea11b0d943d03f6b4d4da0eacf8b5ec2af5c35ff021a03c65b16c2138", 67_424_164),
            Voice("dmitri", "Дмитрий", "Мужской", "bc5dedfdd158fed88391db3645fe13a4e93eebfb6bb2ab238b13ce9bd52bc52d", 67_424_225),
            Voice("irina", "Ирина", "Женский", "7f8b6410559edad2dcfab7fa4813f0c4a09b12748edbe584397b6b5fc62a9782", 67_404_557),
            Voice("ruslan", "Руслан", "Мужской", "ac37cb0ce13b7ad0d4f11075262d51c312d9b0956f0f9014adad078deff84120", 67_425_668),
            Voice("vera", "Вера", "Женский, мягкий", "65db3f0695b89785aea6ba27665688a1fee61d5c930f7e3ff84d3ab79bcf4b99", 67_434_356),
            Voice("sonya", "Соня", "Женский, звонкий", "29b5443d23b4f5ed14202fd06660d6411115f7f4d6f3c130cb0cb7cd2e1f7737", 67_432_916),
            Voice("asya", "Ася", "Женский, высокий", "ba6709df20422c797b76a4eaada6f8d2862a08824846a15c1c6d1b84a91aeae2", 67_429_932),
        )

        /** Делит текст на фразы, как на телефоне: первая звучит почти сразу. */
        fun split(text: String): List<String> {
            val parts = text.replace(Regex("""\s+"""), " ").trim()
                .split(Regex("""(?<=[.!?…:;])\s+|\n+"""))
                .flatMap { s -> if (s.length > 220) s.split(Regex("""(?<=,)\s+""")) else listOf(s) }
                .map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }
            return parts.ifEmpty { listOf(text.trim()) }
        }
    }
}
