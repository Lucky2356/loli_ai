package ai.loli.app.security

import ai.loli.core.auth.AuthSession
import ai.loli.core.auth.SessionStore
import ai.loli.core.data.LoliJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сессия Supabase хранится зашифрованной ключом Android Keystore. */
class KeystoreSessionStore(private val secrets: KeystoreSecretStore) : SessionStore {
    override suspend fun load(): AuthSession? = withContext(Dispatchers.IO) {
        secrets.get(KeystoreSecretStore.SESSION)?.let { runCatching { LoliJson.decodeFromString(AuthSession.serializer(), it) }.getOrNull() }
    }

    override suspend fun save(session: AuthSession?) = withContext(Dispatchers.IO) {
        secrets.put(KeystoreSecretStore.SESSION, session?.let { LoliJson.encodeToString(AuthSession.serializer(), it) })
    }
}
