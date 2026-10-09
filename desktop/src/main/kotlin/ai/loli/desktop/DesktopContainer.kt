package ai.loli.desktop

import ai.loli.core.ai.AIConfig
import ai.loli.core.ai.AIException
import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIProviderFactory
import ai.loli.core.ai.AIProviderType
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.ChatMessage
import ai.loli.core.assistant.ActionExecutor
import ai.loli.core.assistant.AssistantEngine
import ai.loli.core.assistant.AssistantReply
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.ConversationContext
import ai.loli.core.assistant.InputSource
import ai.loli.core.assistant.Persona
import ai.loli.core.assistant.QuickAdd
import ai.loli.core.assistant.ReminderScheduler
import ai.loli.core.assistant.TargetResolver
import ai.loli.core.backup.BackupService
import ai.loli.core.data.LocalStore
import ai.loli.core.db.LoliDatabase
import ai.loli.core.health.Habits
import ai.loli.core.model.Reminder
import ai.loli.core.search.SearchService
import ai.loli.core.skills.SkillHost
import ai.loli.core.skills.Skills
import ai.loli.core.util.Logger
import ai.loli.core.util.SystemTimeSource
import ai.loli.desktop.voice.VoiceController
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/**
 * Всё, что нужно клиенту для Windows: база на компьютере, тот же движок Лоли, что и на телефоне,
 * облачный AI (ключ зашифрован DPAPI), голос, команды компьютеру и напоминания в трее.
 */
