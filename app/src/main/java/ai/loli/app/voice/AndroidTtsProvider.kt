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
class AndroidTtsProvider(
    context: Context,
    private val rate: () -> Float,
    private val pitch: () -> Float = { 1f },
    private val voiceName: () -> String = { "" },
    private val engineName: () -> String = { "" },
) : TextToSpeechProvider {
    private val appContext = context.applicationContext
    @Volatile private var ready = CompletableDeferred<Boolean>()
    @Volatile private var readyOk = false
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()
    @Volatile private var currentEngine: String = engineName()
    @Volatile private var tts: TextToSpeech = create(currentEngine)

    /** Синтезатор (движок) можно сменить: Google, Samsung, RHVoice… Пустое имя — системный по умолчанию. */
    private fun create(engine: String): TextToSpeech {
        val done = CompletableDeferred<Boolean>()
        ready = done
        readyOk = false
        val listener = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            readyOk = ok
            done.complete(ok)
            if (!ok) Logger.w(TAG, "TTS недоступен (status=$status, engine=$engine)")
        }
        val t = if (engine.isBlank()) TextToSpeech(appContext, listener) else TextToSpeech(appContext, listener, engine)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.invoke(Unit) } }
        })
        t.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        return t
    }

    /** Пересоздаёт синтезатор, если в настройках выбран другой движок. */
    private suspend fun ensureEngine(): Boolean {
        val wanted = engineName()
        if (wanted != currentEngine) {
            runCatching { tts.shutdown() }
            currentEngine = wanted
            tts = create(wanted)
        }
        return withTimeoutOrNull(4000) { ready.await() } ?: false
    }

    /** Установленные синтезаторы речи. */
    data class EngineOption(val name: String, val label: String)

    suspend fun engines(): List<EngineOption> {
        ensureEngine()
        return runCatching { tts.engines.orEmpty().map { EngineOption(it.name, it.label) } }.getOrDefault(emptyList())
    }

    fun defaultEngine(): String = runCatching { tts.defaultEngine }.getOrNull().orEmpty()

    override val isReady: Boolean get() = readyOk

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!ensureEngine()) return
        val locale = Locale("ru", "RU")
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        // Выбранный пользователем голос (если он всё ещё установлен).
        voiceName().takeIf { it.isNotBlank() }?.let { name ->
            runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()?.let { v -> runCatching { tts.setVoice(v) } }
        }
        tts.setSpeechRate(rate())
        tts.setPitch(pitch())
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

    /** Голос для списка в настройках. */
    data class VoiceOption(val name: String, val title: String, val subtitle: String)

    /**
     * Русские голоса текущего синтезатора: сначала установленные, потом те, что скачаются при выборе.
     * Список голосов у некоторых движков (Samsung, Huawei) появляется не сразу после запуска — ждём и спрашиваем повторно.
     */
    suspend fun russianVoices(): List<VoiceOption> {
        if (!ensureEngine()) return emptyList()
        var all = emptyList<android.speech.tts.Voice>()
        for (attempt in 0 until 4) {
            all = runCatching { tts.voices.orEmpty().toList() }.getOrDefault(emptyList()).filter { it.locale.language == "ru" }
            if (all.size > 1) break
            kotlinx.coroutines.delay(700)
        }
        fun notInstalled(v: android.speech.tts.Voice) = v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        val voices = all.sortedWith(compareBy({ notInstalled(it) }, { it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
        return voices.mapIndexed { i, v ->
            val where = when {
                notInstalled(v) -> "скачается при выборе"
                v.isNetworkConnectionRequired -> "нужен интернет"
                else -> "работает без интернета"
            }
            val quality = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "очень высокое качество"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "высокое качество"
                else -> "обычное качество"
            }
            val gender = v.name.lowercase().let { n ->
                when {
                    "female" in n || "#female" in n -> " · женский"
                    "male" in n -> " · мужской"
                    else -> ""
                }
            }
            VoiceOption(v.name, "Голос ${i + 1}", "$where · $quality$gender")
        }
    }

    /** Прослушать голос без сохранения настроек. */
    suspend fun preview(text: String, name: String, pitchOverride: Float? = null, rateOverride: Float? = null) {
        if (!ensureEngine()) return
        if (name.isNotBlank()) runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()?.let { runCatching { tts.setVoice(it) } }
        tts.setPitch(pitchOverride ?: pitch())
        tts.setSpeechRate(rateOverride ?: rate())
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
    }

    override fun shutdown() { runCatching { tts.shutdown() } }

    companion object { private const val TAG = "TTS" }
}
