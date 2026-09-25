package ai.loli.core.auth

import ai.loli.core.data.LoliJson
import ai.loli.core.remote.SupabaseConfig
import ai.loli.core.util.Logger
import ai.loli.core.util.TimeSource
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/** Сессия Supabase Auth. Хранится только в защищённом хранилище; в toString() токены маскируются. */
@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    /** Момент истечения access token, epoch seconds. */
    val expiresAt: Long,
    val userId: String,
    val email: String,
) {
    override fun toString() = "AuthSession(userId=$userId, email=$email, expiresAt=$expiresAt, tokens=***)"
}

/** Хранилище сессии (на Android — шифрование ключом из Android Keystore). */
interface SessionStore {
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession?)
}

sealed class AuthException(message: String) : Exception(message) {
    class NotConfigured : AuthException("Сервер синхронизации не настроен (Supabase URL и ключ).")
    class InvalidCredentials : AuthException("Неверный email или пароль.")
    class EmailNotConfirmed : AuthException("Email не подтверждён. Откройте письмо от Supabase и перейдите по ссылке.")
    class UserExists : AuthException("Пользователь с таким email уже зарегистрирован.")
    class WeakPassword(detail: String) : AuthException("Слишком простой пароль: $detail")
    class InvalidEmail : AuthException("Некорректный email.")
    class RateLimited : AuthException("Слишком много попыток. Подождите немного.")
    class SessionExpired : AuthException("Сессия истекла — войдите снова.")
    class Network : AuthException("Нет связи с сервером. Проверьте интернет.")
    class Server(detail: String) : AuthException("Ошибка сервера авторизации: $detail")
}

sealed interface SignUpResult {
    data class SignedIn(val session: AuthSession) : SignUpResult
    /** Включено подтверждение email: сессии пока нет. */
    data class ConfirmationRequired(val email: String) : SignUpResult
}

/**
 * Клиент Supabase Auth (GoTrue REST API). Пароли не хранятся в приложении — только сессия (токены).
 * OAuth-провайдеры (Google и др.) подключаются тем же API через PKCE-поток — см. README, раздел Roadmap.
 */
class SupabaseAuthClient(private val http: HttpClient, private val config: SupabaseConfig) {

    suspend fun signUp(email: String, password: String): SignUpResult {
        val json = call("signup", null, credentials(email, password))
        return if (json["access_token"] != null) SignUpResult.SignedIn(parseSession(json))
        else SignUpResult.ConfirmationRequired(email.trim())
    }

    suspend fun signIn(email: String, password: String): AuthSession =
        parseSession(call("token", "password", credentials(email, password)))

    suspend fun refresh(refreshToken: String): AuthSession =
        parseSession(call("token", "refresh_token", buildJsonObject { put("refresh_token", refreshToken) }))

    suspend fun signOut(accessToken: String) {
        runCatching {
            http.post("${config.url}/auth/v1/logout") {
                header("apikey", config.anonKey)
                header("Authorization", "Bearer $accessToken")
            }
        }
    }

    suspend fun resetPassword(email: String) {
        call("recover", null, buildJsonObject { put("email", email.trim()) })
    }

    private fun credentials(email: String, password: String) = buildJsonObject {
        put("email", email.trim())
        put("password", password)
    }

