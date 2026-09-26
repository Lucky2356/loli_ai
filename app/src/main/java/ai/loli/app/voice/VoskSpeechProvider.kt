package ai.loli.app.voice

import ai.loli.core.data.LoliJson
import ai.loli.core.util.Logger
import ai.loli.core.voice.ListenOptions
import ai.loli.core.voice.SpeechError
import ai.loli.core.voice.SpeechEvent
import ai.loli.core.voice.SpeechRecognitionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Разбор JSON-гипотез Vosk: {"partial": "..."} / {"text": "..."}. */
internal fun voskText(json: String?, key: String): String =
    runCatching { LoliJson.parseToJsonElement(json ?: "").jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("").trim()

/** Держит загруженную модель Vosk (загрузка — сотни миллисекунд, модель переиспользуется). */
class VoskEngine(private val models: VoskModelManager) {
    @Volatile private var model: Model? = null

    @Synchronized
    fun model(): Model? {
        model?.let { return it }
        if (!models.isReady()) return null
        return try {
            Model(models.modelDir.absolutePath).also { model = it }
        } catch (e: Exception) {
            Logger.e("Vosk", "Не удалось загрузить модель", e)
            null
        }
    }

    @Synchronized
    fun release() {
        runCatching { model?.close() }
        model = null
    }
}

/**
 * Офлайн-распознавание команд через Vosk: работает без интернета и без сервисов Google,
 * звук не покидает устройство. Качество ниже, чем у облачного распознавания.
 */
class VoskSpeechProvider(private val engine: VoskEngine, private val models: VoskModelManager) : SpeechRecognitionProvider {
    override val id = "vosk"
    override val displayName = "Офлайн (Vosk)"

    override fun isAvailable(): Boolean = models.isObtainable()

    override fun listen(options: ListenOptions): Flow<SpeechEvent> = callbackFlow {
        // Распаковка встроенной модели (первый запуск) и её загрузка занимают несколько секунд — не в UI-потоке.
        val model = withContext(Dispatchers.Default) { if (models.ensureReady()) engine.model() else null }
        if (model == null) {
            trySend(SpeechEvent.Error(SpeechError.UNAVAILABLE, "Офлайн-модель речи не установлена (Настройки → Голос)."))
            close()
            return@callbackFlow
        }
        var recognizer: Recognizer? = null
        val service = try {
            recognizer = Recognizer(model, SAMPLE_RATE)
            SpeechService(recognizer, SAMPLE_RATE)
        } catch (e: Exception) {
            // Микрофон занят другим приложением или недоступен.
            runCatching { recognizer?.close() }
            trySend(SpeechEvent.Error(SpeechError.BUSY, "Микрофон занят. Попробуйте ещё раз."))
            close()
            return@callbackFlow
        }
        var done = false
        // Vosk отдаёт результат после каждой короткой паузы. Склеиваем части, пока пауза не станет длиннее
        // выбранной в настройках, — иначе фраза «купи хлеб… и молоко» обрывалась бы на полуслове.
        val parts = ArrayList<String>()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val finish = Runnable {
            if (!done) {
                done = true
                trySend(SpeechEvent.Final(parts.joinToString(" ")))
                close()
            }
        }
        val waitAfterResult = (options.silenceMillis - VOSK_ENDPOINT_MS).coerceAtLeast(300L)
        service.startListening(object : RecognitionListener {
            private var started = false
            override fun onPartialResult(hypothesis: String?) {
                voskText(hypothesis, "partial").takeIf { it.isNotEmpty() }?.let {
                    if (!started) { started = true; trySend(SpeechEvent.SpeechStarted) }
                    handler.removeCallbacks(finish) // человек продолжает говорить
                    trySend(SpeechEvent.Partial((parts + it).joinToString(" ")))
                }
            }
            override fun onResult(hypothesis: String?) {
                val text = voskText(hypothesis, "text")
                if (text.isEmpty() || done) return
                parts += text
                handler.removeCallbacks(finish)
                handler.postDelayed(finish, waitAfterResult)
            }
            override fun onFinalResult(hypothesis: String?) {
                voskText(hypothesis, "text").takeIf { it.isNotEmpty() && !done }?.let { parts += it }
                if (parts.isNotEmpty()) { handler.removeCallbacks(finish); finish.run() }
            }
            override fun onError(exception: Exception?) {
                if (done) return
                done = true
                trySend(SpeechEvent.Error(SpeechError.AUDIO, "Микрофон недоступен: ${exception?.message ?: "ошибка записи"}")); close()
            }
            override fun onTimeout() {
                if (done) return
                if (parts.isNotEmpty()) { handler.removeCallbacks(finish); finish.run(); return }
                done = true
                trySend(SpeechEvent.Error(SpeechError.TIMEOUT, "Не расслышала.")); close()
            }
        }, TIMEOUT_MS)
        trySend(SpeechEvent.Ready)
        awaitClose {
            handler.removeCallbacks(finish)
            runCatching { service.stop() }
            runCatching { service.shutdown() }
            runCatching { recognizer?.close() }
        }
    }.flowOn(Dispatchers.Main)

    companion object {
        const val SAMPLE_RATE = 16000f
        private const val TIMEOUT_MS = 15_000
        /** Vosk сам ждёт около полусекунды тишины, прежде чем выдать результат. */
        private const val VOSK_ENDPOINT_MS = 500L
    }
}
