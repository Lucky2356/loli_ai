package ai.loli.desktop.voice

import ai.loli.core.assistant.AssistantReply
import ai.loli.core.assistant.InputSource
import ai.loli.core.voice.SpeechText
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Голос на компьютере: кнопка микрофона (или Ctrl+Пробел) → фраза → Лоли → ответ вслух.
 * Если Лоли задала вопрос, в диалоговом режиме сразу слушает ответ (до трёх раз подряд).
 */
class VoiceController(private val c: DesktopContainer) {
    sealed interface State {
        data object Idle : State
        data class Preparing(val percent: Int) : State
        data class Listening(val partial: String, val level: Float) : State
        data class Thinking(val heard: String) : State
        data class Speaking(val text: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    val input = SpeechInput(c.dataDir)
    val output = SpeechOutput()

    /** Нажатие на микрофон: начать слушать или остановить (речь, распознавание). */
    fun toggle() {
        when (_state.value) {
            is State.Listening -> input.stop()
            is State.Speaking -> output.stop()
            is State.Thinking, is State.Preparing -> Unit
            else -> start()
        }
    }

    fun stopAll() {
        input.stop(); output.stop(); job?.cancel(); _state.value = State.Idle
    }

    private fun start() {
        if (job?.isActive == true) return
        job = c.scope.launch(Dispatchers.IO) {
            if (input.modelDir() == null) {
                _state.value = State.Preparing(0)
                if (!input.ensureModel { _state.value = State.Preparing(it) }) {
                    _state.value = State.Error("Не удалось скачать модель речи. Проверьте интернет и попробуйте ещё раз.")
                    return@launch
                }
            }
            if (!input.hasMicrophone()) {
                _state.value = State.Error("Микрофон не найден. Подключите его и разрешите доступ: Параметры Windows → Конфиденциальность → Микрофон.")
                return@launch
            }
            var rounds = 0
            while (rounds < 3) {
                rounds++
                _state.value = State.Listening("", 0f)
                val heard = try {
                    input.listen { partial, level -> _state.value = State.Listening(partial, level) }
                } catch (e: Exception) {
                    _state.value = State.Error("Микрофон занят или недоступен: ${e.message ?: "ошибка"}.")
                    return@launch
                }
                if (heard.isBlank()) { _state.value = State.Idle; return@launch }
                _state.value = State.Thinking(heard)
                val reply = c.send(heard, InputSource.VOICE) ?: run { _state.value = State.Idle; return@launch }
                speak(reply)
                val again = c.settings.value.dialogMode && (reply.awaitingAnswer || reply.awaitingConfirmation) && !reply.endsDialog
                if (!again) break
            }
            _state.value = State.Idle
        }
    }

    /** Ответ вслух (если включено). Вызывается и для ответов на набранный текст, когда это настроено. */
    suspend fun speak(reply: AssistantReply) {
        val s = c.settings.value
        if (!s.voiceReplies || !output.available || reply.text.isBlank()) return
        _state.value = State.Speaking(reply.text)
        withContext(Dispatchers.IO) { output.speak(SpeechText.forSpeech(reply.text), s.voiceName, s.speechRate) }
        if (_state.value is State.Speaking) _state.value = State.Idle
    }
}
