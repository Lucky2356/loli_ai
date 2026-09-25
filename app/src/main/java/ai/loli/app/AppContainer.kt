package ai.loli.app

import android.content.Context
import ai.loli.app.data.DatabaseFactory
import ai.loli.app.reminders.AlarmReminderScheduler
import ai.loli.app.security.KeystoreSecretStore
import ai.loli.app.security.KeystoreSessionStore
import ai.loli.app.settings.SettingsRepository
import ai.loli.app.sync.NetworkMonitor
import ai.loli.app.sync.SyncScheduler
import ai.loli.app.voice.AndroidSpeechRecognizerProvider
import ai.loli.app.voice.AndroidTtsProvider
import ai.loli.app.voice.VoiceController
import ai.loli.app.voice.VoskEngine
import ai.loli.app.voice.VoskModelManager
import ai.loli.app.voice.VoskSpeechProvider
import ai.loli.core.ai.AIConfig
import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIProviderFactory
import ai.loli.core.ai.EmbeddingProvider
import ai.loli.core.assistant.ActionExecutor
import ai.loli.core.assistant.AssistantEngine
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.TargetResolver
import ai.loli.core.auth.AuthManager
import ai.loli.core.auth.AuthState
import ai.loli.core.auth.SupabaseAuthClient
import ai.loli.core.data.LocalStore
import ai.loli.core.remote.PostgrestRemote
import ai.loli.core.remote.SupabaseConfig
import ai.loli.core.search.SearchService
import ai.loli.core.sync.SyncEngine
import ai.loli.core.util.SystemTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ручное внедрение зависимостей: один объект собирает все слои.
 * UI → VoiceController/ViewModel → AssistantEngine (core) → репозитории (core) → SQLCipher / Supabase.
 */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val time = SystemTimeSource()

    val secrets = KeystoreSecretStore(context)
    val settings = SettingsRepository(context, appScope)

    val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 90_000
        }
    }

    val store = LocalStore(DatabaseFactory.create(context, secrets), time)

    // --- Supabase ---
    fun supabaseConfig(): SupabaseConfig {
        val s = settings.settings.value
        val url = s.supabaseUrlOverride.ifBlank { BuildConfig.SUPABASE_URL }
        val key = secrets.get(KeystoreSecretStore.SUPABASE_ANON_OVERRIDE)?.takeIf { s.supabaseUrlOverride.isNotBlank() } ?: BuildConfig.SUPABASE_ANON_KEY
        return SupabaseConfig(url, key)
    }

    val auth = AuthManager(
        clientProvider = { supabaseConfig().takeIf { it.isConfigured }?.let { SupabaseAuthClient(http, it) } },
        store = KeystoreSessionStore(secrets),
        time = time,
    )

    val syncScheduler = SyncScheduler(context)
    val syncEngine = SyncEngine(
        local = store,
        remote = object : ai.loli.core.remote.RemoteDataSource {
            // Конфигурация может меняться в настройках — создаём клиент на каждый вызов.
            private fun remote() = PostgrestRemote(http, supabaseConfig()) { force -> auth.accessToken(force) }
            override suspend fun upsert(table: String, rows: List<kotlinx.serialization.json.JsonObject>) = remote().upsert(table, rows)
            override suspend fun fetchChanges(table: String, since: java.time.Instant?, limit: Int) = remote().fetchChanges(table, since, limit)
            override suspend fun fetchSingle(table: String) = remote().fetchSingle(table)
        },
        profile = settings,
        userId = { auth.userId },
    )
    val network = NetworkMonitor(context) { if (auth.state.value is AuthState.SignedIn) syncScheduler.requestSoon(1) }

    // --- AI ---
    fun aiConfig(): AIConfig {
        val s = settings.settings.value
        return AIConfig(
            type = s.aiProvider,
            endpoint = s.aiEndpoint,
            model = s.aiModel,
            apiKey = secrets.get(KeystoreSecretStore.aiKey(s.aiProvider.id)).orEmpty(),
            embeddingsEnabled = s.embeddingsEnabled,
        )
    }

    private fun aiProvider(): AIProvider? = aiConfig().takeIf { it.isComplete }?.let { AIProviderFactory.create(http, it) }
    private fun embeddingProvider(): EmbeddingProvider? =
        if (network.online.value) AIProviderFactory.createEmbeddings(http, aiConfig()) else null

    // --- Ассистент ---
    val reminderScheduler = AlarmReminderScheduler(context)
    val search = SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { embeddingProvider() }
    private val resolver = TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories)
    private val executor = ActionExecutor(store.notes, store.expenses, store.tasks, store.reminders, store.memories, search, resolver, reminderScheduler, time)

    val engine = AssistantEngine(
        notes = store.notes, tasks = store.tasks, reminders = store.reminders, memories = store.memories,
        conversations = store.conversations, search = search, executor = executor, time = time,
        settings = { settings.settings.value.let { AssistantSettings(it.assistantName, it.useAI) } },
        aiProvider = { aiProvider() },
    )

    // --- Голос ---
    val voskModels = VoskModelManager(context)
    val voskEngine = VoskEngine(voskModels)
    val systemStt = AndroidSpeechRecognizerProvider(context)
    val offlineStt = VoskSpeechProvider(voskEngine, voskModels)
    val tts = AndroidTtsProvider(context) { settings.settings.value.speechRate }
    val voice = VoiceController(engine, settings.settings, systemStt, offlineStt, tts, appScope)

    /** Завершается, когда сессия и настройки загружены (важно для холодного старта из WorkManager/Receiver). */
    private val ready = CompletableDeferred<Unit>()
    suspend fun awaitReady() = ready.await()

    init {
        // Любое локальное изменение → синхронизация вскоре (если пользователь вошёл).
        store.changes.addListener { if (auth.state.value is AuthState.SignedIn) syncScheduler.requestSoon() }
        appScope.launch {
            auth.restore()
            if (settings.current().localOnly && auth.state.value !is AuthState.SignedIn) auth.useLocalOnly()
            ready.complete(Unit)
            if (auth.state.value is AuthState.SignedIn) {
                syncScheduler.schedulePeriodic()
                syncScheduler.requestSoon(1)
            }
        }
        appScope.launch { rescheduleReminders() }
    }

    suspend fun rescheduleReminders() {
        reminderScheduler.rescheduleAll(store.reminders.active())
    }

    /** Выход: отправляем несинхронизированное, затем стираем локальные данные этого аккаунта. */
    suspend fun signOut(): Boolean {
        if (store.pendingChanges() > 0) {
            val report = syncEngine.sync()
            if (!report.ok) return false
        }
        store.reminders.active().forEach { reminderScheduler.cancel(it.id) }
        syncScheduler.cancelAll()
        auth.signOut()
        store.wipe()
        engine.context.reset()
        return true
    }

    suspend fun forceSignOutDiscardingLocal() {
        store.reminders.active().forEach { reminderScheduler.cancel(it.id) }
        syncScheduler.cancelAll()
        auth.signOut()
        store.wipe()
        engine.context.reset()
    }

    fun onSignedIn() {
        syncScheduler.schedulePeriodic()
        syncScheduler.requestSoon(0)
    }
}
