package ai.loli.app

import android.content.Context
import ai.loli.app.data.DatabaseFactory
import ai.loli.app.device.AndroidDeviceController
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
import ai.loli.core.ai.AIProviderType
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

    /** БД открывается лениво и заранее прогревается в фоне (ключ SQLCipher — тяжёлая операция). */
    val store: LocalStore by lazy { LocalStore(DatabaseFactory.create(context, secrets), time) }

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
    val syncEngine: SyncEngine by lazy { SyncEngine(
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
    ) }
    val network = NetworkMonitor(context) { if (auth.state.value is AuthState.SignedIn) syncScheduler.requestSoon(1) }

    // --- AI ---
    /** Все включённые провайдеры в порядке приоритета (ключи — из Keystore). */
    fun aiConfigs(): List<AIConfig> {
        val s = settings.settings.value
        return s.activeProviders.map { p -> aiConfig(p.type) }
    }

    fun aiConfig(type: AIProviderType): AIConfig {
        val p = settings.settings.value.provider(type)
        return AIConfig(
            type = type,
            endpoint = p.endpoint,
            model = p.model,
            apiKey = secrets.get(KeystoreSecretStore.aiKey(type.id)).orEmpty(),
            embeddingsEnabled = settings.settings.value.embeddingsEnabled,
        )
    }

    /** Списки моделей, загруженные с серверов провайдеров (на время работы приложения). */
    val modelCache = mutableMapOf<AIProviderType, List<ai.loli.core.ai.ModelInfo>>()

    fun aiConfigured(): Boolean = aiConfigs().any { it.isComplete }

    private fun aiProvider(): AIProvider = AIProviderFactory.createChain(http, aiConfigs())
    private fun embeddingProvider(): EmbeddingProvider? =
        if (network.online.value && settings.settings.value.useAI) AIProviderFactory.createEmbeddings(http, aiConfigs()) else null

    // --- Ассистент ---
    val reminderScheduler = AlarmReminderScheduler(context)
    val search: SearchService by lazy { SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { embeddingProvider() } }
    private val resolver by lazy { TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories) }
    /** Команды телефону (таймер, будильник, приложения, звонки…); запасной таймер — напоминание Лоли. */
    val launcher = ai.loli.app.device.BackgroundLauncher(context)
    val systemAccess = ai.loli.app.device.SystemAccess(context)
    val updates = ai.loli.app.update.UpdateManager(context)
    val appLock = ai.loli.app.security.AppLock(context)
    val appAccess = ai.loli.app.device.AppAccess(context) { settings.settings.value }
    val permissions = ai.loli.app.device.PermissionBroker(context)
    /** Свои таймеры и напоминания по месту. */
    val timers = ai.loli.app.reminders.LoliTimers(context)
    val geo = ai.loli.app.reminders.GeoReminders(context)
    val device = AndroidDeviceController(context, launcher, appAccess, permissions, fallbackReminder = { text, at ->
        val r = store.reminders.create(text, at, null, time.zone().id)
        reminderScheduler.schedule(r)
    }, timers = timers)
    /** Погода, курсы, новости, сообщения, экран, радио, игры, сказки… */
    val skillHost = ai.loli.app.device.AndroidSkillHost(
        context, permissions, launcher, timers, geo,
        onUserName = { n -> appScope.launch { settings.setUserName(n.orEmpty()) } },
        onCity = { c -> appScope.launch { settings.setCity(c.orEmpty()) } },
    )
    val skills = ai.loli.core.skills.Skills(skillHost, http, time)
    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)

    /** Телефон заблокирован (экран блокировки показан). */
    fun isLocked(): Boolean = keyguard?.isKeyguardLocked == true

    /** Правила для заблокированного экрана; если пользователь запретил Лоли на блокировке — всё закрыто. */
    /** Включён «Вход по отпечатку», а приложение ещё не разблокировано — чужой с открытым телефоном не должен видеть записи. */
    fun appLocked(): Boolean {
        val s = settings.settings.value
        return s.appLock && !appLock.unlocked.value && appLock.deviceSecure()
    }

    fun lockPolicy(): ai.loli.core.assistant.LockPolicy? {
        // Телефон разблокирован, но Лоли под паролем: записывать и управлять телефоном можно, смотреть и менять записи — нет.
        if (!isLocked() && appLocked()) return ai.loli.core.assistant.LockPolicy(create = true, basicDevice = true, calls = true, view = false, edit = false, apps = true)
        if (!isLocked()) return null
        val s = settings.settings.value
        return if (s.lockScreenEnabled) s.lockPolicy
        else ai.loli.core.assistant.LockPolicy(create = false, basicDevice = false, calls = false, view = false, edit = false, apps = false)
    }

    val executor: ai.loli.core.assistant.ActionExecutor by lazy {
        ActionExecutor(
            store.notes, store.expenses, store.tasks, store.reminders, store.memories, search, resolver, reminderScheduler, time, device,
            lockPolicy = { lockPolicy() }, shopping = store.shopping, routines = store.routines, secrets = store.secrets,
            agendaExtras = { date -> skills.agendaExtras(date, assistantSettings()) },
        )
    }

    val engine: AssistantEngine by lazy { AssistantEngine(
        notes = store.notes, tasks = store.tasks, reminders = store.reminders, memories = store.memories,
        conversations = store.conversations, search = search, executor = executor, time = time,
        settings = { assistantSettings() },
        aiProvider = { aiProvider() },
        routines = { store.routines.all() }, shoppingItems = { store.shopping.all() },
        skills = skills,
    ) }

    fun assistantSettings(): AssistantSettings = settings.settings.value.let {
        AssistantSettings(
            it.assistantName, it.useAI, it.dialogModeEnabled, locked = isLocked() || appLocked(),
            userName = it.userName.takeIf { n -> n.isNotBlank() }, city = it.city.takeIf { c -> c.isNotBlank() },
        )
    }

    // --- Голос ---
    val voskModels = VoskModelManager(context)
    val voskEngine = VoskEngine(voskModels)
    val systemStt = AndroidSpeechRecognizerProvider(context)
    val offlineStt = VoskSpeechProvider(voskEngine, voskModels)
    /** Синтезатор речи подключается только когда понадобится (не при запуске из будильника/синхронизации). */
    private val ttsLazy = lazy { AndroidTtsProvider(
            context,
            rate = { settings.settings.value.speechRate },
            pitch = { settings.settings.value.speechPitch },
            voiceName = { settings.settings.value.voiceName },
            engineName = { settings.settings.value.ttsEngine },
            autoEngine = { settings.settings.value.ttsAutoEngine },
            saveAutoEngine = { settings.setTtsAutoEngine(it) },
        ) }
    val tts: AndroidTtsProvider get() = ttsLazy.value
    /** Встроенный «Голос Лоли»: скачиваемые русские голоса и офлайн-синтез. */
    val loliVoiceModels = ai.loli.app.voice.LoliVoiceModels(context)
    val loliVoice = ai.loli.app.voice.LoliVoice(
        loliVoiceModels,
        voiceId = { settings.settings.value.loliVoice },
        rate = { settings.settings.value.speechRate },
        pitch = { settings.settings.value.speechPitch },
    )
    /** Кто озвучивает ответы: встроенный голос или проверенный синтезатор телефона. */
    val speech = ai.loli.app.voice.SpeechOutput(
        system = { tts }, systemCreated = { ttsLazy.isInitialized() }, loli = loliVoice,
        mode = {
            when (settings.settings.value.voiceMode) {
                "loli" -> ai.loli.app.voice.SpeechOutput.Mode.LOLI
                "system" -> ai.loli.app.voice.SpeechOutput.Mode.SYSTEM
                else -> ai.loli.app.voice.SpeechOutput.Mode.AUTO
            }
        },
    )
    val voice: VoiceController by lazy {
        VoiceController(engine, settings.settings, systemStt, offlineStt, speech, appScope).also { v ->
            val stopWords = ai.loli.app.voice.StopWordWatcher(context, voskEngine, voskModels)
            v.stopWatcher = { stopWords.awaitStop() }
            v.understands = { engine.understandsLocally(it) }
            v.onVoiceReply = { reply ->
                // Экран заблокирован или приложение свёрнуто — результат придёт уведомлением.
                if (reply.text.isNotBlank() && (isLocked() || !launcher.isForeground())) {
                    ai.loli.app.reminders.Notifications.showResult(context, reply.text)
                }
            }
            v.systemDialogAvailable = {
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
            }
        }
    }

    /** Завершается, когда сессия и настройки загружены (важно для холодного старта из WorkManager/Receiver). */
    private val ready = CompletableDeferred<Unit>()
    suspend fun awaitReady() = ready.await()
    private val _started = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Сессия восстановлена — можно показывать интерфейс (без мелькания экрана входа). */
    val started: kotlinx.coroutines.flow.StateFlow<Boolean> = _started

    init {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Прогрев БД вне главного потока; дальше — подписка на изменения для синхронизации.
            store.changes.addListener { if (auth.state.value is AuthState.SignedIn) syncScheduler.requestSoon() }
            // Виджеты «Задачи на сегодня» и «Покупки» обновляются сразу после изменений.
            store.changes.addListener { table ->
                if (table == "tasks" || table == "shopping_items") runCatching { ai.loli.app.widget.ListWidget.refreshAll(context) }
            }
        }
        appScope.launch {
            auth.restore()
            if (settings.current().localOnly && auth.state.value !is AuthState.SignedIn) auth.useLocalOnly()
            ready.complete(Unit)
            _started.value = true
            updates.schedulePeriodic()
            ai.loli.app.reminders.MorningBrief.schedule(context, settings.current().morningBrief)
            if (auth.state.value is AuthState.SignedIn) {
                syncScheduler.schedulePeriodic()
                syncScheduler.requestSoon(1)
            }
        }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { rescheduleReminders() }
    }

    suspend fun rescheduleReminders() {
        runCatching { timers.rescheduleAll() }
        runCatching { geo.registerAll() }
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
