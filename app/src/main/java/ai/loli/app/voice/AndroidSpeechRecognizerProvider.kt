package ai.loli.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.loli.core.util.Logger
import ai.loli.core.voice.ListenOptions
import ai.loli.core.voice.SpeechError
import ai.loli.core.voice.SpeechEvent
import ai.loli.core.voice.SpeechRecognitionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Системное распознавание речи Android.
 *
 * Почему не просто `createSpeechRecognizer(context)`: на многих телефонах (Samsung, Xiaomi, Huawei) по умолчанию
 * выбран сервис производителя, который не понимает русский или сразу возвращает ошибку. Поэтому явно перебираем
 * установленные сервисы: сначала Google (приложение Google, «Распознавание речи от Google»), затем остальные.
 * Если сервис сломан/занят — переходим к следующему; если не работает ни один, VoiceController переключится на Vosk.
 * Собственный сервис Лоли (он на Vosk) в перебор не входит.
 */
class AndroidSpeechRecognizerProvider(private val context: Context) : SpeechRecognitionProvider {
    override val id = "android"
    override val displayName = "Системный (Google)"

    /** Сервисы распознавания на устройстве в порядке предпочтения. */
    fun services(): List<ComponentName> {
        val found = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        return found.mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .map { ComponentName(it.packageName, it.name) }
            .distinct()
            .sortedBy { priority(it.packageName) }
    }

    fun serviceLabel(component: ComponentName): String = when (component.packageName) {
        GOOGLE_APP -> "Google"
        GOOGLE_SPEECH -> "Распознавание речи Google"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(component.packageName, 0)).toString()
        }.getOrDefault(component.packageName)
    }

    /** Доступно, если есть сторонний сервис (собственный сервис Лоли — это тот же Vosk, его вызываем напрямую). */
    override fun isAvailable(): Boolean = services().isNotEmpty()

    override fun listen(options: ListenOptions): Flow<SpeechEvent> = flow {
        // null — системный сервис по умолчанию (если перечислить сервисы не удалось).
        val candidates: List<ComponentName?> = services().ifEmpty { if (SpeechRecognizer.isRecognitionAvailable(context)) listOf(null) else emptyList() }
        if (candidates.isEmpty()) {
            emit(SpeechEvent.Error(SpeechError.UNAVAILABLE, "На телефоне нет системного распознавания речи."))
            return@flow
        }
        for ((index, component) in candidates.withIndex()) {
            var busyRetries = 2
            while (true) {
                var failure: SpeechEvent.Error? = null
                session(component, options).collect { e ->
                    if (e is SpeechEvent.Error && e.kind in SERVICE_FAILURES) failure = e else emit(e)
                }
                val f = failure ?: return@flow
                Logger.w(TAG, "Сервис ${component?.packageName ?: "по умолчанию"} не сработал: ${f.message}")
                if (f.kind == SpeechError.BUSY && busyRetries-- > 0) {
                    delay(450) // предыдущая сессия ещё освобождает микрофон
                    continue
                }
                if (index == candidates.lastIndex) {
                    emit(SpeechEvent.Error(SpeechError.UNAVAILABLE, f.message))
                    return@flow
                }
                delay(150)
                break
            }
        }
    }

    /** Одна сессия распознавания конкретным сервисом. Должна работать в главном потоке. */
    private fun session(component: ComponentName?, options: ListenOptions): Flow<SpeechEvent> = callbackFlow {
        val recognizer = try {
            if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component) else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            trySend(SpeechEvent.Error(SpeechError.OTHER, "Не удалось подключиться к сервису распознавания."))
            close()
            return@callbackFlow
        }
        var finished = false
        var ready = false
        var heard = false
        val label = component?.let { serviceLabel(it) } ?: "системный"
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ready = true; trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { heard = true; trySend(SpeechEvent.SpeechStarted) }
            override fun onRmsChanged(rmsdB: Float) { trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { trySend(SpeechEvent.EndOfSpeech) }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                val kind = when (error) {
                    // «Ничего не распознано» до того, как сервис вообще начал слушать, — это сбой сервиса, а не тишина.
                    ERROR_NO_MATCH -> if (ready || heard) SpeechError.NO_MATCH else SpeechError.OTHER
                    ERROR_SPEECH_TIMEOUT -> SpeechError.TIMEOUT
                    ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
                    ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER -> SpeechError.NETWORK
                    ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
                    ERROR_AUDIO -> SpeechError.AUDIO
                    else -> SpeechError.OTHER // клиент, отключение сервиса, язык не поддерживается
                }
                trySend(SpeechEvent.Error(kind, message(kind, error, label)))
                close()
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }.orEmpty()
                if (text.isBlank()) trySend(SpeechEvent.Error(SpeechError.NO_MATCH, "Не расслышала. Попробуйте ещё раз."))
                else trySend(SpeechEvent.Final(text))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, options.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (options.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Даём договорить: пауза в 2 секунды не обрывает фразу.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            finished = true
            trySend(SpeechEvent.Error(SpeechError.OTHER, "Сервис «$label» не запустился."))
            close()
        }
        awaitClose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Main)

    private fun message(kind: SpeechError, code: Int, label: String): String = when (kind) {
        SpeechError.NO_MATCH, SpeechError.TIMEOUT -> "Не расслышала. Попробуйте ещё раз."
        SpeechError.NO_PERMISSION -> "Нет доступа к микрофону."
        SpeechError.NETWORK -> "Распознаванию «$label» нужен интернет."
        SpeechError.BUSY -> "Распознаватель занят, попробуйте через секунду."
        SpeechError.AUDIO -> "Микрофон занят другим приложением."
        else -> "Сервис «$label» не смог распознать речь (код $code)."
    }

    companion object {
        private const val TAG = "SystemStt"
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        const val GOOGLE_SPEECH = "com.google.android.tts"

        // Коды SpeechRecognizer (часть появилась в новых API — используем значения напрямую).
        private const val ERROR_NETWORK_TIMEOUT = 1
        private const val ERROR_NETWORK = 2
        private const val ERROR_AUDIO = 3
        private const val ERROR_SERVER = 4
        private const val ERROR_SPEECH_TIMEOUT = 6
        private const val ERROR_NO_MATCH = 7
        private const val ERROR_RECOGNIZER_BUSY = 8
        private const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        private const val ERROR_TOO_MANY_REQUESTS = 10

        /** Ошибки, при которых стоит попробовать другой сервис. */
        private val SERVICE_FAILURES = setOf(SpeechError.OTHER, SpeechError.BUSY, SpeechError.UNAVAILABLE)

        private fun priority(pkg: String) = when (pkg) {
            GOOGLE_APP -> 0
            GOOGLE_SPEECH -> 1
            else -> 2
        }
    }
}
