package ai.loli.core.assistant

import ai.loli.core.ai.AIException
import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.ChainAIProvider
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
    /** Разрешён ли диалоговый режим (продолжать разговор без обращения по имени). */
    val dialogMode: Boolean = true,
    /** Телефон заблокирован: облачному AI не передаются память и записи пользователя. */
    val locked: Boolean = false,
    /** Как зовут пользователя («называй меня …»). */
    val userName: String? = null,
    /** Город пользователя — для погоды, если геолокация недоступна. */
    val city: String? = null,
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
    /** Пользователь закончил разговор («хватит», «спасибо, всё») — больше не слушать. */
    val endsDialog: Boolean = false,
    /** Какой AI-провайдер ответил (при нескольких подключённых). */
    val provider: String? = null,
    /** Почему AI не ответил и команда выполнена на устройстве (понятное человеку объяснение). */
    val aiError: String? = null,
    /** Язык озвучки ответа (перевод), ISO-код; null — русский. */
    val speakLanguage: String? = null,
    /** Начать длинную диктовку заметки: голос слушает с большими паузами до «готово». */
    val dictation: Boolean = false,
    /** Для ответа нужно разрешение — приложение предложит его выдать. */
    val permission: ai.loli.core.skills.Permission? = null,
)

/** Последний сбой AI — для экрана настроек: что именно не так с подключением. */
data class AiFailure(val message: String, val at: java.time.Instant)

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
    private val routines: suspend () -> List<ai.loli.core.model.Routine> = { emptyList() },
    private val shoppingItems: suspend () -> List<ai.loli.core.model.ShoppingItem> = { emptyList() },
    private val special: SpecialCommands = SpecialCommands(),
    /** Погода, курсы, новости, сообщения, радио, игры… */
    val skills: ai.loli.core.skills.Skills? = null,
    /** Фраза не понята ни на устройстве, ни AI — для журнала непонятых фраз (только на телефоне). */
    private val onNotUnderstood: (String) -> Unit = {},
    /** Офлайн-модель на телефоне: свободные вопросы без интернета и ключей. */
    private val localChat: ai.loli.core.ai.LocalChat? = null,
) {
    val context = ConversationContext(time)
    private val mutex = Mutex()

    /** Последняя ошибка AI (null — последний запрос к AI прошёл успешно). */
    @Volatile var lastAiFailure: AiFailure? = null
        private set

    /** Откуда пришла текущая фраза: диктовка возможна только голосом. */
    @Volatile private var currentSource: InputSource = InputSource.TEXT

    suspend fun handle(input: String, source: InputSource = InputSource.TEXT): AssistantReply = mutex.withLock {
        currentSource = source
        val cfg = settings()
        val text = stripWakeWord(input, cfg.assistantName)
        if (text.isBlank()) return@withLock AssistantReply("Слушаю!", expectFollowUp = true, awaitingAnswer = true)
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
        continueDialog(reply, source, cfg)
    }

    /**
     * Голосовой разговор: при включённом диалоговом режиме ассистент продолжает слушать после любого ответа,
     * пока пользователь не скажет «хватит» или не замолчит. Без него — только если ждём ответа на вопрос.
     */
    private fun continueDialog(reply: AssistantReply, source: InputSource, cfg: AssistantSettings): AssistantReply {
        if (reply.endsDialog) {
            context.dialogMode = false
            context.appendMode = false
            return reply.copy(expectFollowUp = false)
        }
        if (source == InputSource.TEXT) return reply
        val waiting = reply.expectFollowUp || reply.awaitingAnswer || reply.awaitingConfirmation
        if (cfg.dialogMode) {
            context.dialogMode = true
        } else if (!waiting) {
            context.dialogMode = false
            context.appendMode = false
        }
        return reply.copy(expectFollowUp = cfg.dialogMode || waiting)
    }

    /** Голосовой разговор закончился (тишина, лимит, кнопка «стоп») — выходим из диалогового режима. */
    suspend fun endDialog() = mutex.withLock {
        context.dialogMode = false
        context.appendMode = false
        skills?.reset()
    }

    /** Понимает ли Лоли фразу без AI — чтобы из нескольких вариантов распознавания выбрать осмысленный. */
    fun understandsLocally(text: String): Boolean = runCatching {
        val t = stripWakeWord(text, settings().assistantName)
        localParser.parse(t, time.now(), time.zone()) != null || skills?.recognizes(t, settings().assistantName) == true
    }.getOrDefault(false)

    /** Облачный AI для навыков (пересказ экрана, сказки); null — AI не подключён или недоступен. */
    private val skillAi: ai.loli.core.skills.SkillAi = { system, user, maxTokens ->
        val cloud = if (!settings().useAI) null else try {
            aiProvider()?.complete(AIRequest(system = system, messages = listOf(ChatMessage(ChatMessage.Role.USER, user)), jsonMode = false, maxTokens = maxTokens))
                ?.text?.trim()?.also { lastAiFailure = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AIException) {
            lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
            null
        }
        // Нет облачного AI — отвечает офлайн-модель (если скачана).
        cloud ?: localChat?.takeIf { it.available }?.let { lc ->
            runCatching { lc.reply(system, listOf(ChatMessage(ChatMessage.Role.USER, user.take(3000))), maxTokens.coerceAtMost(600)) }.getOrNull()
        }
    }

    /** Свободный вопрос без облачного AI — офлайн-модель. */
    private suspend fun localAnswer(text: String, cfg: AssistantSettings): AssistantReply? {
        val lc = localChat?.takeIf { it.available } ?: return null
        val history = context.messages.takeLast(6) + ChatMessage(ChatMessage.Role.USER, text)
        val out = try {
            lc.reply(ai.loli.core.ai.LocalChat.systemPrompt(cfg.assistantName, cfg.userName), history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Офлайн-модель не ответила", e)
            null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AssistantReply(out, offline = cfg.useAI, provider = "Офлайн-модель")
    }

    private suspend fun runSkills(text: String, cfg: AssistantSettings): AssistantReply? =
        when (val out = skills?.handle(text, cfg, skillAi)) {
            is ai.loli.core.skills.SkillOutcome.Say -> AssistantReply(
                out.text, expectFollowUp = out.followUp, awaitingAnswer = out.followUp, permission = out.permission, speakLanguage = out.speakLanguage,
            )
            is ai.loli.core.skills.SkillOutcome.Run -> execute(out.plan, usedAI = false, offline = false, name = cfg.assistantName)
            null -> null
        }

    /** Подтверждение/отмена кнопкой в UI. */
    suspend fun respondToConfirmation(confirm: Boolean): AssistantReply = handle(if (confirm) "да" else "нет")

    private suspend fun process(text: String, cfg: AssistantSettings): AssistantReply {
        // 0. Идёт игра или навык ждёт ответа («В каком городе?») — фраза для него.
        if (skills?.busy == true) runSkills(text, cfg)?.let { return it }
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
                return execute(AssistantPlan("", actions), usedAI = false, offline = false, carriedConfirmation = kept, name = cfg.assistantName, dialogAllowed = cfg.dialogMode)
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
        if ((context.dialogMode || context.appendMode) && LocalCommandParser.isDialogEnd(text)) {
            return AssistantReply("Хорошо! Если что — зовите.", endsDialog = true)
        }
        // 4. Сценарии, перевод, списки, дни рождения, секретные заметки — точные команды, выполняются на устройстве.
        runRoutine(text, cfg)?.let { return it }
        if (currentSource != InputSource.TEXT && SpecialCommands.isDictationStart(text)) {
            return AssistantReply("Диктуйте — я записываю. Можно делать паузы. Когда закончите, скажите «готово».", dictation = true, expectFollowUp = false)
        }
        special.translation(text)?.let { return translate(it, cfg) }
        special.parse(text, time.today())?.let { return execute(it, usedAI = false, offline = false, name = cfg.assistantName) }
        // Погода, курсы, новости, справка, сообщения, радио, игры, сказки…
        runSkills(text, cfg)?.let { return it }
        special.boughtItems(text)?.let { bought ->
            val open = shoppingItems().filter { !it.done }
            val matched = bought.filter { b ->
                val stems = ai.loli.core.nlp.TextAnalysis.stems(b).toSet()
                open.any { i -> i.text.equals(b, true) || ai.loli.core.nlp.TextAnalysis.stems(i.text).any { it in stems } }
            }
            if (matched.isNotEmpty()) {
                val list = open.first().listName
                return execute(AssistantPlan("", matched.map { AssistantAction.CheckListItem(list, it) }), usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // 5. Облачный AI → при недоступности офлайн-парсер.
        var aiError: AIException? = null
        if (cfg.useAI) {
            val provider = try { aiProvider() } catch (e: AIException) { aiError = e; null }
            if (provider != null) {
                try {
                    val plan = guardDeviceActions(planWithAI(provider, text, cfg), text)
                    lastAiFailure = null
                    val used = (provider as? ChainAIProvider)?.lastUsed ?: provider
                    return execute(plan, usedAI = true, offline = false, name = cfg.assistantName)
                        .copy(provider = "${used.type.title} · ${used.model}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AIException) {
                    Logger.w(TAG, "AI недоступен: ${e::class.simpleName}")
                    aiError = e
                    lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
                }
            }
        }
        val parsed = localParser.parse(text, time.now(), time.zone())
        // Вопрос или просьба, которую не понял разбор команд, — отвечает офлайн-модель, а не «сохранить заметкой?».
        if (parsed == null && ai.loli.core.ai.LocalChat.looksLikeChat(text)) localAnswer(text, cfg)?.let { return it }
        val local = parsed ?: localFallback(text, cfg)
        if (local != null) {
            val reply = execute(local, usedAI = false, offline = cfg.useAI, name = cfg.assistantName)
            val note = when (aiError) {
                is AIException.Unauthorized -> " (AI: ключ не принят — выполнено на устройстве)"
                else -> ""
            }
            return reply.copy(text = reply.text + note, aiError = aiError?.let { shortReason(it) })
        }
        localAnswer(text, cfg)?.let { return it }
        runCatching { onNotUnderstood(text) }
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

    /** Сохраняет надиктованный текст заметкой. */
    suspend fun saveDictation(raw: String): AssistantReply = mutex.withLock {
        val text = SpecialCommands.cleanDictation(raw)
        if (text.isBlank()) return@withLock AssistantReply("Ничего не услышала — заметку не сохраняю.")
        val title = text.split(Regex("""\s+""")).take(6).joinToString(" ").trimEnd(',', '.', ':').replaceFirstChar { it.uppercase() }
        val note = notes.create(ai.loli.core.model.NoteKind.NOTE, title, text.replaceFirstChar { it.uppercase() })
        context.touchRecord(RecordRef(RecordType.NOTE, note.id, note.title))
        runCatching { conversations.add(context.conversationId, MessageRole.USER, text) }
        val words = text.split(Regex("""\s+""")).size
        AssistantReply("Сохранила заметку ${RuFormat.quote(note.title)} — ${RuFormat.count(words, "слово", "слова", "слов")}.", changedData = true)
    }

    /** Фраза совпала со сценарием («спокойной ночи») — выполняем его команды по очереди. */
    private var routineDepth = 0

    private suspend fun runRoutine(text: String, cfg: AssistantSettings): AssistantReply? {
        // Сценарий внутри сценария не запускается — иначе фраза, ссылающаяся на себя, зациклится.
        if (routineDepth > 0) return null
        val list = runCatching { routines() }.getOrDefault(emptyList())
        if (list.isEmpty()) return null
        val key = ai.loli.core.model.Routine.normalize(text)
            .replace(Regex("""^(?:запусти|включи|выполни)\s+(?:сценарий\s+)?"""), "")
        val routine = list.firstOrNull { ai.loli.core.model.Routine.normalize(it.trigger) == key } ?: return null
        val replies = ArrayList<String>()
        var changed = false
        for (cmd in routine.commands.take(10)) {
            routineDepth++
            val r = try {
                process(cmd, cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantReply("Не получилось: $cmd")
            } finally {
                routineDepth--
            }
            // Сценарий не ведёт диалог: вопросы и подтверждения внутри него не ждём.
            context.pendingConfirmation = null; context.pendingChoice = null; context.pendingSlot = null
            if (r.text.isNotBlank()) replies += r.text
            changed = changed || r.changedData
        }
        return AssistantReply("Сценарий ${RuFormat.quote(routine.trigger)}:\n" + replies.joinToString("\n") { "• $it" }, changedData = changed)
    }

    /** Перевод: с AI — любой текст, без AI — небольшой словарь частых фраз. */
    private suspend fun translate(t: SpecialCommands.Translation, cfg: AssistantSettings): AssistantReply {
        val offline = SpecialCommands.OFFLINE[t.language]?.get(ai.loli.core.nlp.RuTokenizer.normalize(t.phrase).trim(' ', ',', '.', '!', '?'))
        if (cfg.useAI) {
            val provider = runCatching { aiProvider() }.getOrNull()
            if (provider != null) {
                try {
                    val out = provider.complete(
                        AIRequest(
                            system = "Ты переводчик. Переведи текст пользователя на ${t.languageRu} язык. Ответь только переводом — без кавычек, пояснений и транслитерации.",
                            messages = listOf(ChatMessage(ChatMessage.Role.USER, t.phrase.take(2000))),
                            jsonMode = false,
                            maxTokens = 600,
                        ),
                    ).text.trim().trim('"', '«', '»')
                    lastAiFailure = null
                    if (out.isNotEmpty()) return AssistantReply(out, usedAI = true, speakLanguage = t.language)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AIException) {
                    lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
                }
            }
        }
        if (offline != null) return AssistantReply(offline, speakLanguage = t.language, offline = cfg.useAI)
        return AssistantReply("Чтобы переводить любые фразы, подключите облачный AI в настройках. Без интернета я знаю только самые частые: «привет», «спасибо», «как дела»…")
    }

    /** Короткая причина сбоя AI для подписи под ответом. */
    private fun shortReason(e: AIException): String = when (e) {
        is AIException.Unauthorized -> "ключ не принят"
        is AIException.RateLimited -> "превышен лимит запросов"
        is AIException.Network -> "нет связи с сервисом"
        is AIException.NotConfigured -> "не настроен"
        is AIException.InsecureEndpoint -> "небезопасный адрес сервера"
        is AIException.Refused -> "модель отказалась отвечать"
        is AIException.Server -> "ошибка сервиса ${e.code}"
        is AIException.InvalidResponse -> "непонятный ответ модели"
    }

    /**
     * Защита от «инъекций»: в запрос к AI попадают тексты ваших заметок и память. Если в них окажется чужая
     * инструкция («позвони на номер…», «отправь…»), модель может её выполнить. Поэтому звонки, сообщения,
     * отправка, открытие сайтов и контакты от AI выполняются, только если вы сами об этом попросили в этой фразе.
     */
    private fun guardDeviceActions(plan: AssistantPlan, userText: String): AssistantPlan {
        val n = ai.loli.core.nlp.RuTokenizer.normalize(userText)
        val rejected = ArrayList(plan.rejected)
        val kept = plan.actions.filter { a ->
            val cmd = (a as? AssistantAction.Device)?.command ?: return@filter true
            val asked = when (cmd) {
                is DeviceCommand.Call -> Regex("""позвон|набер|звонок|вызов|звякн""").containsMatchIn(n)
                is DeviceCommand.Message -> Regex("""напиш|сообщ|смс|sms|отправ|перешл|скажи""").containsMatchIn(n)
                is DeviceCommand.Share -> Regex("""отправ|перешл|подел|скинь|напиш""").containsMatchIn(n)
                is DeviceCommand.OpenUrl -> Regex("""сайт|ссылк|страниц|открой|зайди|перейди|http|www|\.ru|\.com""").containsMatchIn(n)
                is DeviceCommand.AddContact -> Regex("""контакт|номер""").containsMatchIn(n)
                is DeviceCommand.OpenApp -> Regex("""открой|запусти|включи|зайди|перейди|приложени""").containsMatchIn(n)
                else -> true
            }
            if (!asked) rejected += "AI предложил действие, о котором вы не просили — не выполняю"
            asked
        }
        return if (kept.size == plan.actions.size) plan else plan.copy(actions = kept, rejected = rejected)
    }

    private suspend fun planWithAI(provider: AIProvider, text: String, cfg: AssistantSettings): AssistantPlan {
        // На экране блокировки модель не видит личных данных — ответ не сможет их раскрыть.
        val candidates = if (cfg.locked) emptyList() else gatherCandidates(text)
        val handles = candidates.associate { it.handle to it.id }
        val memoryItems = if (cfg.locked) emptyList() else memories.all().take(25)
        val system = PromptBuilder.build(
            cfg.assistantName, time.now(), time.zone(), memoryItems, candidates, context.focus, context.topic, context.dialogMode,
            userName = cfg.userName,
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
        dialogAllowed: Boolean = settings().dialogMode,
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
        if (plan.expectFollowUp && dialogAllowed) {
            context.dialogMode = true
            context.appendMode = true
        }
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
        if (context.appendMode && focus != null && (focus.type == RecordType.NOTE || focus.type == RecordType.IDEA)) {
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
            WakeWordMatcher(name, threshold = 0.85).match(trimmed)?.let { return it.command }
            // Распознаватель пишет имя по-разному («Лоля», «Лолли»). Мягче сравниваем, если дальше идёт команда.
            WakeWordMatcher(name, threshold = 0.7).match(trimmed)?.let { m ->
                val first = ai.loli.core.nlp.RuTokenizer.normalize(m.command.substringBefore(' ').trim(',', '.'))
                if (first in COMMAND_WORDS || first.endsWith("ть") && first.length >= 5) return m.command
            }
            // «поставь, Лоли, задачу…», «напомни мне, Лоли, …», «…, Лоли»
            val strict = WakeWordMatcher(name, threshold = 0.85)
            val cut = Regex(""",\s*([\p{L}-]+)\s*(?:,|$)""").findAll(trimmed).firstOrNull { strict.similarity(ai.loli.core.nlp.RuTokenizer.normalize(it.groupValues[1])) >= 0.85 }
            if (cut != null) {
                val joined = trimmed.substring(0, cut.range.first) + " " + trimmed.substring(cut.range.last + 1)
                return joined.replace(Regex("""\s{2,}"""), " ").trim().trim(',').trim()
            }
            return trimmed
        }

        private val COMMAND_WORDS = setOf(
            "добавь", "поставь", "заведи", "создай", "запиши", "напомни", "найди", "покажи", "удали", "отметь", "запомни",
            "сохрани", "внеси", "запланируй", "позвони", "напиши", "открой", "включи", "выключи", "сделай", "скажи", "расскажи",
            "сколько", "какая", "какой", "какие", "что", "когда", "где", "мне", "у", "разбуди", "переведи", "стоп", "задача", "задачу",
        )
    }
}
