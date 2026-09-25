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

    override fun isAvailable(): Boolean = models.isReady()

    override fun listen(options: ListenOptions): Flow<SpeechEvent> = callbackFlow {
        val model = engine.model()
        if (model == null) {
            trySend(SpeechEvent.Error(SpeechError.UNAVAILABLE, "Офлайн-модель речи не загружена (Настройки → Голос)."))
            close()
            return@callbackFlow
        }
        val recognizer = Recognizer(model, SAMPLE_RATE)
        val service = SpeechService(recognizer, SAMPLE_RATE)
        var done = false
        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                voskText(hypothesis, "partial").takeIf { it.isNotEmpty() }?.let { trySend(SpeechEvent.Partial(it)) }
            }
            override fun onResult(hypothesis: String?) {
                val text = voskText(hypothesis, "text")
                if (text.isNotEmpty() && !done) { done = true; trySend(SpeechEvent.Final(text)); close() }
            }
            override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
            override fun onError(exception: Exception?) {
                trySend(SpeechEvent.Error(SpeechError.AUDIO, exception?.message ?: "Ошибка микрофона")); close()
            }
            override fun onTimeout() {
                trySend(SpeechEvent.Error(SpeechError.TIMEOUT, "Не расслышала.")); close()
            }
        }, TIMEOUT_MS)
        trySend(SpeechEvent.Ready)
        awaitClose {
            runCatching { service.stop() }
            runCatching { service.shutdown() }
            runCatching { recognizer.close() }
        }
    }.flowOn(Dispatchers.Main)

    companion object {
        const val SAMPLE_RATE = 16000f
        private const val TIMEOUT_MS = 10_000
    }
}
