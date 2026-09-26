package ai.loli.app.llm

import android.content.Context
import android.os.Build
import ai.loli.core.ai.ChatMessage
import ai.loli.core.ai.LocalChat
import ai.loli.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Мост к нативной библиотеке llama.cpp (libloli_llm.so, только arm64). */
object LlamaNative {
    fun interface TokenListener { fun onToken(piece: String): Boolean }

    /** null — библиотека загружена; иначе причина, почему модель на этом телефоне недоступна. */
    val unsupportedReason: String? by lazy {
        when {
            Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" } -> "нужен 64-битный процессор ARM"
            // Сборка оптимизирована под ARMv8.2 с dot product (телефоны примерно с 2018 года).
            !cpuHasDotProd() -> "процессор телефона слишком старый для офлайн-модели"
            else -> runCatching { System.loadLibrary("loli_llm"); init(); null }.getOrElse { "библиотека не загрузилась: ${it.message}" }
        }
    }

    private fun cpuHasDotProd(): Boolean = runCatching {
        File("/proc/cpuinfo").readLines().any { it.startsWith("Features") && it.contains("asimddp") }
    }.getOrDefault(false)

    @JvmStatic external fun init()
    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun generate(handle: Long, messages: Array<String>, maxTokens: Int, temperature: Float, listener: TokenListener?): String
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun free(handle: Long)
}

/**
 * Офлайн-модель для разговора: Qwen 2.5 1.5B Instruct (Apache-2.0), ~1 ГБ.
 * Скачивается один раз из служебного релиза models-v1 этого репозитория (с докачкой), проверяется по sha256.
 * Загружается в память при первом вопросе и выгружается после 5 минут без вопросов — чтобы не держать 1,5 ГБ ОЗУ.
 */
