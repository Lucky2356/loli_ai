package ai.loli.app.voice

import ai.loli.app.settings.AppSettings
import ai.loli.core.assistant.AssistantEngine
import ai.loli.core.assistant.AssistantReply
import ai.loli.core.assistant.InputSource
import ai.loli.core.voice.ListenOptions
import ai.loli.core.voice.SpeechError
import ai.loli.core.voice.SpeechEvent
import ai.loli.core.voice.SpeechRecognitionProvider
import ai.loli.core.voice.SpeechText
import ai.loli.core.voice.TextToSpeechProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String = "", val level: Float = 0f, val followUp: Boolean = false) : VoiceState
    data class Thinking(val heard: String) : VoiceState
    data class Speaking(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Голосовой конвейер: слушаем → распознаём → ассистент → отвечаем голосом.
 * Общий для экрана, жеста ассистента, плитки быстрых настроек и фонового wake word.
 * Поддерживает диалоговый режим: после ответа снова слушает без обращения по имени.
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

    /** Микрофон занят экранным распознаванием — фоновый wake word должен освободить его. */
    private val _uiListening = MutableStateFlow(false)
    val uiListening: StateFlow<Boolean> = _uiListening.asStateFlow()

    private var job: Job? = null

    fun activeProvider(): SpeechRecognitionProvider = when {
        settings.value.preferOfflineStt && offlineStt.isAvailable() -> offlineStt
        systemStt.isAvailable() -> systemStt
        offlineStt.isAvailable() -> offlineStt
        else -> systemStt
    }

    /** Начать голосовую команду (кнопка микрофона, жест ассистента, плитка). */
    fun startListening(source: InputSource = InputSource.VOICE) {
        job?.cancel()
        tts.stop()
        job = scope.launch {
            _uiListening.value = true
            try {
                conversationLoop(source)
            } finally {
                _uiListening.value = false
                if (_state.value !is VoiceState.Error) _state.value = VoiceState.Idle
            }
        }
    }

    fun stop() {
        job?.cancel()
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

    /** Реплика, уже распознанная фоновым сервисом wake word. */
    suspend fun handleRecognized(text: String, source: InputSource): AssistantReply = process(text, source, speak = true)

    fun clearError() {
        if (_state.value is VoiceState.Error) _state.value = VoiceState.Idle
    }

    private suspend fun conversationLoop(source: InputSource) {
        var followUps = 0
        while (true) {
            val heard = listenOnce(followUp = followUps > 0) ?: return
            val reply = process(heard, source, speak = true)
            val continueDialog = reply.expectFollowUp &&
                (settings.value.dialogModeEnabled || reply.awaitingAnswer || reply.awaitingConfirmation)
            if (!continueDialog || followUps >= MAX_FOLLOW_UPS) return
            followUps++
        }
    }

    private suspend fun listenOnce(followUp: Boolean): String? {
        var result: String? = null
        _state.value = VoiceState.Listening(followUp = followUp)
        val options = ListenOptions(preferOffline = settings.value.preferOfflineStt)
        activeProvider().listen(options).collect { e ->
            when (e) {
                is SpeechEvent.Partial -> _state.value = VoiceState.Listening(e.text, (_state.value as? VoiceState.Listening)?.level ?: 0f, followUp)
                is SpeechEvent.Level -> (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(level = e.value) }
                is SpeechEvent.Final -> result = e.text
                is SpeechEvent.Error -> {
                    val silent = followUp && (e.kind == SpeechError.NO_MATCH || e.kind == SpeechError.TIMEOUT)
                    _state.value = if (silent) VoiceState.Idle else VoiceState.Error(e.message)
                }
                else -> Unit
            }
        }
        return result?.takeIf { it.isNotBlank() }
    }

    private suspend fun process(text: String, source: InputSource, speak: Boolean): AssistantReply {
        _state.value = VoiceState.Thinking(text)
        val reply = engine.handle(text, source)
        _lastReply.value = reply
        if (speak && settings.value.ttsEnabled && reply.text.isNotBlank()) {
            _state.value = VoiceState.Speaking(reply.text)
            tts.speak(SpeechText.forSpeech(reply.text))
        }
        _state.value = VoiceState.Idle
        return reply
    }

    companion object {
        private const val MAX_FOLLOW_UPS = 20
    }
}
