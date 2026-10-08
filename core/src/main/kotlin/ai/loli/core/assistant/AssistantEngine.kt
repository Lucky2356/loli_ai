package ai.loli.core.assistant

import ai.loli.core.nlp.RuTokenizer
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
    /** Правила экрана блокировки (null — телефон разблокирован или правила не заданы). */
    val lockPolicy: LockPolicy? = null,
    /** Характер: заботливая, деловая, шутливая. */
    val persona: Persona = Persona.CARING,
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
    /** В ответе личное (сообщения, экран, контакты): в историю для AI не попадает. */
    val sensitive: Boolean = false,
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
    /** Вода, лекарства, привычки, дыхание. */
    private val habits: ai.loli.core.health.Habits? = null,
    /** Строка про траты за день для «итогов дня» (null — не показывать). */
    private val daySpend: suspend (java.time.LocalDate) -> String? = { null },
    /** «Будь деловой» — приложение сохраняет новый характер. */
    private val onPersona: (Persona) -> Unit = {},
    /** Траты за период — для итогов недели и ленты дня. */
    private val expensesBetween: suspend (java.time.LocalDate, java.time.LocalDate) -> List<ai.loli.core.model.Expense> = { _, _ -> emptyList() },
) {
    private val review = ai.loli.core.review.Review(
        notes, tasks, reminders,
        habitLog = { a, b -> habits?.log(a, b).orEmpty() },
        expenses = expensesBetween, time = time,
    )

    /** Итоги недели для воскресной сводки — тот же текст, что и по голосовой команде. */
    suspend fun weeklySummary(): String = review.week()

    val context = ConversationContext(time)
    private val mutex = Mutex()

    /** Последняя ошибка AI (null — последний запрос к AI прошёл успешно). */
    @Volatile var lastAiFailure: AiFailure? = null
        private set

    /** Живой диалог: последний ответ (для «повтори»), фраза, которую можно повторить «ещё», был ли это разговор с моделью. */
    private var lastReply: AssistantReply? = null
    private var lastReplyAt: java.time.Instant = java.time.Instant.EPOCH
    private var lastMoreable: String? = null
    private var lastWasChat = false
    private var repeating = false

    /** Откуда пришла текущая фраза: диктовка возможна только голосом. */
    @Volatile private var currentSource: InputSource = InputSource.TEXT

    suspend fun handle(input: String, source: InputSource = InputSource.TEXT): AssistantReply = mutex.withLock {
        currentSource = source
        val cfg = settings()
        val text = ai.loli.core.nlp.Slang.normalize(stripWakeWord(input, cfg.assistantName))
        if (text.isBlank()) return@withLock AssistantReply("Слушаю!", expectFollowUp = true, awaitingAnswer = true)
        context.touch()

        repeating = false
        val reply = try {
            decorate(process(text, cfg), cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Ошибка обработки команды", e)
            AssistantReply("Что-то пошло не так: ${e.message ?: "неизвестная ошибка"}. Попробуйте ещё раз.")
        }
        if (!repeating) {
            record(text, reply.text, reply.sensitive)
            lastReply = reply; lastReplyAt = time.now()
            lastWasChat = reply.provider != null && reply.outcomes.isEmpty() && !reply.changedData
            if (MOREABLE.containsMatchIn(RuTokenizer.normalize(text))) lastMoreable = text
            else if (!MORE.containsMatchIn(RuTokenizer.normalize(text).trimEnd('?', '!', '.'))) lastMoreable = null
        }
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

    /** Характер в ответе: шутливая иногда добавляет фразу к сделанному, деловая обходится без восклицаний. */
    private fun decorate(reply: AssistantReply, cfg: AssistantSettings): AssistantReply = when (cfg.persona) {
        Persona.PLAYFUL -> {
            val extra = if (reply.changedData && !reply.awaitingAnswer && !reply.awaitingConfirmation && !reply.sensitive) cfg.persona.flourish(time.now().epochSecond) else null
            if (extra != null) reply.copy(text = reply.text.trimEnd() + " " + extra) else reply
        }
        Persona.BUSINESS -> if (reply.speakLanguage == null) reply.copy(text = reply.text.replace("!", ".").replace("..", ".")) else reply
        Persona.CARING -> reply
    }

    /** «Будь деловой», «какой у тебя характер», а также приветствие и «как дела» в своём характере. */
    private suspend fun personaTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        Persona.switch(text)?.let { p ->
            runCatching { onPersona(p) }
            return AssistantReply(Persona.switched(p))
        }
        if (Persona.isAsk(text)) {
            return AssistantReply(
                "Сейчас я ${cfg.persona.title.lowercase()}: ${cfg.persona.hint}. Можно сменить: «будь заботливой», «будь деловой» или «будь шутливой».",
            )
        }
        return null
    }

    private suspend fun smallTalk(text: String, cfg: AssistantSettings): AssistantReply? {
        val low = runCatching { habits?.moodLow() == true && !cfg.locked }.getOrDefault(false)
        return cfg.persona.smallTalk(text, time.zonedNow().hour, cfg.userName, low)?.let { AssistantReply(it) }
    }

    /** «У меня кот Барсик» — предлагаем запомнить, если такого в памяти ещё нет. */
    private suspend fun offerFact(text: String): AssistantReply? {
        val slot = FactOffer.offer(text) ?: return null
        val key = RuTokenizer.normalize(slot.content).trim()
        if (memories.all().any { RuTokenizer.normalize(it.content).trim() == key }) return null
        context.pendingSlot = slot
        return AssistantReply(slot.question, awaitingAnswer = true, expectFollowUp = true)
    }

    /** Голосовой разговор закончился (тишина, лимит, кнопка «стоп») — выходим из диалогового режима. */
    suspend fun endDialog() = mutex.withLock {
        context.dialogMode = false
        context.appendMode = false
        skills?.reset()
    }

    /** Экран выключили — забываем разговор: следующий человек с телефоном не продолжит его, а AI не увидит прошлого. */
    suspend fun forgetConversation() = mutex.withLock {
        context.reset()
        // Игру и «в каком городе?» не сбрасываем — экран гаснет посреди «Городов»; личное (ответ на сообщение) — забываем.
        skills?.forgetPrivate()
    }

    /** Понимает ли Лоли фразу без AI — чтобы из нескольких вариантов распознавания выбрать осмысленный. */
    fun understandsLocally(text: String): Boolean = runCatching {
        val t = ai.loli.core.nlp.Slang.normalize(stripWakeWord(text, settings().assistantName))
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
        val history = (if (cfg.locked) emptyList() else context.messages.takeLast(6)) + ChatMessage(ChatMessage.Role.USER, text)
        val out = try {
            lc.reply(ai.loli.core.ai.LocalChat.systemPrompt(cfg.assistantName, cfg.userName, cfg.persona.tone), history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Офлайн-модель не ответила", e)
            null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AssistantReply(out, offline = cfg.useAI, provider = "Офлайн-модель")
    }

    /** Дневник дня: настроение, итоги, неделя. Личное — на заблокированном экране не показываем. */
    private suspend fun diaryTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val h = habits ?: return null
        val today = time.today()
        val zone = time.zone()
        // Задачи и траты считаем только для «итогов дня», а не на каждую фразу.
        val r = h.diary(
            text,
            tasksDone = { tasks.all().count { it.completedAt?.atZone(zone)?.toLocalDate() == today } },
            spend = { daySpend(today) },
            startsCommand = localParser.startsWithCommand(text),
        ) ?: return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.allowsSkill(SkillAccess.PRIVATE)) return AssistantReply("Разблокируйте телефон — дневник личный, без разблокировки не открываю.")
        return AssistantReply(r.text, changedData = r.changed, sensitive = true, expectFollowUp = r.text.endsWith("?") || r.text.contains("«нет»"))
    }

    /** «Где паспорт?» — по записям «положила паспорт в…». Неизвестную вещь отдаём дальше, если вопрос мог быть не о вещи. */
    private suspend fun whereTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val ask = PersonalCommands.where(text) ?: return null
        // Машину ищет «парковка» (по месту на карте), телефон — громкий сигнал, а не записи о вещах.
        if (Regex("""машин|авто|тачк|парковк|припарков|телефон|смартфон|мобильн|трубк""").containsMatchIn(RuTokenizer.normalize(text))) return null
        val hits = ai.loli.core.personal.ThingsBook(memories).find(ask.item)
        if (hits.isEmpty() && !ask.strong) return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.view) return AssistantReply("Разблокируйте телефон — где лежат вещи, без разблокировки не говорю.")
        // Раньше могли сказать «запомни, что паспорт в сейфе» — ищем и в памяти.
        val said = if (hits.isNotEmpty()) null else runCatching { search.search(ask.item, setOf(RecordType.MEMORY), limit = 1, minScore = 0.4) }
            .getOrDefault(emptyList()).firstOrNull()?.doc?.title
        return AssistantReply(
            when {
                said != null -> "Вы говорили: ${RuFormat.quote(said)}."
                hits.isEmpty() -> "Не знаю, где ${ask.item}. Когда положите, скажите: «положила ${ask.item} в …» — запомню."
                hits.size == 1 -> "${hits[0].item} — ${hits[0].place}."
                else -> hits.joinToString("\n") { "• ${it.item} — ${it.place}" }
            },
            sensitive = true,
        )
    }

    /** «Итоги недели», «что я делала 5 октября». Личное — на заблокированном экране не показываем. */
    private suspend fun reviewTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val ask = ai.loli.core.review.Review.parse(text, time.today()) ?: return null
        // «Что было завтра» не бывает: про будущее — план на день.
        if (ask is ai.loli.core.review.Review.Ask.Day && ask.date.isAfter(time.today())) return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.allowsSkill(SkillAccess.PRIVATE)) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не показываю.")
        val out = when (ask) {
            ai.loli.core.review.Review.Ask.Week -> review.week()
            is ai.loli.core.review.Review.Ask.Day -> review.day(ask.date)
        }
        return AssistantReply(out, sensitive = true)
    }

    private var workout: ai.loli.core.skills.WorkoutSession? = null
    private var workoutAt = time.now()

    /** Тренировка: упражнения по очереди с таймером. Забывается через полчаса тишины. */
    private suspend fun workoutTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        if (workout != null && java.time.Duration.between(workoutAt, time.now()) > java.time.Duration.ofMinutes(30)) workout = null
        val session = workout
        if (session == null) {
            if (ai.loli.core.skills.Workout.isList(text)) return AssistantReply(ai.loli.core.skills.Workout.listText())
            val program = ai.loli.core.skills.Workout.start(text) ?: return null
            val s = ai.loli.core.skills.WorkoutSession(program)
            workout = s; workoutAt = time.now(); cook = null
            return exerciseReply(s.intro(), s, cfg)
        }
        val cmd = ai.loli.core.skills.Workout.command(text) ?: return null
        workoutAt = time.now()
        return when (cmd) {
            ai.loli.core.skills.Workout.Command.Stop -> {
                workout = null
                AssistantReply(finishWorkout(session, early = true), endsDialog = true, changedData = session.done > 0)
            }
            ai.loli.core.skills.Workout.Command.Repeat -> exerciseReply(session.step(), session, cfg)
            ai.loli.core.skills.Workout.Command.Next, ai.loli.core.skills.Workout.Command.Skip -> {
                session.advance(skipped = cmd == ai.loli.core.skills.Workout.Command.Skip)
                if (session.finished) {
                    workout = null
                    AssistantReply(finishWorkout(session, early = false), endsDialog = true, changedData = session.done > 0)
                } else exerciseReply(session.step(), session, cfg)
            }
        }
    }

    /** Текст упражнения + таймер на него (сигнал скажет, когда пора дальше). */
    private suspend fun exerciseReply(text: String, s: ai.loli.core.skills.WorkoutSession, cfg: AssistantSettings): AssistantReply {
        val e = s.current ?: return AssistantReply(text, expectFollowUp = true)
        val timer = AssistantAction.Device(DeviceCommand.Timer(e.seconds, "${s.program.name}: ${e.name.lowercase()}"))
        val r = execute(AssistantPlan("", listOf(timer), preface = text), usedAI = false, offline = false, name = cfg.assistantName)
        // Таймер не поставился (нет разрешения) — ведём без него.
        return r.copy(text = if (r.outcomes.any { it.kind == Outcome.Kind.ERROR }) text + " Засеките время сами, потом скажите «дальше»." else text, expectFollowUp = true)
    }

    /** Конец тренировки: отмечаем привычку («зарядка»), чтобы считались дни подряд. */
    private suspend fun finishWorkout(s: ai.loli.core.skills.WorkoutSession, early: Boolean): String {
        if (s.done == 0) return "Хорошо, без тренировки. В другой раз!"
        val streak = runCatching { habits?.run(ai.loli.core.health.HabitCommand.Mark(s.program.habit))?.text }.getOrNull()
        val head = if (early) "Закончили: ${RuFormat.count(s.done, "упражнение", "упражнения", "упражнений")} из ${s.program.exercises.size}." else "Тренировка окончена: ${s.program.name.lowercase()}, ${RuFormat.count(s.done, "упражнение", "упражнения", "упражнений")}. Молодец!"
        return head + (streak?.let { " $it" } ?: "")
    }

    private var cook: ai.loli.core.skills.CookingSession? = null
    private var cookAskedAt: java.time.Instant? = null
    private var cookAt = time.now()

    /** Готовка забывается после часа молчания. */
    private suspend fun cookingTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val active = cook
        if (active != null && java.time.Duration.between(cookAt, time.now()) > java.time.Duration.ofMinutes(60)) cook = null
        val session = cook
        if (session == null) {
            // Спросили «Что готовим?» — следующая короткая фраза и есть название блюда.
            val asked = cookAskedAt?.let { java.time.Duration.between(it, time.now()) <= java.time.Duration.ofMinutes(3) } == true
            cookAskedAt = null
            val dish = ai.loli.core.skills.Cooking.startDish(text)
                ?: (if (asked && !localParser.startsWithCommand(text) && text.trim().split(Regex("\\s+")).size <= 4 && !LocalCommandParser.isNo(text)) text.trim().trimEnd('.', '!', '?') else null)
                ?: return null
            val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
            if (policy != null && !policy.view) return AssistantReply("Разблокируйте телефон — рецепты без разблокировки не открываю.")
            if (dish.isEmpty()) { cookAskedAt = time.now(); return AssistantReply("Что готовим? Скажите название блюда — рецепт должен быть в заметках.", awaitingAnswer = true) }
            val note = ai.loli.core.skills.Cooking.find(notes.all(), dish)
                ?: return AssistantReply("В заметках нет рецепта «$dish». Продиктуйте его: «запиши заметку рецепт $dish: ингредиенты — …, сначала …, потом …».")
            val recipe = ai.loli.core.skills.Cooking.parse(note)
            if (recipe.steps.isEmpty()) return AssistantReply("В заметке «${note.title}» нет шагов. Допишите приготовление по шагам.")
            val s = ai.loli.core.skills.CookingSession(recipe)
            cook = s; cookAt = time.now()
            return AssistantReply(s.intro(), expectFollowUp = true)
        }
        val cmd = ai.loli.core.skills.Cooking.command(text) ?: return null
        cookAt = time.now()
        return when (cmd) {
            ai.loli.core.skills.CookingCommand.Stop -> { cook = null; AssistantReply("Хорошо, закончили готовить.", endsDialog = true) }
            ai.loli.core.skills.CookingCommand.Next -> AssistantReply(session.next(), expectFollowUp = !session.finished, endsDialog = session.finished).also { if (session.finished) cook = null }
            ai.loli.core.skills.CookingCommand.Repeat -> AssistantReply(if (session.finished) "Блюдо готово!" else session.current(), expectFollowUp = true)
            ai.loli.core.skills.CookingCommand.Back -> AssistantReply(session.back(), expectFollowUp = true)
            ai.loli.core.skills.CookingCommand.Ingredients -> AssistantReply(session.ingredients(), expectFollowUp = true)
            is ai.loli.core.skills.CookingCommand.Go -> AssistantReply(session.go(cmd.step), expectFollowUp = true)
            ai.loli.core.skills.CookingCommand.Timer -> {
                val sec = session.timerSeconds() ?: return AssistantReply("В этом шаге времени нет. Скажите, например: «таймер на 10 минут».", expectFollowUp = true)
                execute(AssistantPlan("", listOf(AssistantAction.Device(DeviceCommand.Timer(sec, session.recipe.title)))), usedAI = false, offline = false, name = cfg.assistantName)
                    .copy(expectFollowUp = true)
            }
        }
    }

    private suspend fun habitReply(text: String, cfg: AssistantSettings): AssistantReply? {
        val h = habits ?: return null
        val cmd = h.parse(text) ?: return null
        // Здоровье — личное: на экране блокировки только дыхание/медитация и отметки (если разрешено создавать записи).
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        val access = when (cmd) {
            is ai.loli.core.health.HabitCommand.Relax -> SkillAccess.PUBLIC
            is ai.loli.core.health.HabitCommand.EventSave -> SkillAccess.CREATE
            is ai.loli.core.health.HabitCommand.Countdown -> SkillAccess.VIEW
            is ai.loli.core.health.HabitCommand.Water, is ai.loli.core.health.HabitCommand.Pill, is ai.loli.core.health.HabitCommand.Mark -> SkillAccess.CREATE
            else -> SkillAccess.PRIVATE
        }
        if (policy != null && !policy.allowsSkill(access)) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не показываю.")
        val r = h.run(cmd) ?: return null
        if (r.device != null) {
            // Во время упражнения микрофон не слушает: иначе он услышит подсказки «вдох», «выдох» как команды.
            val relaxing = r.device is DeviceCommand.Relax && r.device.kind != RelaxKind.STOP
            return execute(AssistantPlan("", listOf(AssistantAction.Device(r.device)), preface = r.text), usedAI = false, offline = false, name = cfg.assistantName)
                .copy(sensitive = false, endsDialog = relaxing)
        }
        return AssistantReply(r.text, changedData = r.changed, sensitive = r.private)
    }

    private suspend fun dialogTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        if (context.pendingConfirmation != null || context.pendingChoice != null || context.pendingSlot != null) return null
        val n = RuTokenizer.normalize(text).trim().trimEnd('?', '!', '.', ',')
        val fresh = java.time.Duration.between(lastReplyAt, time.now()) <= DIALOG_TTL
        if (fresh && REPEAT.containsMatchIn(n)) lastReply?.let { r ->
            if (cfg.locked && r.sensitive) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не повторяю.")
            repeating = true
            return AssistantReply(r.text, sensitive = r.sensitive, speakLanguage = r.speakLanguage, provider = r.provider)
        }
        skills?.let { sk ->
            skillReply(sk.more(text, cfg, skillAi), cfg)?.let { return it }
            skillReply(sk.followUp(text, cfg, skillAi), cfg)?.let { return it }
        }
        if (fresh && MORE.containsMatchIn(n)) lastMoreable?.let { return process(it, cfg) }
        // «Отмени последнее» сразу после «выпила стакан воды» — отменяем отметку.
        if (UNDO.containsMatchIn(n)) habits?.undoRecent()?.let { return AssistantReply(it.text, changedData = true, sensitive = true) }
        // «Продолжай» после ответа модели — продолжение рассказа, а не музыка.
        if (fresh && lastWasChat && CONTINUE.containsMatchIn(n)) {
            if (!cfg.useAI) localAnswer("Продолжай.", cfg)?.let { return it }
        }
        return null
    }

    /** Фраза — команда (расход, задача, телефон, список)? Тогда она прерывает игру или ожидание навыка. */
    private fun isCommand(text: String): Boolean = runCatching {
        localParser.parse(text, time.now(), time.zone())?.actions?.isNotEmpty() == true || special.parse(text, time.today()) != null
    }.getOrDefault(false)

    private suspend fun runSkills(text: String, cfg: AssistantSettings): AssistantReply? = skillReply(skills?.handle(text, cfg, skillAi), cfg)

    private suspend fun skillReply(out: ai.loli.core.skills.SkillOutcome?, cfg: AssistantSettings): AssistantReply? =
        when (out) {
            is ai.loli.core.skills.SkillOutcome.Say -> AssistantReply(
                out.text, expectFollowUp = out.followUp, awaitingAnswer = out.followUp, permission = out.permission, speakLanguage = out.speakLanguage,
                sensitive = out.sensitive,
            )
            is ai.loli.core.skills.SkillOutcome.Run -> execute(out.plan, usedAI = false, offline = false, name = cfg.assistantName)
            null -> null
        }

    /** Подтверждение/отмена кнопкой в UI. */
    suspend fun respondToConfirmation(confirm: Boolean): AssistantReply = handle(if (confirm) "да" else "нет")

    private suspend fun process(text: String, cfg: AssistantSettings): AssistantReply {
        // «Работать без разблокировки» выключено — на заблокированном экране Лоли ничего не выполняет
        // (в том числе по слову-активатору) и ничего не отправляет AI.
        if (cfg.locked && cfg.lockPolicy?.any == false) {
            return AssistantReply("Разблокируйте телефон — без разблокировки я не отвечаю. Это можно разрешить в настройках Лоли.")
        }
        // 0. Идёт игра или навык ждёт ответа («В каком городе?») — фраза для него, если это не новая команда.
        var skillsTried = false
        if (skills?.busy == true) {
            if (skills.interruptedBy(text, cfg, ::isCommand)) skills.reset()
            else { skillsTried = true; runSkills(text, cfg)?.let { return it } }
        }
        // 0.4. Тренировка и режим готовки: «дальше», «повтори», «назад», «таймер».
        workoutTurn(text, cfg)?.let { return it }
        cookingTurn(text, cfg)?.let { return it }
        reviewTurn(text, cfg)?.let { return it }
        diaryTurn(text, cfg)?.let { return it }
        // 0.5. Живой диалог: «повтори», «ещё», «а завтра?», «продолжай».
        dialogTurn(text, cfg)?.let { return it }
        // 1. Ожидаем подтверждение опасного действия.
        context.pendingConfirmation?.let { pending ->
            when {
                LocalCommandParser.isYes(text) -> {
                    context.pendingConfirmation = null
                    // Пока ждали «да», телефон могли заблокировать: удаление — это изменение записей.
                    if (cfg.lockPolicy?.edit == false) return AssistantReply("Разблокируйте телефон — удалять записи без разблокировки не разрешено.")
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
            if (LocalCommandParser.isNo(text) && slot !is SlotRequest.SaveAsNote && slot !is SlotRequest.RememberFact) return AssistantReply("Хорошо, отменила.")
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
        personaTurn(text, cfg)?.let { return it }
        whereTurn(text, cfg)?.let { return it }
        special.parse(text, time.today())?.let { return execute(it, usedAI = false, offline = false, name = cfg.assistantName) }
        habitReply(text, cfg)?.let { return it }
        // Погода, курсы, новости, справка, сообщения, радио, игры, сказки…
        if (!skillsTried) runSkills(text, cfg)?.let { return it }
        special.boughtItems(text)?.let { bought ->
            val open = shoppingItems().filter { !it.done }
            val matched = bought.filter { b ->
                val stems = ai.loli.core.nlp.TextAnalysis.stems(b).toSet()
                open.any { i -> i.text.equals(b, true) || ai.loli.core.nlp.TextAnalysis.stems(i.text).any { it in stems } }
            }
            if (matched.isNotEmpty()) {
                // Каждую покупку отмечаем в том списке, где она есть.
                val actions = matched.map { b ->
                    val stems = ai.loli.core.nlp.TextAnalysis.stems(b).toSet()
                    val item = open.firstOrNull { i -> i.text.equals(b, true) } ?: open.first { i -> ai.loli.core.nlp.TextAnalysis.stems(i.text).any { it in stems } }
                    AssistantAction.CheckListItem(item.listName, b)
                }
                return execute(AssistantPlan("", actions), usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // Приветствие, «как дела», «спасибо» — в своём характере.
        smallTalk(text, cfg)?.let { return it }
        // 5. Облачный AI → при недоступности офлайн-парсер.
        var aiError: AIException? = null
        if (cfg.useAI) {
            val provider = try { aiProvider() } catch (e: AIException) { aiError = e; null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logger.w(TAG, "AI-провайдер не создан: ${e::class.simpleName}"); aiError = AIException.Network(e); null }
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
                } catch (e: Exception) {
                    // Любой другой сбой AI-ветки (разбор ответа, сеть под капотом) не должен ломать команду: работаем на устройстве.
                    Logger.w(TAG, "AI-ветка не сработала: ${e::class.simpleName}")
                    aiError = AIException.Network(e)
                    lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
                }
            }
        }
        // «Посоветуй, чем заняться в выходные» — просьба, а не задача на выходные.
        if (ai.loli.core.ai.LocalChat.isStrongChat(text)) localAnswer(text, cfg)?.let { return it }
        // «Мой размер обуви 38» — факт о себе, а не расход на 38 ₽: спрашиваем раньше разбора команд.
        offerFact(text)?.let { return it }
        val parsed = localParser.parse(text, time.now(), time.zone())
        // Вопрос или просьба, которую не понял разбор команд, — отвечает офлайн-модель, а не «сохранить заметкой?».
        if (parsed == null && ai.loli.core.ai.LocalChat.looksLikeChat(text)) localAnswer(text, cfg)?.let { return it }
        val local = parsed ?: localFallback(text, cfg)
        if (local != null) {
            val reply = execute(local, usedAI = false, offline = cfg.useAI, name = cfg.assistantName)
            // «Как приготовить плов» — не поиск по записям: если в записях пусто, отвечает офлайн-модель.
            // Вопрос о себе («что я люблю…», «где мой паспорт») модели не отдаём — она не знает ваших записей.
            if (local.actions.isNotEmpty() && local.actions.all { it is AssistantAction.Search } && reply.text.startsWith("Ничего не нашла") &&
                ai.loli.core.ai.LocalChat.looksLikeChat(text) && !Regex("""(?:^|\s)(?:я|мой|моя|моё|мое|мои|моего|моей|моих)(?:\s|$)""").containsMatchIn(text.lowercase())
            ) localAnswer(text, cfg)?.let { return it }
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
        return runCommands(routine, cfg)
    }

    /**
     * Сработало напоминание «Сценарий: по будням в 7:30» — выполняем сценарий сами. Телефон в это время обычно
     * заблокирован, но сценарий создан владельцем на разблокированном телефоне, поэтому выполняется без ограничений блокировки.
     * null — такого сценария уже нет.
     */
    suspend fun runScheduledRoutine(reminderText: String): AssistantReply? = mutex.withLock {
        if (!reminderText.startsWith(ai.loli.core.model.Routine.SCHEDULE_PREFIX)) return@withLock null
        val key = ai.loli.core.model.Routine.normalize(reminderText.removePrefix(ai.loli.core.model.Routine.SCHEDULE_PREFIX))
        val routine = runCatching { routines() }.getOrDefault(emptyList()).firstOrNull { ai.loli.core.model.Routine.normalize(it.trigger) == key }
            ?: return@withLock null
        val cfg = settings().copy(locked = false, lockPolicy = null)
        executor.trusted = true
        try {
            runCommands(routine, cfg).also { context.dialogMode = false; context.appendMode = false }
        } finally {
            executor.trusted = false
        }
    }

    private suspend fun runCommands(routine: ai.loli.core.model.Routine, cfg: AssistantSettings): AssistantReply {
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
        val rejected = ArrayList(plan.rejected)
        val kept = plan.actions.filter { a ->
            val cmd = (a as? AssistantAction.Device)?.command ?: return@filter true
            val ok = DeviceGuard.allowed(cmd, userText)
            if (!ok) rejected += "AI предложил действие, о котором вы не просили — не выполняю"
            ok
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
            userName = cfg.userName, persona = cfg.persona,
        )
        // На блокировке прошлый разговор модели не показываем: в нём могли быть личные данные.
        val history = if (cfg.locked) emptyList() else context.messages
        val request = AIRequest(system = system, messages = history + ChatMessage(ChatMessage.Role.USER, text))
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
        is AssistantAction.RescheduleReminder -> action.copy(target = action.target.withId(id))
        is AssistantAction.RescheduleTask -> action.copy(target = action.target.withId(id))
        is AssistantAction.ForgetMemory -> action.copy(target = action.target.withId(id))
        else -> action
    }

    private suspend fun record(userText: String, reply: String, sensitive: Boolean = false) {
        // Личное (сообщения, экран, контакты) остаётся в истории на телефоне, но не уходит AI в следующих запросах.
        context.addTurn(userText, if (sensitive) "(личные данные — показаны пользователю, не пересказываю)" else reply)
        runCatching {
            conversations.add(context.conversationId, MessageRole.USER, userText)
            if (reply.isNotBlank()) conversations.add(context.conversationId, MessageRole.ASSISTANT, reply)
        }.onFailure { Logger.w(TAG, "Не удалось сохранить историю", it) }
    }

    companion object {
        private const val TAG = "Assistant"
        private val DIALOG_TTL: java.time.Duration = java.time.Duration.ofMinutes(3)
        private val REPEAT = Regex("""^(?:повтори|повтори пожалуйста|повтори еще раз|повтори ещё раз|повтори последнее|повтори ответ|что ты сказала|что ты сказал|что ты говоришь|что ты там сказала|еще раз|ещё раз|скажи еще раз|скажи ещё раз|не расслышал|не расслышала|я не расслышал|я не расслышала|не поняла повтори|не понял повтори|что-что|что что|чего|а)$""")
        private val MORE = Regex("""^(?:а\s+)?(?:давай\s+)?(?:ещ[её]|еще)(?:\s+(?:одну|один|одно|разок|пожалуйста|давай|анекдот|шутку|факт|комплимент|цитату|тост|скороговорку|загадку))?$""")
        private val MOREABLE = Regex("""анекдот|шутк|пошути|смешное|факт|комплимент|цитат|тост|скороговорк|стих|монетк|кубик|случайное число|совет дня|мотивац""")
        private val UNDO = Regex("""^(?:отмени|удали|убери)\s+(?:последнее|это|то что (?:я )?(?:только что )?(?:сказала|сказал|добавила|добавил))$|^(?:отмени|отмена последнего)$""")
        private val CONTINUE = Regex("""^(?:продолжай|продолжи|дальше|и что дальше|а дальше|что было дальше|рассказывай дальше|продолжай рассказ|и\?)$""")
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
