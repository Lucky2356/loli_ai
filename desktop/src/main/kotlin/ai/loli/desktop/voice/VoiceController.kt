package ai.loli.desktop.voice

import ai.loli.core.assistant.AssistantReply
import ai.loli.core.assistant.InputSource
import ai.loli.core.util.Logger
import ai.loli.core.voice.SpeechText
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Голос на компьютере: кнопка микрофона (или Ctrl+Пробел) → фраза → Лоли → ответ вслух.
 * Если Лоли задала вопрос, в диалоговом режиме сразу слушает ответ (до трёх раз подряд).
 * Повторное нажатие на любой стадии — остановить: скачивание модели, слушание или речь.
 */
class VoiceController(private val c: DesktopContainer) {
    sealed interface State {
        data object Idle : State
        /** [percent] null — модель загружается в память (без процентов). */
        data class Preparing(val percent: Int?) : State
        data class Listening(val partial: String, val level: Float) : State
        data class Thinking(val heard: String) : State
        data class Speaking(val text: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    private var errorJob: Job? = null
    /** Пользователь прервал — диалог не продолжаем. */
    @Volatile private var interrupted = false

    val input = SpeechInput(c.dataDir)
    val output = SpeechOutput()

    /** Нажатие на микрофон: начать слушать или остановить то, что идёт сейчас. */
    fun toggle() {
        when (_state.value) {
            is State.Listening -> input.stop()
            is State.Speaking -> { interrupted = true; output.stop() }
            is State.Preparing -> stopAll()
            is State.Thinking -> Unit
            else -> start()
        }
    }

    fun stopAll() {
        interrupted = true
        input.stop(); output.stop(); job?.cancel()
        _state.value = State.Idle
    }

    /** Выход из программы. */
    fun shutdown() {
        interrupted = true
        input.stop(); job?.cancel()
        output.shutdown(); input.close()
    }

    /** Подготовить заранее то, что уже есть на диске: синтезатор и (если модель на месте) распознавание. */
    fun warmUp() {
        if (c.settings.value.voiceReplies) output.warmUp()
    }

    private fun fail(message: String) {
        _state.value = State.Error(message)
        errorJob?.cancel()
        // Ошибка видна 8 секунд, потом строка состояния сама исчезает.
        errorJob = c.scope.launch { delay(8_000); if (_state.value is State.Error) _state.value = State.Idle }
    }

    private fun start() {
        if (job?.isActive == true) return
        interrupted = false
        errorJob?.cancel()
        job = c.scope.launch(Dispatchers.IO) {
            try {
                if (input.modelDir() == null) {
                    _state.value = State.Preparing(0)
                    if (!input.ensureModel { _state.value = State.Preparing(it) }) {
                        if (interrupted) { _state.value = State.Idle; return@launch }
                        fail("Не удалось скачать модель речи. Проверьте интернет и попробуйте ещё раз.")
                        return@launch
                    }
                }
                if (!input.hasMicrophone()) {
                    fail("Микрофон не найден. Подключите его и разрешите доступ: Параметры Windows → Конфиденциальность → Микрофон.")
                    return@launch
                }
                if (!input.loaded) {
                    _state.value = State.Preparing(null)
                    if (!input.prepare()) { fail("Модель речи повреждена. Переустановите Лоли."); return@launch }
                    if (interrupted) { _state.value = State.Idle; return@launch }
                }
                var rounds = 0
                while (rounds < 3 && !interrupted) {
                    rounds++
                    _state.value = State.Listening("", 0f)
                    val heard = try {
                        input.listen { partial, level -> if (_state.value is State.Listening) _state.value = State.Listening(partial, level) }
                    } catch (e: Exception) {
                        Logger.w("Voice", "Микрофон недоступен", e)
                        fail("Микрофон занят другой программой или недоступен.")
                        return@launch
                    }
                    if (heard.isBlank() || interrupted) break
                    _state.value = State.Thinking(heard)
                    val reply = c.send(heard, InputSource.VOICE) ?: break
                    speak(reply)
                    val again = c.settings.value.dialogMode && (reply.awaitingAnswer || reply.awaitingConfirmation) && !reply.endsDialog
                    if (!again) break
                }
                if (_state.value !is State.Error) _state.value = State.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("Voice", "Голосовой режим упал", e)
                fail("Голос не сработал: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Ответ вслух (если включено). */
    suspend fun speak(reply: AssistantReply) {
        val s = c.settings.value
        if (!s.voiceReplies || !output.available || reply.text.isBlank() || interrupted) return
        _state.value = State.Speaking(reply.text)
        withContext(Dispatchers.IO) { output.speak(SpeechText.forSpeech(reply.text), s.voiceName, s.speechRate) }
        if (_state.value is State.Speaking) _state.value = State.Idle
    }
}
