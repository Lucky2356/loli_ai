package ai.loli.app.voice

import android.content.Context
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Встроенные русские голоса Лоли (Piper VITS для sherpa-onnx). Скачиваются один раз из служебного
 * релиза voices-v1 этого же репозитория (оттуда же приходят обновления) и проверяются по sha256 —
 * подменённый или повреждённый файл не установится.
 */
class LoliVoiceModels(context: Context) {
    data class Voice(val id: String, val title: String, val subtitle: String, val sha256: String, val bytes: Long) {
        val url get() = "https://github.com/$REPO/releases/download/$RELEASE/loli-voice-ru-$id.zip"
        val folder get() = "vits-piper-ru_RU-$id-medium"
        val modelFile get() = "ru_RU-$id-medium.onnx"
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Installing : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val root = File(context.filesDir, "loli-voice")
    private val mutex = Mutex()
    private val states = VOICES.associate { it.id to MutableStateFlow<State>(if (isReady(it.id)) State.Ready else State.Missing) }

    fun state(id: String): StateFlow<State> = (states[id] ?: states.getValue(DEFAULT)).asStateFlow()

    fun voice(id: String): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first { it.id == DEFAULT }

    fun dir(id: String): File = File(File(root, id), voice(id).folder)

    fun isReady(id: String): Boolean {
        val d = dir(id)
        return File(d, voice(id).modelFile).isFile && File(d, "tokens.txt").isFile && File(d, "espeak-ng-data").isDirectory &&
            !File(root, "$id/$INCOMPLETE").exists()
    }

    fun anyReady(): Boolean = VOICES.any { isReady(it.id) }

    /** Выбранный голос, а если он не скачан — любой уже скачанный (чтобы смена голоса по умолчанию не оставила без голоса). */
    fun effective(id: String): String = if (isReady(id)) id else VOICES.firstOrNull { isReady(it.id) }?.id ?: id

    suspend fun download(id: String): Boolean = mutex.withLock {
        val v = voice(id)
        val state = states.getValue(v.id)
        if (isReady(v.id)) { state.value = State.Ready; return@withLock true }
        withContext(Dispatchers.IO) {
            val target = File(root, v.id)
            val zip = File(root, "${v.id}.zip.part")
            try {
                root.mkdirs()
                state.value = State.Downloading(0f)
                fetch(v, zip) { state.value = State.Downloading(it) }
                state.value = State.Installing
                if (sha256(zip) != v.sha256) error("файл повреждён — попробуйте ещё раз")
                target.deleteRecursively(); target.mkdirs()
                File(target, INCOMPLETE).createNewFile()
                unzip(zip, target)
                File(target, INCOMPLETE).delete()
                zip.delete()
                if (!isReady(v.id)) error("голос не распаковался")
                state.value = State.Ready
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Голос ${v.id} не скачан", e)
                zip.delete()
                target.deleteRecursively()
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
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        states[id]?.value = State.Missing
    }

    private fun fetch(v: Voice, out: File, progress: (Float) -> Unit) {
        var conn = open(v.url)
        repeat(5) {
            if (conn.responseCode in 300..399) {
                val next = conn.getHeaderField("Location") ?: error("редирект без адреса")
                conn.disconnect()
                conn = open(URL(URL(v.url), next).toString())
            }
        }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: v.bytes
        var read = 0L
        var last = 0f
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    val p = (read.toFloat() / total).coerceIn(0f, 1f)
                    if (p - last >= 0.01f) { last = p; progress(p) }
                }
            }
        }
        conn.disconnect()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "LoliAssistant")
        connect()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File, target: File) {
        val base = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered(BUFFER)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val out = File(target, e.name)
                // Защита от zip slip: только внутри каталога голоса.
                if (!out.canonicalPath.startsWith(base)) error("некорректный архив")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { z.copyTo(it, BUFFER) }
                }
                e = z.nextEntry
            }
        }
    }

    companion object {
        private const val TAG = "LoliVoice"
        private const val REPO = "Lucky2356/loli_ai"
        private const val RELEASE = "voices-v1"
        private const val INCOMPLETE = ".incomplete"
        private const val BUFFER = 64 * 1024
        const val DEFAULT = "denis"

        val VOICES = listOf(
            Voice("denis", "Денис", "Мужской", "8bcfc5cea11b0d943d03f6b4d4da0eacf8b5ec2af5c35ff021a03c65b16c2138", 67_424_164),
            Voice("dmitri", "Дмитрий", "Мужской", "bc5dedfdd158fed88391db3645fe13a4e93eebfb6bb2ab238b13ce9bd52bc52d", 67_424_225),
            Voice("irina", "Ирина", "Женский", "7f8b6410559edad2dcfab7fa4813f0c4a09b12748edbe584397b6b5fc62a9782", 67_404_557),
            Voice("ruslan", "Руслан", "Мужской", "ac37cb0ce13b7ad0d4f11075262d51c312d9b0956f0f9014adad078deff84120", 67_425_668),
        )
    }
}