class OfflineLlm(context: Context, private val scope: CoroutineScope) : LocalChat {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
        data class Unsupported(val reason: String) : State
    }

    private val dir = File(context.filesDir, "llm").apply { mkdirs() }
    private val file = File(dir, FILE)
    private val part = File(dir, "$FILE.part")
    private val prefs = context.getSharedPreferences("loli_llm", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    private val downloadLock = Mutex()
    @Volatile private var handle = 0L
    private var unloadJob: Job? = null

    private fun initialState(): State {
        LlamaNative.unsupportedReason?.let { return State.Unsupported(it) }
        return if (file.isFile && prefs.getBoolean(KEY_VERIFIED, false)) State.Ready else State.Missing
    }

    /** Пользователь включил офлайн-модель (скачал) и не выключил. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); if (!v) scope.launch { unload() } }

    override val available: Boolean get() = enabled && _state.value == State.Ready

    val sizeBytes: Long get() = SIZE

    suspend fun download(): Boolean = downloadLock.withLock {
        if (_state.value is State.Unsupported) return@withLock false
        if (_state.value == State.Ready) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                fetch()
                _state.value = State.Verifying
                if (sha256(part) != SHA256) { part.delete(); error("файл повреждён — попробуйте ещё раз") }
                file.delete()
                if (!part.renameTo(file)) error("не удалось сохранить модель")
                prefs.edit().putBoolean(KEY_VERIFIED, true).putBoolean(KEY_ENABLED, true).apply()
                _state.value = State.Ready
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Модель не скачана", e)
                _state.value = State.Failed(
                    when (e) {
                        is java.net.UnknownHostException -> "нет интернета"
                        is java.net.SocketTimeoutException -> "сервер не отвечает — нажмите ещё раз, загрузка продолжится"
                        is java.io.IOException -> if (dir.usableSpace < SIZE) "не хватает места (нужно ~1 ГБ)" else "связь прервалась — нажмите ещё раз, загрузка продолжится"
                        else -> e.message ?: "ошибка загрузки"
                    },
                )
                false
            }
        }
    }

    /** Докачка: если связь оборвалась, продолжаем с того же места (Range). */
    private fun fetch() {
        if (dir.usableSpace + part.length() < SIZE + 50_000_000) error("не хватает места (нужно ~1 ГБ)")
        var url = MODEL_URL
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "LoliAssistant")
                if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            if (conn.responseCode in 300..399 && redirects++ < 6) {
                url = java.net.URL(URL(url), conn.getHeaderField("Location") ?: error("редирект без адреса")).toString()
                conn.disconnect()
                continue
            }
            break
        }
        val resume = conn.responseCode == 206
        if (conn.responseCode == 416) { conn.disconnect(); return } // уже скачано целиком
        if (conn.responseCode !in 200..299) error("сервер ответил ${conn.responseCode}")
        if (!resume) part.delete()
        var done = part.length()
        var last = 0f
        conn.inputStream.use { input ->
            RandomAccessFile(part, "rw").use { out ->
                out.seek(done)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val p = (done.toFloat() / SIZE).coerceIn(0f, 1f)
                    if (p - last >= 0.005f) { last = p; _state.value = State.Downloading(p) }
                }
            }
        }
        conn.disconnect()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete() {
        scope.launch {
            unload()
            file.delete(); part.delete()
            prefs.edit().putBoolean(KEY_VERIFIED, false).apply()
            _state.value = initialState()
        }
    }

    override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int): String? {
        if (!available) return null
        return lock.withLock {
            withContext(Dispatchers.Default) {
                val h = ensureLoaded() ?: return@withContext null
                // Недавний разговор, но не длиннее контекста: старые реплики отбрасываем.
                val msgs = ArrayList<String>()
                msgs += "system"; msgs += system
                var budget = 3000
                val tail = history.takeLast(8).reversed().takeWhile { m -> budget -= m.content.length; budget > 0 }.reversed()
                for (m in tail) {
                    msgs += if (m.role == ChatMessage.Role.ASSISTANT) "assistant" else "user"
                    msgs += clean(m.content)
                }
                val out = runCatching { LlamaNative.generate(h, msgs.toTypedArray(), maxTokens, 0.6f, null) }
                    .onFailure { Logger.w(TAG, "Генерация не удалась", it) }.getOrNull()
                scheduleUnload()
                out?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Остановить ответ («стоп»). */
    fun stop() { handle.takeIf { it != 0L }?.let { LlamaNative.stop(it) } }

    private fun ensureLoaded(): Long? {
        if (handle != 0L) return handle
        val threads = Runtime.getRuntime().availableProcessors().let { (it - 2).coerceIn(2, 4) }
        val h = runCatching { LlamaNative.load(file.absolutePath, CONTEXT, threads) }.getOrDefault(0L)
        if (h == 0L) { Logger.w(TAG, "Модель не загрузилась"); return null }
        handle = h
        return h
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch { delay(5 * 60_000L); unload() }
    }

    private suspend fun unload() = lock.withLock {
        val h = handle
        handle = 0L
        if (h != 0L) withContext(Dispatchers.Default) { runCatching { LlamaNative.free(h) } }
    }

    /** Эмодзи и прочие символы вне BMP модели не нужны, а в JNI передаются неудобно. */
    private fun clean(s: String) = buildString { s.forEach { ch -> if (!Character.isSurrogate(ch)) append(ch) } }.take(2000)

    companion object {
        private const val TAG = "OfflineLlm"
        private const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MODEL_URL = "https://github.com/Lucky2356/loli_ai/releases/download/models-v1/loli-llm-qwen2.5-1.5b-q4_k_m.gguf"
        private const val SHA256 = "1adf0b11065d8ad2e8123ea110d1ec956dab4ab038eab665614adba04b6c3370"
        const val SIZE = 986_048_768L
        private const val CONTEXT = 2048
        private const val KEY_VERIFIED = "verified"
        private const val KEY_ENABLED = "enabled"
    }
}
