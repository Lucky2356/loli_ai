package ai.loli.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.loli.core.util.Logger
import ai.loli.core.voice.TextToSpeechProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Озвучивание ответов системным синтезатором речи Android (русский голос).
 *
 * На многих телефонах (Xiaomi, Huawei, Honor, Oppo, Vivo) синтезатор по умолчанию не знает русского
 * и читает кириллицу «по-китайски» или молчит. Поэтому, если пользователь не выбрал синтезатор сам,
 * Лоли проверяет все установленные и берёт тот, что говорит по-русски (сначала Google).
 * Если русского нет нигде — не озвучивает чужим языком, а сообщает, что установить.
 */
class AndroidTtsProvider(
    context: Context,
    private val rate: () -> Float,
    private val pitch: () -> Float = { 1f },
    private val voiceName: () -> String = { "" },
    /** Синтезатор, выбранный пользователем вручную; пусто — выбирает Лоли. */
    private val engineName: () -> String = { "" },
    /** Найденный автоматически синтезатор с русским (запоминается между запусками). */
    private val autoEngine: () -> String = { "" },
    private val saveAutoEngine: suspend (String) -> Unit = {},
) : TextToSpeechProvider {
    private val appContext = context.applicationContext
    @Volatile private var ready = CompletableDeferred<Boolean>()
    @Volatile private var readyOk = false
    private val pending = ConcurrentHashMap<String, (Unit) -> Unit>()
    private val switchLock = Mutex()
    @Volatile private var probed = false
    @Volatile private var currentEngine: String = engineName().ifBlank { autoEngine() }
    @Volatile private var tts: TextToSpeech = create(currentEngine)

    /** Есть ли русский голос в текущем синтезаторе. */
    enum class RuStatus { UNKNOWN, OK, MISSING_DATA, NO_RUSSIAN }

    private val _russian = MutableStateFlow(RuStatus.UNKNOWN)
    val russianStatus: StateFlow<RuStatus> = _russian.asStateFlow()
    private val _engineLabel = MutableStateFlow("")
    /** Название синтезатора, которым сейчас говорит Лоли. */
    val engineLabel: StateFlow<String> = _engineLabel.asStateFlow()

    private fun create(engine: String, onReady: CompletableDeferred<Boolean>? = null): TextToSpeech {
        val done = onReady ?: CompletableDeferred<Boolean>().also { ready = it; readyOk = false }
        val listener = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (onReady == null) readyOk = ok
            done.complete(ok)
            if (!ok) Logger.w(TAG, "TTS недоступен (status=$status, engine=$engine)")
        }
        val t = if (engine.isBlank()) TextToSpeech(appContext, listener) else TextToSpeech(appContext, listener, engine)
        if (onReady != null) return t
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

    private fun ruSupport(t: TextToSpeech): RuStatus = when (runCatching { t.isLanguageAvailable(RU) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)) {
        TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> RuStatus.OK
        TextToSpeech.LANG_MISSING_DATA -> RuStatus.MISSING_DATA
        else -> RuStatus.NO_RUSSIAN
    }

    private fun switchTo(engine: String) {
        runCatching { tts.shutdown() }
        currentEngine = engine
        tts = create(engine)
    }

    /**
     * Готовит синтезатор: ручной выбор пользователя → он; иначе запомненный автоматически → он;
     * если он не говорит по-русски — перебор установленных синтезаторов (один раз за запуск).
     */
    private suspend fun ensureEngine(): Boolean = switchLock.withLock {
        val manual = engineName()
        val wanted = manual.ifBlank { autoEngine() }
        if (wanted != currentEngine) switchTo(wanted)
        if (withTimeoutOrNull(5000) { ready.await() } != true) return@withLock false
        var status = ruSupport(tts)
        if (status != RuStatus.OK && manual.isBlank() && !probed) {
            probed = true
            findRussianEngine()?.let { found ->
                if (found != currentEngine) {
                    switchTo(found)
                    if (withTimeoutOrNull(5000) { ready.await() } != true) return@withLock false
                }
                runCatching { saveAutoEngine(found) }
                status = ruSupport(tts)
            }
        }
        _russian.value = status
        _engineLabel.value = runCatching {
            val name = currentEngine.ifBlank { tts.defaultEngine }
            tts.engines.firstOrNull { it.name == name }?.label ?: name
        }.getOrDefault("")
        true
    }

    /** Перебирает установленные синтезаторы и возвращает тот, что знает русский (Google — первым). */
    private suspend fun findRussianEngine(): String? {
        val engines = runCatching { tts.engines.map { it.name } }.getOrDefault(emptyList())
            .sortedBy { if (it == GOOGLE_TTS) 0 else 1 }
        var missingData: String? = null
        for (name in engines) {
            val done = CompletableDeferred<Boolean>()
            val probe = runCatching { create(name, done) }.getOrNull() ?: continue
            val ok = withTimeoutOrNull(5000) { done.await() } == true
            val st = if (ok) ruSupport(probe) else RuStatus.NO_RUSSIAN
            runCatching { probe.shutdown() }
            Logger.i(TAG, "Синтезатор $name: русский — $st")
            if (st == RuStatus.OK) return name
            if (st == RuStatus.MISSING_DATA && missingData == null) missingData = name
        }
        // Русский есть, но голос не скачан — берём этот синтезатор, чтобы кнопка «Скачать» вела к нему.
        return missingData
    }

    /** Повторная проверка (после установки синтезатора или голоса). */
    suspend fun recheck() {
        // Уже говорит по-русски — перебирать синтезаторы незачем.
        if (_russian.value != RuStatus.OK) probed = false
        ensureEngine()
    }

    /** Выставляет русский и выбранный голос. false — русского нет, говорить нельзя (выйдет чужой язык). */
    private fun applyRussian(voice: String): Boolean {
        val st = ruSupport(tts)
        _russian.value = st
        if (st != RuStatus.OK) return false
        runCatching { tts.language = RU }
        voice.takeIf { it.isNotBlank() }?.let { name ->
            runCatching { tts.voices?.firstOrNull { it.name == name && it.locale.language == "ru" } }.getOrNull()
                ?.let { v -> runCatching { tts.setVoice(v) } }
        }
        return true
    }

    /** Установленные синтезаторы речи. */
    data class EngineOption(val name: String, val label: String)

    suspend fun engines(): List<EngineOption> {
        ensureEngine()
        return runCatching { tts.engines.orEmpty().map { EngineOption(it.name, it.label) } }.getOrDefault(emptyList())
    }

    fun defaultEngine(): String = runCatching { tts.defaultEngine }.getOrNull().orEmpty()

    /** Синтезатор, которым Лоли говорит сейчас (с учётом автовыбора). */
    fun activeEngine(): String = currentEngine.ifBlank { defaultEngine() }

    /** Проверить русский (для экрана настроек и шага «Русский голос»). */
    suspend fun checkRussian(): RuStatus { ensureEngine(); return _russian.value }

    override val isReady: Boolean get() = readyOk

    override suspend fun speak(text: String) = speakIn(text, null)

    /** Перевод озвучивается голосом нужного языка, если он есть; всё остальное — только русским. */
    override suspend fun speakIn(text: String, language: String?) {
        if (text.isBlank()) return
        if (!ensureEngine()) return
        val foreign = language?.takeIf { it != "ru" }?.let { Locale.forLanguageTag(it) }
            ?.takeIf { runCatching { tts.isLanguageAvailable(it) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE }
        if (foreign != null) {
            tts.language = foreign
        } else if (!applyRussian(voiceName())) {
            Logger.w(TAG, "Русского голоса нет — ответ только текстом")
            return
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
        if (foreign != null) runCatching { tts.language = RU }
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
                    "female" in n -> " · женский"
                    "male" in n -> " · мужской"
                    else -> ""
                }
            }
            VoiceOption(v.name, "Голос ${i + 1}", "$where · $quality$gender")
        }
    }

    /** Прослушать голос без сохранения настроек. Всегда по-русски; без русского голоса молчит, а экран показывает, что установить. */
    suspend fun preview(text: String, name: String, pitchOverride: Float? = null, rateOverride: Float? = null) {
        if (!ensureEngine()) return
        if (!applyRussian(name.ifBlank { voiceName() })) return
        tts.setPitch(pitchOverride ?: pitch())
        tts.setSpeechRate(rateOverride ?: rate())
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
    }

    override fun shutdown() { runCatching { tts.shutdown() } }

    companion object {
        private const val TAG = "TTS"
        const val GOOGLE_TTS = "com.google.android.tts"
        private val RU = Locale("ru", "RU")
    }
}
