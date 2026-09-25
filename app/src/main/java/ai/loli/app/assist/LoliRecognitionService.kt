package ai.loli.app.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import ai.loli.app.LoliApp
import ai.loli.core.voice.ListenOptions
import ai.loli.core.voice.SpeechError
import ai.loli.core.voice.SpeechEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Офлайн-распознавание речи Лоли (Vosk) как системный сервис. Нужен Android, чтобы Лоли можно было выбрать
 * цифровым ассистентом; заодно другие приложения могут распознавать русскую речь без интернета.
 */
class LoliRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastPartial = ""
    private var current: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        job?.cancel()
        current = listener
        lastPartial = ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            safe { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        val container = (application as LoliApp).container
        val partial = recognizerIntent?.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) ?: false
        job = scope.launch {
            container.offlineStt.listen(ListenOptions(preferOffline = true)).collect { e ->
                when (e) {
                    SpeechEvent.Ready -> safe { listener.readyForSpeech(Bundle()) }
                    SpeechEvent.SpeechStarted -> safe { listener.beginningOfSpeech() }
                    is SpeechEvent.Level -> safe { listener.rmsChanged(e.value * 10f) }
                    is SpeechEvent.Partial -> {
                        lastPartial = e.text
                        if (partial) safe { listener.partialResults(results(e.text)) }
                    }
                    is SpeechEvent.Final -> {
                        safe { listener.endOfSpeech() }
                        safe { listener.results(results(e.text)) }
                        current = null
                    }
                    is SpeechEvent.Error -> {
                        safe { listener.error(code(e.kind)) }
                        current = null
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        // Клиент просит закончить: отдаём то, что успели распознать.
        job?.cancel()
        if (current != null) {
            if (lastPartial.isNotBlank()) safe { listener.results(results(lastPartial)) } else safe { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
        }
        current = null
    }

    override fun onCancel(listener: Callback) {
        job?.cancel()
        current = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun code(kind: SpeechError) = when (kind) {
        SpeechError.NO_MATCH -> SpeechRecognizer.ERROR_NO_MATCH
        SpeechError.TIMEOUT -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        SpeechError.NO_PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        SpeechError.BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        SpeechError.AUDIO -> SpeechRecognizer.ERROR_AUDIO
        SpeechError.NETWORK -> SpeechRecognizer.ERROR_NETWORK
        else -> SpeechRecognizer.ERROR_CLIENT
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Exception) { /* клиент отключился */ }
    }
}