    private suspend fun call(path: String, grantType: String?, body: JsonObject): JsonObject {
        if (!config.isConfigured) throw AuthException.NotConfigured()
        val response: HttpResponse = try {
            http.post("${config.url}/auth/v1/$path") {
                if (grantType != null) parameter("grant_type", grantType)
                header("apikey", config.anonKey)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AuthException.Network()
        } catch (e: Exception) {
            if (e::class.simpleName.orEmpty().contains("Timeout")) throw AuthException.Network()
            throw AuthException.Server(e::class.simpleName ?: "error")
        }
        val text = response.bodyAsText()
        val json = runCatching { LoliJson.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (response.status.value in 200..299) return json
        throw mapError(response.status.value, json)
    }

    private fun mapError(status: Int, json: JsonObject): AuthException {
        val code = (json["error_code"] ?: json["code"])?.jsonPrimitive?.contentOrNull.orEmpty()
        val msg = (json["msg"] ?: json["error_description"] ?: json["message"] ?: json["error"])?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            status == 429 || code == "over_request_rate_limit" || code == "over_email_send_rate_limit" -> AuthException.RateLimited()
            code == "invalid_credentials" || msg.contains("Invalid login credentials", true) -> AuthException.InvalidCredentials()
            code == "email_not_confirmed" || msg.contains("not confirmed", true) -> AuthException.EmailNotConfirmed()
            code == "user_already_exists" || code == "email_exists" || msg.contains("already registered", true) -> AuthException.UserExists()
            code == "weak_password" || msg.contains("Password should", true) -> AuthException.WeakPassword(msg)
            code == "email_address_invalid" || code == "validation_failed" -> AuthException.InvalidEmail()
            code == "refresh_token_not_found" || code == "refresh_token_already_used" || code == "session_not_found" -> AuthException.SessionExpired()
            status == 400 && msg.contains("refresh", true) -> AuthException.SessionExpired()
            else -> AuthException.Server("${status}${if (msg.isNotBlank()) ": ${msg.take(200)}" else ""}")
        }
    }

    private fun parseSession(json: JsonObject): AuthSession {
        val user = json["user"]?.jsonObject ?: throw AuthException.Server("нет данных пользователя")
        val expiresAt = json["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() / 1000 + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
        return AuthSession(
            accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull ?: throw AuthException.Server("нет access_token"),
            refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: throw AuthException.Server("нет refresh_token"),
            expiresAt = expiresAt,
            userId = user["id"]?.jsonPrimitive?.contentOrNull ?: throw AuthException.Server("нет id пользователя"),
            email = user["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}

sealed interface AuthState {
    /** Сервер не настроен или пользователь выбрал работу без аккаунта: данные только на устройстве. */
    data object LocalOnly : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String) : AuthState
}

/**
 * Управляет сессией: восстановление, вход/регистрация/выход и автоматическое обновление access token.
 */
class AuthManager(
    private val clientProvider: () -> SupabaseAuthClient?,
    private val store: SessionStore,
    private val time: TimeSource,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var session: AuthSession? = null

    val userId: String? get() = session?.userId

    suspend fun restore() {
        session = store.load()
        _state.value = session?.let { AuthState.SignedIn(it.userId, it.email) }
            ?: if (clientProvider() == null) AuthState.LocalOnly else AuthState.SignedOut
    }

    suspend fun signIn(email: String, password: String) {
        val client = clientProvider() ?: throw AuthException.NotConfigured()
        setSession(client.signIn(email, password))
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        val client = clientProvider() ?: throw AuthException.NotConfigured()
        val result = client.signUp(email, password)
        if (result is SignUpResult.SignedIn) setSession(result.session)
        return result
    }

    suspend fun resetPassword(email: String) {
        (clientProvider() ?: throw AuthException.NotConfigured()).resetPassword(email)
    }

    suspend fun signOut() {
        val current = session
        setSession(null)
        if (current != null) clientProvider()?.signOut(current.accessToken)
    }

    /** Работа без аккаунта (локально). */
    fun useLocalOnly() { _state.value = AuthState.LocalOnly }

    /**
     * Актуальный access token: обновляется заранее, за минуту до истечения.
     * null — пользователь не вошёл. [forceRefresh] — после ответа 401 от сервера.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = mutex.withLock {
        val current = session ?: return@withLock null
        val nowSec = time.now().epochSecond
        if (!forceRefresh && current.expiresAt - nowSec > 60) return@withLock current.accessToken
        val client = clientProvider() ?: return@withLock null
        try {
            val refreshed = client.refresh(current.refreshToken)
            session = refreshed
            store.save(refreshed)
            refreshed.accessToken
        } catch (e: AuthException.SessionExpired) {
            Logger.w(TAG, "Сессия истекла, требуется повторный вход")
            session = null
            store.save(null)
            _state.value = AuthState.SignedOut
            null
        }
    }

    private suspend fun setSession(s: AuthSession?) {
        session = s
        store.save(s)
        _state.value = s?.let { AuthState.SignedIn(it.userId, it.email) }
            ?: if (clientProvider() == null) AuthState.LocalOnly else AuthState.SignedOut
    }

    companion object { private const val TAG = "Auth" }
}
