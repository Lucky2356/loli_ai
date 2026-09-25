package ai.loli.core.assistant

import ai.loli.core.ai.AIException
import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.ChatMessage
import ai.loli.core.domain.ConversationRepository
import ai.loli.core.domain.MemoryRepository
import ai.loli.core.domain.NoteRepository
import ai.loli.core.domain.ReminderRepository
import ai.loli.core.domain.TaskRepository
import ai.loli.core.model.MessageRole
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.nlp.WakeWordMatcher
import ai.loli.core.search.SearchService
import ai.loli.core.util.Logger
import ai.loli.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Откуда пришла реплика — для статистики и поведения диалога. */
enum class InputSource { TEXT, VOICE, WAKE_WORD }

data class AssistantSettings(
    val assistantName: String = "Лоли",
    /** Облачный AI необязателен: по умолчанию всё понимается локально, без интернета. */
    val useAI: Boolean = false,
)

/** Ответ ассистента для UI и голоса. */
data class AssistantReply(
    val text: String,
    val outcomes: List<Outcome> = emptyList(),
    /** Ждём «да/нет». */
    val awaitingConfirmation: Boolean = false,
    /** Ждём выбора из вариантов или ответа на уточняющий вопрос. */
    val awaitingAnswer: Boolean = false,
    /** Продолжать слушать без повторного обращения по имени. */
    val expectFollowUp: Boolean = false,
    val usedAI: Boolean = false,
    /** AI был недоступен, команда обработана офлайн. */
    val offline: Boolean = false,
    val changedData: Boolean = false,
)

/**
 * Главный оркестратор: реплика пользователя → намерение → проверенные действия → выполнение → ответ.
 *
 *  - Облачный AI (если настроен и доступен) определяет намерение и возвращает структурированные действия.
 *  - Без AI/интернета работает офлайн-парсер типовых команд — локальные операции не зависят от AI.
 *  - Разрушительные действия требуют подтверждения; неоднозначные ссылки — уточнения.
 */