class DesktopContainer(val dataDir: File = defaultDataDir()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val time = SystemTimeSource()
    val settings = DesktopSettings(File(dataDir, "settings.properties"))
    val secrets = SecretStore(File(dataDir, "secrets.properties"))

    // WAL: чтение (экраны) не ждёт записи (ответ Лоли); busy_timeout — вместо «database is locked» подождать до 5 с.
    private val driver = JdbcSqliteDriver(
        "jdbc:sqlite:" + File(dataDir, "loli.db").absolutePath,
        Properties().apply { put("journal_mode", "WAL"); put("synchronous", "NORMAL"); put("busy_timeout", "5000") },
        LoliDatabase.Schema,
    )
    val store = LocalStore(driver, time, Dispatchers.IO)

    val http = HttpClient(Java) {
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
        expectSuccess = false
    }

    val search = SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { null }
    private val resolver = TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories)

    /** Будильников в Windows нет: напоминания проверяет [ReminderTicker], пока Лоли запущена (в том числе в трее). */
    private val scheduler = object : ReminderScheduler {
        override fun schedule(reminder: Reminder) = Unit
        override fun cancel(reminderId: String) = Unit
    }

    val device = DesktopDevice(store, time)

    val executor = ActionExecutor(
        store.notes, store.expenses, store.tasks, store.reminders, store.memories, search, resolver, scheduler, time, device,
        shopping = store.shopping, routines = store.routines, secrets = store.secrets,
    )

    private val skillHost = object : SkillHost {
        override fun setUserName(name: String?) = settings.update { it.copy(userName = name.orEmpty()) }
        override fun setCity(city: String?) = settings.update { it.copy(city = city.orEmpty()) }
    }

    // --- AI -----------------------------------------------------------------------

    fun apiKey(type: AIProviderType): String = secrets.get("ai_key_${type.id}").orEmpty()
    /** true — ключ сохранён. */
    fun setApiKey(type: AIProviderType, key: String): Boolean = secrets.put("ai_key_${type.id}", AIConfig.cleanApiKey(key).ifEmpty { null })

    fun aiConfig(s: DesktopSettings.Values = settings.value): AIConfig {
        val type = AIProviderType.fromId(s.aiProvider).takeIf { !it.builtIn } ?: AIProviderType.OPENAI
        return AIConfig(type, s.aiEndpoint.ifBlank { null }, s.aiModel.ifBlank { null }, apiKey(type), embeddingsEnabled = false)
    }

    @Volatile private var cached: Pair<String, AIProvider>? = null

    /** Провайдер по текущим настройкам; null — AI выключен или не настроен. */
    private fun aiProvider(): AIProvider? {
        val s = settings.value
        if (!s.aiEnabled) return null
        val cfg = aiConfig(s)
        if (!cfg.isComplete) return null
        val key = "${cfg.type.id}|${cfg.endpoint}|${cfg.model}|${cfg.apiKey.hashCode()}"
        cached?.takeIf { it.first == key }?.let { return it.second }
        return AIProviderFactory.createChain(http, listOf(cfg)).also { cached = key to it }
    }

    val aiReady: Boolean get() = settings.value.aiEnabled && aiConfig().isComplete

    /** «Проверить подключение»: короткий запрос к модели. null — всё хорошо, иначе понятная причина. */
    suspend fun testAi(cfg: AIConfig): String? = try {
        val p = AIProviderFactory.create(http, cfg)
        val r = p.complete(AIRequest(system = "Ответь одним словом.", messages = listOf(ChatMessage(ChatMessage.Role.USER, "Скажи: работает")), jsonMode = false, maxTokens = 20))
        if (r.text.isBlank()) "Модель ответила пустым сообщением." else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: AIException) {
        e.message
    } catch (e: Exception) {
        "Не удалось связаться: ${e.message ?: e::class.simpleName}"
    }

    // --- Движок -------------------------------------------------------------------

    val engine = AssistantEngine(
        notes = store.notes, tasks = store.tasks, reminders = store.reminders, memories = store.memories,
        conversations = store.conversations, search = search, executor = executor, time = time,
        settings = { assistantSettings() },
        aiProvider = { aiProvider() },
        routines = { store.routines.all() }, shoppingItems = { store.shopping.all() },
        skills = Skills(skillHost, http, time),
        habits = Habits(store.habits, time),
        onPersona = { p -> settings.update { it.copy(persona = p.wire) } },
        expensesBetween = { a, b -> store.expenses.between(a, b) },
        daySpend = { d ->
            store.expenses.between(d, d).filter { it.category != ai.loli.core.nlp.ExpenseCategories.INCOME }.takeIf { it.isNotEmpty() }
                ?.let { l -> "Потрачено: " + l.groupBy { it.currency }.entries.joinToString(", ") { (c, x) -> ai.loli.core.nlp.Money.format(x.sumOf { it.amountMinor }, c) } + "." }
        },
    )

    fun assistantSettings(): AssistantSettings = settings.value.let {
        AssistantSettings(
            assistantName = it.assistantName, useAI = aiReady, dialogMode = it.dialogMode,
            userName = it.userName.ifBlank { null }, city = it.city.ifBlank { null }, persona = Persona.of(it.persona),
        )
    }

    private val _busy = MutableStateFlow(false)
    /** Лоли думает над ответом — для индикатора в чате. */
    val busy: StateFlow<Boolean> = _busy

    /** Последний ответ: подпись «AI» / «на компьютере» и причина, если AI не ответил. */
    private val _lastReply = MutableStateFlow<AssistantReply?>(null)
    val lastReply: StateFlow<AssistantReply?> = _lastReply

    private val _error = MutableStateFlow<String?>(null)
    /** Ответить не получилось — показываем в чате, а не молчим. */
    val error: StateFlow<String?> = _error
    fun dismissError() { _error.value = null }

    /** Отправить фразу Лоли (из поля ввода или голосом). */
    suspend fun send(text: String, source: InputSource = InputSource.TEXT): AssistantReply? {
        if (text.isBlank()) return null
        _busy.value = true
        _error.value = null
        return try {
            engine.handle(text, source).also { _lastReply.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Desktop", "Ошибка обработки", e)
            _error.value = "Не получилось ответить: ${e.message ?: e::class.simpleName}. Подробности — в журнале (Настройки → О программе)."
            null
        } finally {
            _busy.value = false
        }
    }

    /** Очистить историю чата на этом компьютере (записи, задачи и траты остаются). */
    suspend fun clearChat() {
        store.conversations.clear()
        _lastReply.value = null
    }

    /**
     * Быстрое добавление с вкладок: фраза → одно действие → запись. Чат не трогается.
     * Возвращает (успех, текст для пользователя).
     */
    suspend fun quickAdd(kind: QuickAdd.Kind, text: String): Pair<Boolean, String> = try {
        when (val plan = QuickAdd.plan(kind, text, time.now(), time.zone())) {
            is QuickAdd.Result.Hint -> false to plan.text
            is QuickAdd.Result.Ok -> {
                val result = executor.execute(listOf(plan.action), ConversationContext(time))
                val message = result.outcomes.joinToString(" ") { it.text }.ifBlank { "Готово." }
                !result.hasErrors to message
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e("Desktop", "Быстрое добавление не удалось", e)
        false to "Не получилось сохранить: ${e.message ?: e::class.simpleName}"
    }

    val voice = VoiceController(this)

    /** Сработавшие напоминания на экране (окно поверх всех программ). */
    val alerts = ReminderAlerts(this)

    /** Резервная копия — тот же формат, что на телефоне: копию с телефона можно восстановить здесь и наоборот. */
    val backup = BackupService(store, DesktopBackupExtras(settings), System.getProperty("jpackage.app-version") ?: "dev")

    val updates = UpdateChecker(http)

    /** Выход из программы: остановить голос и фоновые задачи, закрыть сеть и базу. */
    fun close() {
        runCatching { voice.shutdown() }
        runCatching { settings.flush() }
        scope.cancel()
        runCatching { http.close() }
        runCatching { driver.close() }
    }

    companion object {
        /** %APPDATA%\Loli на Windows, ~/.loli на остальных системах. */
        fun defaultDataDir(): File {
            val appData = System.getenv("APPDATA")
            val dir = if (!appData.isNullOrBlank()) File(appData, "Loli") else File(System.getProperty("user.home"), ".loli")
            return dir.apply { mkdirs() }
        }
    }
}
