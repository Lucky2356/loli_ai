package ai.loli.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.loli.core.util.Logger
import ai.loli.core.voice.TextToSpeechProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Озвучивание ответов системным синтезатором речи Android (русский голос). */
class AndroidTtsProvider(context: Context, private val rate: () -> Float) : TextToSpeechProvider {
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var readyOk = false
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        readyOk = ok
        ready.complete(ok)
        if (!ok) Logger.w(TAG, "TTS недоступен (status=$status)")
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
        })
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
    }

    override val isReady: Boolean get() = readyOk

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        val ok = withTimeoutOrNull(3000) { ready.await() } ?: false
        if (!ok) return
        val locale = Locale("ru", "RU")
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        tts.setSpeechRate(rate())
        val id = UUID.randomUUID().toString()
        // Верхняя граница, чтобы зависший движок не блокировал ассистента.
        withTimeoutOrNull(60_000L + text.length * 120L) {
            suspendCancellableCoroutine { cont ->
                pending[id] = { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation { pending.remove(id); tts.stop() }
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (result != TextToSpeech.SUCCESS) pending.remove(id)?.invoke(Unit)
            }
        }
    }

    override fun stop() { runCatching { tts.stop() } }

    override fun shutdown() { runCatching { tts.shutdown() } }

    companion object { private const val TAG = "TTS" }
}