class AssistantEngine(
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val memories: MemoryRepository,
    private val conversations: ConversationRepository,
    private val search: SearchService,
    private val executor: ActionExecutor,
    private val time: TimeSource,
    private val settings: () -> AssistantSettings,
    private val aiProvider: suspend () -> AIProvider?,
    private val localParser: LocalCommandParser = LocalCommandParser(),
) {
    val context = ConversationContext(time)
    private val mutex = Mutex()

    suspend fun handle(input: String, source: InputSource = InputSource.TEXT): AssistantReply = mutex.withLock {
        val cfg = settings()
        val text = stripWakeWord(input, cfg.assistantName)
        if (text.isBlank()) return@withLock AssistantReply("Слушаю!", expectFollowUp = true)
        context.touch()

        val reply = try {
            process(text, cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Ошибка обработки команды", e)
            AssistantReply("Что-то пошло не так: ${e.message ?: "неизвестная ошибка"}. Попробуйте ещё раз.")
        }
        record(text, reply.text)
        if (source != InputSource.TEXT && !reply.expectFollowUp && !reply.awaitingAnswer && !reply.awaitingConfirmation) {
            context.dialogMode = false
        }
        reply
    }

    /** Подтверждение/отмена кнопкой в UI. */
    suspend fun respondToConfirmation(confirm: Boolean): AssistantReply = handle(if (confirm) "да" else "нет")

    private suspend fun process(text: String, cfg: AssistantSettings): AssistantReply {
        // 1. Ожидаем подтверждение опасного действия.
        context.pendingConfirmation?.let { pending ->
            when {
                LocalCommandParser.isYes(text) -> {
                    context.pendingConfirmation = null
                    val result = executor.applyConfirmed(pending, context)
                    return AssistantReply(result.outcomes.joinToString(" ") { it.text }, result.outcomes, changedData = true)
                }
                LocalCommandParser.isNo(text) -> {
                    context.pendingConfirmation = null
                    context.pendingChoice = null
                    return AssistantReply("Хорошо, ничего не удаляю.")
                }
                context.pendingChoice != null -> Unit // сначала ответ на «какую запись?», подтверждение ждёт
                else -> context.pendingConfirmation = null // новая команда отменяет ожидание
            }
        }
        // 2. Ожидаем выбор варианта.
        context.pendingChoice?.let { choice ->
            context.pendingChoice = null
            if (LocalCommandParser.isNo(text)) { context.pendingConfirmation = null; return AssistantReply("Хорошо, отменила.") }
            // Ответ на «какую?» — короткая фраза без новой команды: «вторую», «про склад».
            val wordCount = text.trim().split(Regex("\\s+")).size
            val looksLikeCommand = localParser.startsWithCommand(text)
            val index = if (looksLikeCommand) null else {
                (if (wordCount <= 3) LocalCommandParser.ordinal(text, choice.options.size) else null)
                    ?: (if (wordCount <= 6) matchOptionByTitle(text, choice.options) else null)
            }
            if (index != null) {
                val chosen = choice.options[index]
                context.touchRecord(chosen)
                val kept = context.pendingConfirmation
                val actions = listOf(withTarget(choice.action, chosen.id)) + choice.remaining
                return execute(AssistantPlan("", actions), usedAI = false, offline = false, carriedConfirmation = kept, name = cfg.assistantName)
            }
            context.pendingConfirmation = null
        }
        // 3. Дозаполнение недостающих данных («Сколько потратили?» → «500»).
        context.pendingSlot?.let { slot ->
            context.pendingSlot = null
            if (LocalCommandParser.isNo(text) && slot !is SlotRequest.SaveAsNote) return AssistantReply("Хорошо, отменила.")
            localParser.fillSlot(slot, text, time.now(), time.zone())?.let { filled ->
                return execute(filled, usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // Уточнение к вопросу о расходах: «а на транспорт?»
        context.lastExpenseQuery?.let { prevQuery ->
            localParser.followUpExpenseQuery(text, prevQuery, time.now(), time.zone())?.let { q ->
                return execute(AssistantPlan("", listOf(q)), usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // 4. Завершение диалогового режима.
        if (context.dialogMode && LocalCommandParser.isDialogEnd(text)) {
            context.dialogMode = false
            return AssistantReply("Хорошо! Если что — зовите.")
        }
        // 4. Облачный AI → при недоступности офлайн-парсер.
        var aiError: AIException? = null
        if (cfg.useAI) {
            val provider = try { aiProvider() } catch (e: AIException) { aiError = e; null }
            if (provider != null) {
                try {
                    val plan = planWithAI(provider, text, cfg)
                    return execute(plan, usedAI = true, offline = false, name = cfg.assistantName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AIException) {
                    Logger.w(TAG, "AI недоступен: ${e::class.simpleName}")
                    aiError = e
                }
            }
        }
        val local = localParser.parse(text, time.now(), time.zone()) ?: localFallback(text, cfg)
        if (local != null) {
            val reply = execute(local, usedAI = false, offline = cfg.useAI, name = cfg.assistantName)
            val note = when (aiError) {
                is AIException.Unauthorized -> " (AI: ключ не принят — выполнено офлайн)"
                null -> ""
                else -> ""
            }
            return reply.copy(text = reply.text + note)
        }
        val reason = when {
            !cfg.useAI -> "Не совсем поняла."
            aiError is AIException.NotConfigured || aiError == null -> "Не поняла, а облачный AI не настроен."
            else -> "Не поняла, а облачный AI недоступен: ${aiError?.message}"
        }
        return AssistantReply(
            "$reason Попробуйте сказать иначе, например: «потратила 500 рублей на продукты», «добавь задачу…», " +
                "«напомни завтра в 10 утра…», «что у меня на сегодня». Скажите «что ты умеешь» — расскажу подробнее.",
            offline = cfg.useAI,
        )
    }

    private suspend fun planWithAI(provider: AIProvider, text: String, cfg: AssistantSettings): AssistantPlan {
        val candidates = gatherCandidates(text)
        val handles = candidates.associate { it.handle to it.id }
        val memoryItems = memories.all().take(25)
        val system = PromptBuilder.build(
            cfg.assistantName, time.now(), time.zone(), memoryItems, candidates, context.focus, context.topic, context.dialogMode,
        )
        val request = AIRequest(system = system, messages = context.messages + ChatMessage(ChatMessage.Role.USER, text))
        val response = provider.complete(request)
        val plan = ActionParser(time.now(), time.zone(), handles).parse(response.text)
        plan.topic?.let { context.topic = it }
        if (plan.rejected.isNotEmpty()) Logger.w(TAG, "Отклонено действий: ${plan.rejected.size}")
        return plan
    }

    /** Записи, на которые AI может сослаться: найденные по смыслу + фокус + недавние + активные задачи/напоминания. */
    private suspend fun gatherCandidates(text: String): List<Candidate> {
        val seen = LinkedHashMap<String, Candidate>()
        fun add(type: RecordType, id: String, title: String, snippet: String, extra: String = "") {
            if (seen.containsKey(id) || seen.size >= MAX_CANDIDATES) return
            seen[id] = Candidate("#${seen.size + 1}", type, id, title, snippet, extra)
        }
        context.focus?.let { f -> executorLookup(f)?.let { add(it.type, it.id, it.title, it.snippet) } }
        val query = listOfNotNull(text, context.topic).joinToString(" ")
        runCatching { search.search(query, limit = 8, minScore = 0.25) }.getOrDefault(emptyList())
            .forEach { add(it.doc.type, it.doc.id, it.doc.title, it.doc.body) }
        context.recent.forEach { r -> executorLookup(r)?.let { add(it.type, it.id, it.title, it.snippet) } }
        notes.all().take(5).forEach { add(if (it.kind == NoteKind.IDEA) RecordType.IDEA else RecordType.NOTE, it.id, it.title, it.content) }
        tasks.all().filter { !it.done }.take(8).forEach { add(RecordType.TASK, it.id, it.title, it.details, it.dueDate?.let { d -> "срок $d" } ?: "") }
        reminders.active().take(6).forEach { add(RecordType.REMINDER, it.id, it.text, "", "сработает ${it.triggerAt.atZone(time.zone()).toLocalDateTime()}") }
        return seen.values.toList()
    }

    private suspend fun executorLookup(ref: RecordRef): Candidate? = when (ref.type) {
        RecordType.NOTE, RecordType.IDEA -> notes.get(ref.id)?.let { Candidate("", ref.type, it.id, it.title, it.content) }
        RecordType.TASK -> tasks.get(ref.id)?.let { Candidate("", ref.type, it.id, it.title, it.details) }
        RecordType.REMINDER -> reminders.get(ref.id)?.let { Candidate("", ref.type, it.id, it.text, "") }
        RecordType.MEMORY -> memories.get(ref.id)?.let { Candidate("", ref.type, it.id, it.content, "") }
        RecordType.EXPENSE -> null
    }

    private suspend fun execute(
        plan: AssistantPlan,
        usedAI: Boolean,
        offline: Boolean,
        carriedConfirmation: PendingConfirmation? = null,
        name: String = "Лоли",
    ): AssistantReply {
        val executed = executor.execute(plan.actions, context)
        // Подтверждение, заданное до уточнения, не теряется — объединяем с новыми.
        val result = if (carriedConfirmation == null) executed else executed.copy(
            pendingConfirmation = executed.pendingConfirmation?.let {
                PendingConfirmation(carriedConfirmation.question + " " + it.question, carriedConfirmation.operations + it.operations)
            } ?: carriedConfirmation,
        )
        context.pendingConfirmation = result.pendingConfirmation
        context.pendingChoice = result.pendingChoice
        val changed = result.outcomes.any { it.kind == Outcome.Kind.CHANGED }
        val onlyMutations = result.outcomes.all { it.kind == Outcome.Kind.CHANGED }
        val parts = ArrayList<String>()
        when {
            plan.actions.isEmpty() -> parts += plan.reply.ifBlank { "Не совсем поняла. Повторите, пожалуйста, иначе." }
            // Для изменений AI-ответ звучит естественнее, но только если всё действительно выполнено.
            onlyMutations && plan.reply.isNotBlank() && !result.hasErrors && result.outcomes.isNotEmpty() -> parts += plan.reply
            else -> parts += result.outcomes.map { it.text }
        }
        result.pendingConfirmation?.let { parts += it.question }
        if (plan.rejected.isNotEmpty()) parts += "Часть команды не выполнила: ${plan.rejected.joinToString("; ")}."
        if (plan.preface.isNotBlank()) parts.add(0, plan.preface)
        // Недостающие данные спрашиваем после выполненного (если уточнений по записям не требуется).
        plan.slot?.takeIf { result.pendingChoice == null }?.let { slot ->
            context.pendingSlot = slot
            if (plan.actions.isEmpty()) parts.clear()
            parts += slot.question
        }
        val awaitingAnswer = result.pendingChoice != null || plan.slot != null || result.outcomes.any { it.kind == Outcome.Kind.QUESTION }
        if (plan.expectFollowUp) context.dialogMode = true
        // В диалоге продолжаем слушать, пока пользователь не скажет «хватит» (или не замолчит).
        val followUp = plan.expectFollowUp || context.dialogMode || awaitingAnswer || result.pendingConfirmation != null
        return AssistantReply(
            text = parts.filter { it.isNotBlank() }.joinToString("\n").trim().replace("{name}", name),
            outcomes = result.outcomes,
            awaitingConfirmation = result.pendingConfirmation != null,
            awaitingAnswer = awaitingAnswer,
            expectFollowUp = followUp,
            usedAI = usedAI,
            offline = offline,
            changedData = changed,
        )
    }

    /**
     * Фраза не распознана локальными правилами:
     *  - в диалоге о записи (мозговой штурм) — дописываем сказанное в неё;
     *  - повествовательная фраза — предлагаем сохранить заметкой (не теряем мысль);
     *  - иначе — null (подсказка, что умею).
     */
    private fun localFallback(text: String, cfg: AssistantSettings): AssistantPlan? {
        val focus = context.focus
        if (context.dialogMode && focus != null && (focus.type == RecordType.NOTE || focus.type == RecordType.IDEA)) {
            return AssistantPlan(
                "", listOf(AssistantAction.AppendNote(TargetRef(focus.id, null, setOf(focus.type)), text.trim().replaceFirstChar { it.uppercase() })),
                expectFollowUp = true,
            )
        }
        val words = text.trim().split(Regex("\\s+")).size
        val question = text.trim().endsWith("?")
        if (!cfg.useAI && words >= 3 && !question) {
            return AssistantPlan("", emptyList(), slot = SlotRequest.SaveAsNote(text.trim(), "Не совсем поняла команду. Сохранить это как заметку?"))
        }
        return null
    }

    private fun matchOptionByTitle(text: String, options: List<RecordRef>): Int? {
        val q = TextAnalysis.stems(text)
        if (q.isEmpty()) return null
        val scored = options.mapIndexed { i, o ->
            val t = TextAnalysis.stems(o.title)
            i to q.count { s -> t.any { TextAnalysis.stemSimilarity(s, it) >= 0.7 } }.toDouble() / q.size
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        return if (best.second >= 0.5 && (second == null || best.second > second.second)) best.first else null
    }

    private fun withTarget(action: AssistantAction, id: String): AssistantAction = when (action) {
        is AssistantAction.AppendNote -> action.copy(target = action.target.withId(id), splitQueryFromContent = false)
        is AssistantAction.UpdateNote -> action.copy(target = action.target.withId(id))
        is AssistantAction.DeleteNote -> action.copy(target = action.target.withId(id))
        is AssistantAction.CompleteTask -> action.copy(target = action.target.withId(id))
        is AssistantAction.DeleteTask -> action.copy(target = action.target.withId(id))
        is AssistantAction.CancelReminder -> action.copy(target = action.target.withId(id))
        is AssistantAction.ForgetMemory -> action.copy(target = action.target.withId(id))
        else -> action
    }

    private suspend fun record(userText: String, reply: String) {
        context.addTurn(userText, reply)
        runCatching {
            conversations.add(context.conversationId, MessageRole.USER, userText)
            if (reply.isNotBlank()) conversations.add(context.conversationId, MessageRole.ASSISTANT, reply)
        }.onFailure { Logger.w(TAG, "Не удалось сохранить историю", it) }
    }

    companion object {
        private const val TAG = "Assistant"
        private const val MAX_CANDIDATES = 20

        /** Убирает обращение «Лоли, …» из начала текстовой команды. */
        fun stripWakeWord(input: String, name: String): String {
            val trimmed = input.trim()
            val match = WakeWordMatcher(name, threshold = 0.85).match(trimmed) ?: return trimmed
            return match.command
        }
    }
}
