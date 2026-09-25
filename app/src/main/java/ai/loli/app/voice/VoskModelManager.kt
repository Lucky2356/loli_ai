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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Офлайн-модель распознавания русской речи Vosk (vosk-model-small-ru, лицензия Apache 2.0).
 *
 * Модель встроена в APK (assets/vosk/model-ru) и при первом использовании распаковывается во внутреннюю
 * память — ничего скачивать не нужно. Если сборка без встроенной модели (локальная разработка),
 * её можно скачать: сначала с зеркала в GitHub Releases, затем с сайта alphacephei.com.
 */
class VoskModelManager(private val context: Context) {
    sealed interface State {
        data object Missing : State
        data class Installing(val progress: Float) : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val root = File(context.filesDir, "vosk")
    val modelDir = File(root, MODEL_NAME)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isReady(): Boolean = File(modelDir, "am/final.mdl").exists() && File(modelDir, "conf/model.conf").exists() && !File(modelDir, INCOMPLETE).exists()

    /** Модель встроена в эту сборку приложения. */
    val isBundled: Boolean by lazy { runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false) }

    /** Модель есть или её можно получить без интернета. */
    fun isObtainable(): Boolean = isReady() || isBundled

    /** Готовит модель: распаковывает встроенную, а если её нет — скачивает. */
    suspend fun ensureReady(allowDownload: Boolean = false): Boolean = mutex.withLock {
        if (isReady()) { _state.value = State.Ready; return@withLock true }
        when {
            isBundled -> installFromAssets()
            allowDownload -> downloadLocked()
            else -> false
        }
    }

    suspend fun download(): Boolean = ensureReady(allowDownload = true)

    private suspend fun installFromAssets(): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(root, "tmp-assets").apply { deleteRecursively(); mkdirs() }
        try {
            _state.value = State.Installing(0f)
            val files = ArrayList<String>()
            collectAssets(ASSET_DIR, files)
            if (files.isEmpty()) error("встроенная модель пуста")
            files.forEachIndexed { i, path ->
                val out = File(tmp, path.removePrefix("$ASSET_DIR/"))
                out.parentFile?.mkdirs()
                context.assets.open(path).use { input -> FileOutputStream(out).use { input.copyTo(it, BUFFER) } }
                _state.value = State.Installing((i + 1f) / files.size)
            }
            replaceModel(tmp)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "Не удалось распаковать встроенную модель", e)
            tmp.deleteRecursively()
            _state.value = State.Failed("не удалось распаковать модель: ${e.message ?: "ошибка"}")
            false
        }
    }

    private fun collectAssets(path: String, out: MutableList<String>) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) { out += path; return }
        children.forEach { collectAssets("$path/$it", out) }
    }

    private suspend fun downloadLocked(): Boolean = withContext(Dispatchers.IO) {
        var lastError = "нет соединения"
        for (url in MODEL_URLS) {
            val tmp = File(root, "tmp").apply { deleteRecursively(); mkdirs() }
            try {
                _state.value = State.Downloading(0f)
                downloadAndUnzip(url, tmp)
                replaceModel(tmp)
                return@withContext true
            } catch (e: Exception) {
                Logger.w(TAG, "Зеркало модели недоступно: ${URL(url).host}", e)
                lastError = when (e) {
                    is java.net.UnknownHostException -> "нет интернета"
                    is java.net.SocketTimeoutException -> "сервер не отвечает"
                    is IOException -> e.message ?: "ошибка сети"
                    else -> e.message ?: "ошибка загрузки"
                }
                tmp.deleteRecursively()
            }
        }
        _state.value = State.Failed(lastError)
        false
    }

    private fun downloadAndUnzip(url: String, tmp: File) {
        var connection = open(url)
        // HttpURLConnection не переходит между http и https — обрабатываем редиректы сами.
        repeat(5) {
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: error("редирект без адреса")
                connection.disconnect()
                connection = open(URL(URL(url), next).toString())
            } else return@repeat
        }
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: APPROX_SIZE
        var read = 0L
        var lastReported = 0f
        val counting = object : java.io.FilterInputStream(connection.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    read += n
                    val p = (read.toFloat() / total).coerceIn(0f, 1f)
                    if (p - lastReported >= 0.005f) { lastReported = p; _state.value = State.Downloading(p) }
                }
                return n
            }
        }
        ZipInputStream(counting.buffered(BUFFER)).use { zip ->
            val canonicalTmp = tmp.canonicalPath
            var entry = zip.nextEntry
            var files = 0
            while (entry != null) {
                val out = File(tmp, entry.name)
                // Защита от zip slip: файлы только внутри каталога модели.
                if (!out.canonicalPath.startsWith(canonicalTmp + File.separator)) error("некорректный архив")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it, BUFFER) }
                    files++
                }
                entry = zip.nextEntry
            }
            if (files == 0) error("пустой архив")
        }
        connection.disconnect()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "LoliAssistant")
        connect()
    }

    /** Атомарная замена: модель не бывает «наполовину» установленной. */
    private fun replaceModel(tmp: File) {
        val extracted = if (File(tmp, "am").exists()) tmp else tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
        if (!File(extracted, "am/final.mdl").exists()) error("архив не содержит модель")
        modelDir.deleteRecursively()
        modelDir.parentFile?.mkdirs()
        if (!extracted.renameTo(modelDir)) {
            File(modelDir, INCOMPLETE).apply { parentFile?.mkdirs(); createNewFile() }
            extracted.copyRecursively(modelDir, overwrite = true)
            File(modelDir, INCOMPLETE).delete()
        }
        tmp.deleteRecursively()
        if (!isReady()) error("модель повреждена")
        _state.value = State.Ready
    }

    fun delete() {
        root.deleteRecursively()
        _state.value = State.Missing
    }

    companion object {
        private const val TAG = "Vosk"
        private const val ASSET_DIR = "vosk/model-ru"
        private const val INCOMPLETE = ".incomplete"
        private const val BUFFER = 64 * 1024
        const val MODEL_NAME = "vosk-model-small-ru-0.22"
        /** Зеркала по порядку: GitHub Releases этого проекта (доступен почти везде), затем сайт авторов Vosk. */
        val MODEL_URLS = listOf(
            "https://github.com/Lucky2356/loli_ai/releases/latest/download/$MODEL_NAME.zip",
            "https://alphacephei.com/vosk/models/$MODEL_NAME.zip",
        )
        private const val APPROX_SIZE = 45L * 1024 * 1024
    }
}
