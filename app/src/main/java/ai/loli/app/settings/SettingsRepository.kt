package ai.loli.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/** Несекретные настройки. Секреты (API-ключи, токены) — только в KeystoreSecretStore. */
data class AppSettings(
    val assistantName: String = "Лоли",
    val aiProvider: AIProviderType = AIProviderType.OPENAI,
    val aiModel: String = "",
    val aiEndpoint: String = "",
    /** Облачный AI выключен по умолчанию: команды понимаются локально, без интернета. */
    val useAI: Boolean = false,
    val embeddingsEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val speechRate: Float = 1.0f,
    val wakeWordEnabled: Boolean = false,
    val dialogModeEnabled: Boolean = true,
    val preferOfflineStt: Boolean = false,
    val supabaseUrlOverride: String = "",
    val localOnly: Boolean = false,
    val onboardingDone: Boolean = false,
    val profileUpdatedAt: Long = 0,
    val profileDirty: Boolean = false,
) {
    val effectiveModel: String get() = aiModel.ifBlank { aiProvider.defaultModel }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "loli_settings")

class SettingsRepository(context: Context, scope: CoroutineScope) : ProfileSync {
    private val store = context.applicationContext.dataStore

    private object K {
        val name = stringPreferencesKey("assistant_name")
        val provider = stringPreferencesKey("ai_provider")
        val model = stringPreferencesKey("ai_model")
        val endpoint = stringPreferencesKey("ai_endpoint")
        val useAI = booleanPreferencesKey("use_ai")
        val embeddings = booleanPreferencesKey("embeddings")
        val tts = booleanPreferencesKey("tts")
        val rate = floatPreferencesKey("speech_rate")
        val wake = booleanPreferencesKey("wake_word")
        val dialog = booleanPreferencesKey("dialog_mode")
        val offlineStt = booleanPreferencesKey("offline_stt")
        val supabaseUrl = stringPreferencesKey("supabase_url")
        val localOnly = booleanPreferencesKey("local_only")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val profileUpdated = longPreferencesKey("profile_updated_at")
        val profileDirty = booleanPreferencesKey("profile_dirty")
    }

    val settings: StateFlow<AppSettings> = store.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Актуальные настройки прямо из хранилища (не ждёт первой эмиссии StateFlow). */
    suspend fun current(): AppSettings = store.data.first().toSettings()

    private fun Preferences.toSettings(): AppSettings {
        val p = this
        return AppSettings(
            assistantName = p[K.name]?.takeIf { it.isNotBlank() } ?: "Лоли",
            aiProvider = AIProviderType.fromId(p[K.provider]),
            aiModel = p[K.model].orEmpty(),
            aiEndpoint = p[K.endpoint].orEmpty(),
            useAI = p[K.useAI] ?: false,
            embeddingsEnabled = p[K.embeddings] ?: true,
            ttsEnabled = p[K.tts] ?: true,
            speechRate = p[K.rate] ?: 1.0f,
            wakeWordEnabled = p[K.wake] ?: false,
            dialogModeEnabled = p[K.dialog] ?: true,
            preferOfflineStt = p[K.offlineStt] ?: false,
            supabaseUrlOverride = p[K.supabaseUrl].orEmpty(),
            localOnly = p[K.localOnly] ?: false,
            onboardingDone = p[K.onboarding] ?: false,
            profileUpdatedAt = p[K.profileUpdated] ?: 0,
            profileDirty = p[K.profileDirty] ?: false,
        )
    }


    /** Изменение полей профиля (синхронизируются между устройствами). */
    suspend fun updateProfile(
        assistantName: String? = null,
        provider: AIProviderType? = null,
        model: String? = null,
        endpoint: String? = null,
    ) {
        store.edit { p ->
            assistantName?.let { p[K.name] = it.trim().take(40) }
            provider?.let { p[K.provider] = it.id }
            model?.let { p[K.model] = it.trim() }
            endpoint?.let { p[K.endpoint] = it.trim() }
            p[K.profileUpdated] = System.currentTimeMillis()
            p[K.profileDirty] = true
        }
    }

    suspend fun setUseAI(v: Boolean) = store.edit { it[K.useAI] = v }
    suspend fun setEmbeddings(v: Boolean) = store.edit { it[K.embeddings] = v }
    suspend fun setTts(v: Boolean) = store.edit { it[K.tts] = v }
    suspend fun setSpeechRate(v: Float) = store.edit { it[K.rate] = v.coerceIn(0.5f, 2f) }
    suspend fun setWakeWord(v: Boolean) = store.edit { it[K.wake] = v }
    suspend fun setDialogMode(v: Boolean) = store.edit { it[K.dialog] = v }
    suspend fun setPreferOfflineStt(v: Boolean) = store.edit { it[K.offlineStt] = v }
    suspend fun setSupabaseUrl(v: String) = store.edit { it[K.supabaseUrl] = v.trim() }
    suspend fun setLocalOnly(v: Boolean) = store.edit { it[K.localOnly] = v }
    suspend fun setOnboardingDone(v: Boolean) = store.edit { it[K.onboarding] = v }

    // --- ProfileSync: общий профиль для всех устройств пользователя (без API-ключей) ---

    override suspend fun local(): JsonObject? {
        val s = settings.value
        return buildJsonObject {
            put("assistant_name", s.assistantName)
            put("ai_provider", s.aiProvider.id)
            put("ai_model", s.aiModel)
            put("ai_endpoint", s.aiEndpoint)
            put("updated_at", Instant.ofEpochMilli(s.profileUpdatedAt.coerceAtLeast(1)).toString())
        }
    }

    override suspend fun localUpdatedAt(): Instant? = settings.value.profileUpdatedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
    override suspend fun isDirty(): Boolean = settings.value.profileDirty

    override suspend fun applyRemote(profile: JsonObject) {
        fun s(k: String) = (profile[k] as? JsonPrimitive)?.contentOrNull
        val updated = s("updated_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
        store.edit { p ->
            s("assistant_name")?.takeIf { it.isNotBlank() }?.let { p[K.name] = it }
            s("ai_provider")?.let { p[K.provider] = it }
            p[K.model] = s("ai_model").orEmpty()
            p[K.endpoint] = s("ai_endpoint").orEmpty()
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
