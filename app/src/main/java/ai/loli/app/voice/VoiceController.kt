package ai.loli.app.voice

import ai.loli.app.settings.AppSettings
import ai.loli.app.settings.SttMode
import ai.loli.core.assistant.AssistantEngine
import ai.loli.core.assistant.AssistantReply
import ai.loli.core.assistant.InputSource
import ai.loli.core.util.Logger
import ai.loli.core.voice.ListenOptions
import ai.loli.core.voice.SpeechError
import ai.loli.core.voice.SpeechEvent
import ai.loli.core.voice.SpeechRecognitionProvider
import ai.loli.core.voice.SpeechFixes
import ai.loli.core.voice.SpeechText
import ai.loli.core.voice.TextToSpeechProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String = "", val level: Float = 0f, val followUp: Boolean = false, val hint: String? = null) : VoiceState
    data class Thinking(val heard: String) : VoiceState
    data class Speaking(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Голосовой конвейер: слушаем → распознаём → ассистент → отвечаем голосом.
 * Общий для главного экрана, окна ассистента, плитки быстрых настроек и фонового wake word.
 *
 *  - Диалоговый режим: после ответа снова слушает без обращения по имени, пока пользователь не скажет
 *    «хватит» или не замолчит (две паузы подряд).
 *  - Надёжность: если системное распознавание не работает на этом телефоне, автоматически переходит
 *    на встроенное офлайн-распознавание Vosk, а в крайнем случае — на системное окно Google.
 */
class VoiceController(
    private val engine: AssistantEngine,
    private val settings: StateFlow<AppSettings>,
    private val systemStt: SpeechRecognitionProvider,
    private val offlineStt: SpeechRecognitionProvider,
    private val tts: TextToSpeechProvider,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _lastReply = MutableStateFlow<AssistantReply?>(null)
    val lastReply: StateFlow<AssistantReply?> = _lastReply.asStateFlow()

    /** Микрофон нужен экранному распознаванию — фоновый wake word должен освободить его. */
    private val _uiListening = MutableStateFlow(false)
    val uiListening: StateFlow<Boolean> = _uiListening.asStateFlow()

    /** Фоновый сервис wake word сейчас держит микрофон. */
    val wakeHoldsMic = MutableStateFlow(false)

    /** Просьба к открытому экрану показать системное окно распознавания (последний запасной вариант). */
    private val _systemDialogRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val systemDialogRequests: SharedFlow<Unit> = _systemDialogRequests.asSharedFlow()
    /** Есть ли на устройстве системное окно распознавания (задаётся приложением). */
    var systemDialogAvailable: () -> Boolean = { false }
    /** Вызывается после каждого голосового ответа (приложение показывает уведомление, если экран заблокирован или свёрнут). */
    var onVoiceReply: (AssistantReply) -> Unit = {}
    /** Слушает «стоп», пока Лоли говорит; true — пользователь её остановил (задаётся приложением). */
    var stopWatcher: (suspend () -> Boolean)? = null
    /** Понимает ли Лоли фразу — для выбора лучшего из вариантов распознавания. */
    var understands: (String) -> Boolean = { false }

    /** Системное распознавание не сработало на этом устройстве — в режиме «Авто» сразу используем Vosk. */
    @Volatile private var systemBroken = false

    private var job: Job? = null

    private sealed interface Heard {
        data class Text(val text: String) : Heard
        data object Silence : Heard
        /** Пользователь сказал «стоп». */
        data object Stop : Heard
        /** [serviceBroken] — сервис не работает на этом телефоне (а не просто не разобрал фразу). */
        data class Failed(val message: String, val tryNext: Boolean, val serviceBroken: Boolean = true) : Heard
    }

    fun providerChain(): List<SpeechRecognitionProvider> {
        val system = systemStt.takeIf { it.isAvailable() }
        val offline = offlineStt.takeIf { it.isAvailable() }
        return when (settings.value.sttMode) {
            SttMode.OFFLINE -> listOfNotNull(offline)
            SttMode.SYSTEM -> listOfNotNull(system)
            SttMode.AUTO -> if (systemBroken) listOfNotNull(offline, system) else listOfNotNull(system, offline)
        }
    }

    /** Начать голосовую команду (кнопка микрофона, жест ассистента, плитка). */
    fun startListening(source: InputSource = InputSource.VOICE) {
        job?.cancel()
        tts.stop()
        job = scope.launch {
            _uiListening.value = true
            try {
                // Дожидаемся, пока фоновый wake word отпустит микрофон, иначе система отдаст нам тишину.
                withTimeoutOrNull(2_000) { wakeHoldsMic.first { !it } }
                conversationLoop(source)
            } finally {
                // Разговор закончился (тишина, лимит, «хватит») — ассистент выходит из диалогового режима.
                scope.launch { engine.endDialog() }
                _uiListening.value = false
                if (_state.value !is VoiceState.Error) _state.value = VoiceState.Idle
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.launch { engine.endDialog() }
        tts.stop()
        _uiListening.value = false
        _state.value = VoiceState.Idle
    }

    /** Текстовая команда из поля ввода (ответ не озвучивается). */
    fun submitText(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        tts.stop()
        job = scope.launch { process(text, InputSource.TEXT, speak = false) }
    }

    /** Подтверждение/отмена кнопками в интерфейсе. */
    fun confirm(yes: Boolean) {
        job?.cancel()
        job = scope.launch { process(if (yes) "да" else "нет", InputSource.TEXT, speak = false) }
    }

    suspend fun endDialog() = engine.endDialog()

    /** Реплика, уже распознанная фоновым сервисом wake word. */
    suspend fun handleRecognized(text: String, source: InputSource): AssistantReply =
        process(SpeechFixes.apply(text, settings.value.assistantName), source, speak = true)

    /** Результат системного окна распознавания. */
    fun onSystemDialogResult(text: String?) {
        if (text.isNullOrBlank()) { _state.value = VoiceState.Idle; return }
        job?.cancel()
        job = scope.launch {
            val reply = process(SpeechFixes.apply(text, settings.value.assistantName), InputSource.VOICE, speak = true)
            if (reply.expectFollowUp) _systemDialogRequests.tryEmit(Unit) else engine.endDialog()
        }
    }

    fun clearError() {
        if (_state.value is VoiceState.Error) _state.value = VoiceState.Idle
    }

    private suspend fun conversationLoop(source: InputSource) {
        var followUps = 0
        var silentInRow = 0
        while (true) {
            val followUp = followUps > 0
            when (val heard = listenOnce(followUp)) {
                Heard.Stop -> return
                is Heard.Text -> {
                    silentInRow = 0
                    // Фраза оборвалась на «и», «на», «потом»… — дослушиваем продолжение и склеиваем.
                    var phrase = heard.text
                    var more = 0
                    while (SpeechFixes.looksUnfinished(phrase) && more++ < MAX_CONTINUATIONS) {
                        when (val next = listenOnce(followUp = true, hint = "Продолжайте…")) {
                            is Heard.Text -> phrase = "$phrase ${next.text}"
                            Heard.Stop -> return
                            else -> break
                        }
                    }
                    var reply = process(phrase, source, speak = true)
                    if (reply.dictation) reply = dictate(source)
                    if (!reply.expectFollowUp || reply.endsDialog || followUps >= MAX_FOLLOW_UPS) return
                    followUps++
                    delay(250) // синтезатор отпускает аудио, иначе распознаватель услышит хвост ответа
                }
                Heard.Silence -> {
                    // В разговоре пользователю нужно время подумать: одна пауза не завершает диалог.
                    if (!followUp) { _state.value = VoiceState.Error("Не расслышала. Нажмите на микрофон и говорите."); return }
                    if (++silentInRow >= SILENT_LIMIT) return
                }
                is Heard.Failed -> {
                    if (heard.tryNext && systemDialogAvailable() && settings.value.sttMode != SttMode.OFFLINE) {
                        _state.value = VoiceState.Idle
                        _systemDialogRequests.tryEmit(Unit)
                    } else {
                        _state.value = VoiceState.Error(heard.message)
                    }
                    return
                }
            }
        }
    }

    /**
     * Длинная диктовка: слушаем кусками с большими паузами и склеиваем, пока человек не скажет «готово»
     * (или «стоп», или не помолчит дважды подряд). Потом сохраняем заметку и называем её.
     */
    private suspend fun dictate(source: InputSource): AssistantReply {
        val parts = ArrayList<String>()
        var silent = 0
        while (parts.sumOf { it.length } < 20_000) {
            when (val heard = listenOnce(followUp = true, hint = if (parts.isEmpty()) "Диктуйте…" else "Записываю… Скажите «готово», когда закончите", silenceMillis = DICTATION_PAUSE_MS)) {
                is Heard.Text -> {
                    silent = 0
                    parts += heard.text
                    _state.value = VoiceState.Listening(parts.joinToString(" ").takeLast(160), 0f, true)
                    if (ai.loli.core.assistant.SpecialCommands.endsDictation(heard.text)) break
                }
                Heard.Stop -> break
                Heard.Silence -> if (++silent >= 2) break
                is Heard.Failed -> break
            }
        }
        _state.value = VoiceState.Thinking(parts.joinToString(" ").take(120))
        val reply = engine.saveDictation(parts.joinToString(" "))
        _lastReply.value = reply
        if (source != InputSource.TEXT) runCatching { onVoiceReply(reply) }
        if (settings.value.ttsEnabled) { _state.value = VoiceState.Speaking(reply.text); tts.speak(SpeechText.forSpeech(reply.text)) }
        _state.value = VoiceState.Idle
        return reply
    }

    private suspend fun listenOnce(followUp: Boolean, hint: String? = null, silenceMillis: Long? = null): Heard {
        val chain = providerChain()
        if (chain.isEmpty()) return Heard.Failed("На телефоне нет распознавания речи. Установите приложение Google или офлайн-модель в настройках.", false)
        var last: Heard.Failed? = null
        for ((i, provider) in chain.withIndex()) {
            val switchHint = if (i > 0) (if (provider === offlineStt) "Переключилась на офлайн-распознавание — повторите, пожалуйста" else "Повторите, пожалуйста") else hint
            val result = listenWith(provider, followUp, switchHint, silenceMillis)
            if (result !is Heard.Failed) return result
            last = result
            if (provider === systemStt && result.tryNext && result.serviceBroken) systemBroken = true
            if (!result.tryNext) break
        }
        return last ?: Heard.Failed("Распознавание речи недоступно.", false)
    }

    private suspend fun listenWith(provider: SpeechRecognitionProvider, followUp: Boolean, hint: String?, silenceOverride: Long? = null): Heard {
        var result: Heard = Heard.Silence
        var speechDetected = false
        _state.value = VoiceState.Listening(followUp = followUp, hint = hint)
        val s = settings.value
        val options = ListenOptions(
            preferOffline = s.sttMode == SttMode.OFFLINE,
            silenceMillis = silenceOverride ?: s.speechPause.millis,
            biasing = listOf(s.assistantName) + BIASING,
        )
        try {
            val name = s.assistantName
            // «Стоп» останавливает сразу, не дожидаясь конца фразы.
            provider.listen(options).takeWhile { e ->
                val stop = (e is SpeechEvent.Partial && StopWords.isStop(e.text, name)) || (e is SpeechEvent.Final && StopWords.isStop(e.text, name))
                if (stop) result = Heard.Stop
                !stop
            }.collect { e ->
                when (e) {
                    SpeechEvent.SpeechStarted -> speechDetected = true
                    is SpeechEvent.Partial -> _state.value = VoiceState.Listening(e.text, (_state.value as? VoiceState.Listening)?.level ?: 0f, followUp)
                    is SpeechEvent.Level -> (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(level = e.value) }
                    is SpeechEvent.Final -> result = if (e.text.isBlank()) Heard.Silence else Heard.Text(bestVariant(e.text, e.alternatives, name))
                    is SpeechEvent.Error -> result = when (e.kind) {
                        SpeechError.NO_MATCH, SpeechError.TIMEOUT -> Heard.Silence
                        SpeechError.NO_PERMISSION -> Heard.Failed(e.message, tryNext = false)
                        // Нет сети/сервис сломан/микрофон занят — пробуем следующий способ распознавания.
                        else -> Heard.Failed(e.message, tryNext = true)
                    }
                    else -> Unit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Распознаватель ${provider.id} упал", e)
            result = Heard.Failed("Ошибка распознавания: ${e.message ?: e::class.simpleName}", tryNext = true)
        }
        // Речь была, но системный сервис её не разобрал (частая проблема чужих сервисов на телефонах без Google):
        // в режиме «Авто» сразу пробуем офлайн-распознавание, не считая сервис сломанным.
        if (result == Heard.Silence && speechDetected && provider === systemStt && s.sttMode == SttMode.AUTO && offlineStt.isAvailable()) {
            return Heard.Failed("Не разобрала фразу.", tryNext = true, serviceBroken = false)
        }
        return result
    }

    /**
     * Из нескольких вариантов услышанного берём первый, который Лоли понимает как команду
     * («запиши расход» вместо «запиши рас ход»). Если не понятен ни один — самый вероятный (его поймёт AI).
     */
    private fun bestVariant(top: String, alternatives: List<String>, name: String): String {
        val variants = (listOf(top) + alternatives).map { SpeechFixes.apply(it, name) }.filter { it.isNotBlank() }.distinct()
        if (variants.size <= 1) return variants.firstOrNull() ?: top
        return variants.firstOrNull { runCatching { understands(it) }.getOrDefault(false) } ?: variants.first()
    }

    private suspend fun process(text: String, source: InputSource, speak: Boolean): AssistantReply {
        // Голосом «стоп» — молча останавливаемся и выходим из разговора.
        if (source != InputSource.TEXT && StopWords.isStop(text, settings.value.assistantName)) {
            tts.stop()
            engine.endDialog()
            _state.value = VoiceState.Idle
            return AssistantReply("", endsDialog = true)
        }
        _state.value = VoiceState.Thinking(text)
        val reply = engine.handle(text, source)
        _lastReply.value = reply
        if (source != InputSource.TEXT) runCatching { onVoiceReply(reply) }
        var interrupted = false
        if (speak && settings.value.ttsEnabled && reply.text.isNotBlank()) {
            _state.value = VoiceState.Speaking(reply.text)
            coroutineScope {
                // Пока Лоли говорит, её можно перебить словом «стоп» (если микрофон не занят фоновой службой — та ловит «стоп» сама).
                val watcher = stopWatcher?.takeIf { !wakeHoldsMic.value }?.let { w ->
                    launch { if (w()) { interrupted = true; tts.stop() } }
                }
                if (reply.speakLanguage != null) tts.speakIn(reply.text, reply.speakLanguage) else tts.speak(SpeechText.forSpeech(reply.text))
                watcher?.cancel()
            }
        }
        _state.value = VoiceState.Idle
        if (interrupted) {
            engine.endDialog()
            return reply.copy(expectFollowUp = false, endsDialog = true)
        }
        return reply
    }

    companion object {
        private const val TAG = "Voice"
        private const val MAX_FOLLOW_UPS = 30
        /** В диктовке человек думает дольше: пауза до конца куска — 5 секунд. */
        private const val DICTATION_PAUSE_MS = 5000L
        /** Сколько пауз подряд завершают разговор. */
        private const val SILENT_LIMIT = 2
        /** Сколько раз дослушивать оборванную фразу. */
        private const val MAX_CONTINUATIONS = 2
        /** Частые слова команд — подсказка распознавателю. */
        private val BIASING = listOf(
            "напомни", "запиши", "заметку", "идею", "задачу", "расход", "потратила", "потратил", "рублей", "будильник", "таймер",
            "стоп", "хватит", "позвони", "напиши", "открой", "запомни", "удали", "покажи", "сколько",
        )
    }
}
