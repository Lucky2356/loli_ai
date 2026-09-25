package ai.loli.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.loli.core.voice.ListenOptions
import ai.loli.core.voice.SpeechError
import ai.loli.core.voice.SpeechEvent
import ai.loli.core.voice.SpeechRecognitionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Системный распознаватель Android (обычно Google). Работает с русским языком, умеет офлайн,
 * если на устройстве скачан языковой пакет. Должен вызываться в главном потоке — обеспечено flowOn(Main).
 */
class AndroidSpeechRecognizerProvider(private val context: Context) : SpeechRecognitionProvider {
    override val id = "android"
    override val displayName = "Системный (Google)"

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(options: ListenOptions): Flow<SpeechEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var finished = false
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.SpeechStarted) }
            override fun onRmsChanged(rmsdB: Float) { trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { trySend(SpeechEvent.EndOfSpeech) }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                val kind = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.TIMEOUT
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SpeechError.NETWORK
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
                    SpeechRecognizer.ERROR_AUDIO -> SpeechError.AUDIO
                    else -> SpeechError.OTHER
                }
                trySend(SpeechEvent.Error(kind, message(kind, error)))
                close()
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) trySend(SpeechEvent.Error(SpeechError.NO_MATCH, "Не расслышала."))
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (options.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer.startListening(intent)
        awaitClose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }.flowOn(Dispatchers.Main)

    private fun message(kind: SpeechError, code: Int): String = when (kind) {
        SpeechError.NO_MATCH, SpeechError.TIMEOUT -> "Не расслышала. Попробуйте ещё раз."
        SpeechError.NO_PERMISSION -> "Нет доступа к микрофону."
        SpeechError.NETWORK -> "Распознаванию нужен интернет или офлайн-пакет русского языка."
        SpeechError.BUSY -> "Распознаватель занят, попробуйте через секунду."
        SpeechError.AUDIO -> "Ошибка записи звука."
        else -> "Ошибка распознавания ($code)." + if (Build.VERSION.SDK_INT >= 31) "" else ""
    }
}
