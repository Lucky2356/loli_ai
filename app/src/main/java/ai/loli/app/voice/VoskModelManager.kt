package ai.loli.app.voice

import android.content.Context
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Офлайн-модель распознавания русской речи Vosk (vosk-model-small-ru, ~45 МБ, лицензия Apache 2.0).
 * Скачивается по явному действию пользователя и хранится во внутренней памяти приложения.
 * Нужна для фонового wake word и офлайн-распознавания: звук никуда не отправляется.
 */
class VoskModelManager(private val context: Context) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val root = File(context.filesDir, "vosk")
    val modelDir = File(root, MODEL_NAME)
    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isReady(): Boolean = File(modelDir, "am/final.mdl").exists() || File(modelDir, "conf/model.conf").exists()

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        if (isReady()) { _state.value = State.Ready; return@withContext true }
        val tmp = File(root, "tmp").apply { deleteRecursively(); mkdirs() }
        try {
            _state.value = State.Downloading(0f)
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            connection.connect()
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: APPROX_SIZE
            var read = 0L
            val counting = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        _state.value = State.Downloading((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                var entry = zip.nextEntry
                val canonicalTmp = tmp.canonicalPath
                while (entry != null) {
                    val out = File(tmp, entry.name)
                    // Защита от zip slip: файлы только внутри каталога модели.
                    if (!out.canonicalPath.startsWith(canonicalTmp + File.separator)) error("Некорректный архив")
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
            val extracted = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
            modelDir.deleteRecursively()
            if (!extracted.renameTo(modelDir)) extracted.copyRecursively(modelDir, overwrite = true)
            tmp.deleteRecursively()
            if (!isReady()) error("архив не содержит модель")
            _state.value = State.Ready
            true
        } catch (e: Exception) {
            Logger.w(TAG, "Не удалось скачать модель Vosk", e)
            tmp.deleteRecursively()
            _state.value = State.Failed(e.message ?: "ошибка загрузки")
            false
        }
    }

    fun delete() {
        root.deleteRecursively()
        _state.value = State.Missing
    }

    companion object {
        private const val TAG = "Vosk"
        const val MODEL_NAME = "vosk-model-small-ru-0.22"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        private const val APPROX_SIZE = 45L * 1024 * 1024
    }
}
