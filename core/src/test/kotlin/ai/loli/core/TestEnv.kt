package ai.loli.core

import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIProviderType
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.AIResponse
import ai.loli.core.ai.EmbeddingProvider
import ai.loli.core.assistant.ActionExecutor
import ai.loli.core.assistant.AssistantEngine
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.ReminderScheduler
import ai.loli.core.assistant.TargetResolver
import ai.loli.core.data.LocalStore
import ai.loli.core.health.Habits
import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.Reminder
import ai.loli.core.search.SearchService
import ai.loli.core.util.FixedTimeSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId

/** Тестовое окружение: настоящая SQLite в памяти + фиксированное время (пятница, 25.09.2026, 12:00 МСК). */
class TestEnv(
    val time: FixedTimeSource = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), ZoneId.of("Europe/Moscow")),
    device: ai.loli.core.assistant.DeviceController = ai.loli.core.assistant.UnsupportedDevice,
    lockPolicy: () -> ai.loli.core.assistant.LockPolicy? = { null },
    skillsFactory: ((FixedTimeSource) -> ai.loli.core.skills.Skills)? = null,
    localChat: ai.loli.core.ai.LocalChat? = null,
    /** Обёртка над тратами (например, передача в Финансовый помощник). */
    wrapExpenses: (ai.loli.core.domain.ExpenseRepository) -> ai.loli.core.domain.ExpenseRepository = { it },
) {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LoliDatabase.Schema.create(it) }
    val store = LocalStore(driver, time, Dispatchers.Unconfined)
    val scheduler = RecordingScheduler()
    /** Напоминания, созданные как «настойчивые». */
    val persistent = mutableListOf<String>()
    var embeddings: EmbeddingProvider? = null
    val search = SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { embeddings }
    val resolver = TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories)
    val expenses = wrapExpenses(store.expenses)
    val executor = ActionExecutor(
        store.notes, expenses, store.tasks, store.reminders, store.memories, search, resolver, scheduler, time, device, lockPolicy,
        shopping = store.shopping, routines = store.routines, secrets = store.secrets,
        onPersistentReminder = { persistent += it },
    )
    var settings = AssistantSettings(useAI = true)
    var ai: AIProvider? = null

    /** Новый характер, выбранный голосом («будь деловой»). */
    var onPersona: (ai.loli.core.assistant.Persona) -> Unit = {}
    val engine = AssistantEngine(
        store.notes, store.tasks, store.reminders, store.memories, store.conversations, search, executor, time,
        settings = { settings }, aiProvider = { ai },
        routines = { store.routines.all() }, shoppingItems = { store.shopping.all() },
        skills = skillsFactory?.invoke(time),
        localChat = localChat,
        habits = Habits(store.habits, time),
        daySpend = { d -> store.expenses.between(d, d).takeIf { it.isNotEmpty() }?.let { "Потрачено: ${it.sumOf { e -> e.amountMinor } / 100} ₽." } },
        onPersona = { onPersona(it) },
    )
}

class RecordingScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Reminder>()
    val cancelled = mutableListOf<String>()
    override fun schedule(reminder: Reminder) { scheduled += reminder }
    override fun cancel(reminderId: String) { cancelled += reminderId }
}

/** AI-провайдер со сценарием ответов: возвращает заранее заданный JSON и запоминает запросы. */
class ScriptedAI(private val responder: (AIRequest) -> String) : AIProvider {
    override val type = AIProviderType.OPENAI
    override val model = "test-model"
    val requests = mutableListOf<AIRequest>()
    override suspend fun complete(request: AIRequest): AIResponse {
        requests += request
        return AIResponse(responder(request), model, "stop")
    }
}

/** Достаёт из системного промпта метку кандидата по фрагменту заголовка. */
fun AIRequest.handleFor(titleFragment: String): String =
    Regex("""(#\d+) \[[^\]]+] «([^»]*)»""").findAll(system).firstOrNull { it.groupValues[2].contains(titleFragment, ignoreCase = true) }
        ?.groupValues?.get(1) ?: error("Кандидат «$titleFragment» не найден в промпте")
