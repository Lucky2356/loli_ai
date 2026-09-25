package ai.loli.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.loli.core.ai.AIProviderType
import ai.loli.core.sync.ProfileSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/** Как распознавать речь. */
enum class SttMode(val id: String, val title: String, val hint: String) {
    AUTO("auto", "Автоматически", "Google, а без него — офлайн на устройстве"),
    SYSTEM("system", "Системное (Google)", "Лучшее качество, обычно нужен интернет"),
    OFFLINE("offline", "Офлайн (Vosk)", "Звук не покидает телефон, качество ниже");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

enum class ThemeMode(val id: String, val title: String) {
    SYSTEM("system", "Как в системе"), LIGHT("light", "Светлая"), DARK("dark", "Тёмная");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Настройки одного AI-провайдера. API-ключ хранится отдельно, в Keystore. */
data class ProviderSettings(val type: AIProviderType, val enabled: Boolean, val model: String, val endpoint: String) {
    val effectiveModel: String get() = model.ifBlank { type.defaultModel }
}

/** Несекретные настройки. Секреты (API-ключи, токены) — только в KeystoreSecretStore. */
data class AppSettings(
    val assistantName: String = "Лоли",
    /** Порядок провайдеров = приоритет: при ошибке первого запрос уходит следующему. */
    val aiOrder: List<AIProviderType> = AIProviderType.entries,
    val aiEnabled: Set<AIProviderType> = emptySet(),
    val aiModels: Map<AIProviderType, String> = emptyMap(),
    val aiEndpoints: Map<AIProviderType, String> = emptyMap(),
    /** Облачный AI выключен по умолчанию: команды понимаются локально, без интернета. */
    val useAI: Boolean = false,
    val embeddingsEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val speechRate: Float = 1.0f,
    val wakeWordEnabled: Boolean = false,
    val dialogModeEnabled: Boolean = true,
    val sttMode: SttMode = SttMode.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Сама скачивать и ставить новые версии. */
    val autoUpdate: Boolean = true,
    val supabaseUrlOverride: String = "",
    val localOnly: Boolean = false,
    val onboardingDone: Boolean = false,
    val profileUpdatedAt: Long = 0,
    val profileDirty: Boolean = false,
) {
    fun provider(type: AIProviderType) = ProviderSettings(type, type in aiEnabled, aiModels[type].orEmpty(), aiEndpoints[type].orEmpty())
    val providers: List<ProviderSettings> get() = aiOrder.map { provider(it) }
    val activeProviders: List<ProviderSettings> get() = providers.filter { it.enabled }
    /** Основной провайдер — первый включённый. */
    val primaryProvider: ProviderSettings? get() = activeProviders.firstOrNull()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "loli_settings")

class SettingsRepository(context: Context, scope: CoroutineScope) : ProfileSync {
    private val store = context.applicationContext.dataStore

    private object K {
        val name = stringPreferencesKey("assistant_name")
        // Устаревшие ключи одного провайдера — читаются для миграции.
        val legacyProvider = stringPreferencesKey("ai_provider")
        val legacyModel = stringPreferencesKey("ai_model")
        val legacyEndpoint = stringPreferencesKey("ai_endpoint")
        val aiOrder = stringPreferencesKey("ai_order")
        val aiEnabled = stringPreferencesKey("ai_enabled")
        fun model(t: AIProviderType) = stringPreferencesKey("ai_model_${t.id}")
        fun endpoint(t: AIProviderType) = stringPreferencesKey("ai_endpoint_${t.id}")
        val useAI = booleanPreferencesKey("use_ai")
        val embeddings = booleanPreferencesKey("embeddings")
        val tts = booleanPreferencesKey("tts")
        val rate = floatPreferencesKey("speech_rate")
        val wake = booleanPreferencesKey("wake_word")
        val dialog = booleanPreferencesKey("dialog_mode")
        val legacyOfflineStt = booleanPreferencesKey("offline_stt")
        val sttMode = stringPreferencesKey("stt_mode")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val supabaseUrl = stringPreferencesKey("supabase_url")
        val localOnly = booleanPreferencesKey("local_only")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val profileUpdated = longPreferencesKey("profile_updated_at")
        val profileDirty = booleanPreferencesKey("profile_dirty")
    }

