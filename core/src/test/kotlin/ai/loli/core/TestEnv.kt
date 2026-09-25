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
) {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LoliDatabase.Schema.create(it) }
    val store = LocalStore(driver, time, Dispatchers.Unconfined)
    val scheduler = RecordingScheduler()
    var embeddings: EmbeddingProvider? = null
    val search = SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { embeddings }
    val resolver = TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories)
    val executor = ActionExecutor(store.notes, store.expenses, store.tasks, store.reminders, store.memories, search, resolver, scheduler, time, device, lockPolicy)
    var settings = AssistantSettings(useAI = true)
    var ai: AIProvider? = null

    val engine = AssistantEngine(
        store.notes, store.tasks, store.reminders, store.memories, store.conversations, search, executor, time,
        settings = { settings }, aiProvider = { ai },
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