    private val _loaded = MutableStateFlow(false)
    /** Настройки прочитаны с диска (до этого [settings] содержит значения по умолчанию). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val settings: StateFlow<AppSettings> = store.data.map { it.toSettings() }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Актуальные настройки прямо из хранилища (не ждёт первой эмиссии StateFlow). */
    suspend fun current(): AppSettings = store.data.first().toSettings()

    private fun Preferences.order(): List<AIProviderType> {
        val saved = this[K.aiOrder]?.split(',')?.mapNotNull { AIProviderType.fromIdOrNull(it) }.orEmpty()
        val legacy = AIProviderType.fromIdOrNull(this[K.legacyProvider])
        val head = saved.ifEmpty { listOfNotNull(legacy) }
        return (head + AIProviderType.entries).distinct()
    }

    private fun Preferences.enabled(): Set<AIProviderType> {
        this[K.aiEnabled]?.let { csv -> return csv.split(',').mapNotNull { AIProviderType.fromIdOrNull(it) }.toSet() }
        // Миграция с версии с одним провайдером: он остаётся включённым.
        return setOfNotNull(AIProviderType.fromIdOrNull(this[K.legacyProvider]))
    }

    private fun Preferences.toSettings(): AppSettings {
        val p = this
        val legacy = AIProviderType.fromIdOrNull(p[K.legacyProvider])
        val models = AIProviderType.entries.associateWith { t ->
            p[K.model(t)] ?: (if (t == legacy) p[K.legacyModel] else null).orEmpty()
        }.filterValues { it.isNotBlank() }
        val endpoints = AIProviderType.entries.associateWith { t ->
            p[K.endpoint(t)] ?: (if (t == legacy) p[K.legacyEndpoint] else null).orEmpty()
        }.filterValues { it.isNotBlank() }
        return AppSettings(
            assistantName = p[K.name]?.takeIf { it.isNotBlank() } ?: "Лоли",
            aiOrder = p.order(),
            aiEnabled = p.enabled(),
            aiModels = models,
            aiEndpoints = endpoints,
            useAI = p[K.useAI] ?: false,
            embeddingsEnabled = p[K.embeddings] ?: true,
            ttsEnabled = p[K.tts] ?: true,
            speechRate = p[K.rate] ?: 1.0f,
            wakeWordEnabled = p[K.wake] ?: false,
            dialogModeEnabled = p[K.dialog] ?: true,
            sttMode = SttMode.fromId(p[K.sttMode]) ?: if (p[K.legacyOfflineStt] == true) SttMode.OFFLINE else SttMode.AUTO,
            themeMode = ThemeMode.fromId(p[K.theme]),
            dynamicColor = p[K.dynamicColor] ?: false,
            autoUpdate = p[K.autoUpdate] ?: true,
            supabaseUrlOverride = p[K.supabaseUrl].orEmpty(),
            localOnly = p[K.localOnly] ?: false,
            onboardingDone = p[K.onboarding] ?: false,
            profileUpdatedAt = p[K.profileUpdated] ?: 0,
            profileDirty = p[K.profileDirty] ?: false,
        )
    }

    private fun MutablePreferences.touchProfile() {
        this[K.profileUpdated] = System.currentTimeMillis()
        this[K.profileDirty] = true
    }

    private fun MutablePreferences.saveOrder(order: List<AIProviderType>) {
        this[K.aiOrder] = order.joinToString(",") { it.id }
    }

    private fun MutablePreferences.saveEnabled(enabled: Set<AIProviderType>) {
        this[K.aiEnabled] = enabled.joinToString(",") { it.id }
    }

    suspend fun setAssistantName(name: String) {
        store.edit { p ->
            p[K.name] = name.trim().take(40)
            p.touchProfile()
        }
    }

    suspend fun setProviderEnabled(type: AIProviderType, enabled: Boolean) {
        store.edit { p ->
            val set = p.enabled().toMutableSet()
            if (enabled) set += type else set -= type
            p.saveEnabled(set)
            p.saveOrder(p.order())
            p.touchProfile()
        }
    }

    /** Поднять/опустить провайдера в списке приоритета. */
    suspend fun moveProvider(type: AIProviderType, delta: Int) {
        store.edit { p ->
            val order = p.order().toMutableList()
            val from = order.indexOf(type)
            val to = (from + delta).coerceIn(0, order.lastIndex)
            if (from < 0 || from == to) return@edit
            order.removeAt(from)
            order.add(to, type)
            p.saveOrder(order)
            p.saveEnabled(p.enabled())
            p.touchProfile()
        }
    }

    suspend fun setProviderConfig(type: AIProviderType, model: String, endpoint: String) {
        store.edit { p ->
            p[K.model(type)] = model.trim()
            p[K.endpoint(type)] = endpoint.trim()
            p.saveEnabled(p.enabled())
            p.touchProfile()
        }
    }

    suspend fun setUseAI(v: Boolean) = store.edit { it[K.useAI] = v }
    suspend fun setEmbeddings(v: Boolean) = store.edit { it[K.embeddings] = v }
    suspend fun setTts(v: Boolean) = store.edit { it[K.tts] = v }
    suspend fun setSpeechRate(v: Float) = store.edit { it[K.rate] = v.coerceIn(0.5f, 2f) }
    suspend fun setWakeWord(v: Boolean) = store.edit { it[K.wake] = v }
    suspend fun setDialogMode(v: Boolean) = store.edit { it[K.dialog] = v }
    suspend fun setSttMode(v: SttMode) = store.edit { it[K.sttMode] = v.id }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[K.theme] = v.id }
    suspend fun setDynamicColor(v: Boolean) = store.edit { it[K.dynamicColor] = v }
    suspend fun setAutoUpdate(v: Boolean) = store.edit { it[K.autoUpdate] = v }
    suspend fun setSupabaseUrl(v: String) = store.edit { it[K.supabaseUrl] = v.trim() }
    suspend fun setLocalOnly(v: Boolean) = store.edit { it[K.localOnly] = v }
    suspend fun setOnboardingDone(v: Boolean) = store.edit { it[K.onboarding] = v }

    // --- ProfileSync: общий профиль для всех устройств пользователя (без API-ключей) ---
    // В облаке хранится основной провайдер; остальные настраиваются на каждом устройстве.

    override suspend fun local(): JsonObject? {
        val s = current()
        val primary = s.primaryProvider ?: s.provider(s.aiOrder.first())
        return buildJsonObject {
            put("assistant_name", s.assistantName)
            put("ai_provider", primary.type.id)
            put("ai_model", primary.model)
            put("ai_endpoint", primary.endpoint)
            put("updated_at", Instant.ofEpochMilli(s.profileUpdatedAt.coerceAtLeast(1)).toString())
        }
    }

    override suspend fun localUpdatedAt(): Instant? = current().profileUpdatedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
    override suspend fun isDirty(): Boolean = current().profileDirty

    override suspend fun applyRemote(profile: JsonObject) {
        fun s(k: String) = (profile[k] as? JsonPrimitive)?.contentOrNull
        val updated = s("updated_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
        store.edit { p ->
            s("assistant_name")?.takeIf { it.isNotBlank() }?.let { p[K.name] = it }
            AIProviderType.fromIdOrNull(s("ai_provider"))?.let { type ->
                // Основной провайдер из профиля — первым в списке; остальные включённые остаются запасными.
                p.saveOrder(listOf(type) + p.order().filter { it != type })
                val enabled = p.enabled()
                p.saveEnabled(if (enabled.isEmpty()) enabled else enabled + type)
                p[K.model(type)] = s("ai_model").orEmpty()
                p[K.endpoint(type)] = s("ai_endpoint").orEmpty()
            }
            p[K.profileUpdated] = updated
            p[K.profileDirty] = false
        }
    }

    override suspend fun markSynced(updatedAt: Instant) {
        store.edit { p ->
            if ((p[K.profileUpdated] ?: 0) <= updatedAt.toEpochMilli()) p[K.profileDirty] = false
        }
    }
}
