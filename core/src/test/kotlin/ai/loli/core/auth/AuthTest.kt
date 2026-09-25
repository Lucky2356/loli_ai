package ai.loli.core.auth

import ai.loli.core.remote.PostgrestRemote
import ai.loli.core.remote.RemoteException
import ai.loli.core.remote.SupabaseConfig
import ai.loli.core.util.FixedTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {
    private val config = SupabaseConfig("https://demo.supabase.co", "anon-key")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val time = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"))

    private fun sessionJson(token: String, expiresAt: Long) =
        """{"access_token":"$token","refresh_token":"r-$token","expires_at":$expiresAt,"user":{"id":"uid-1","email":"a@b.c"}}"""

    private class MemoryStore : SessionStore {
        var value: AuthSession? = null
        override suspend fun load() = value
        override suspend fun save(session: AuthSession?) { value = session }
    }

    @Test fun signInSendsPasswordGrantAndStoresSession() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req ->
            requests += req
            respond(sessionJson("tok1", time.now().epochSecond + 3600), HttpStatusCode.OK, json)
        })
        val store = MemoryStore()
        val auth = AuthManager({ SupabaseAuthClient(http, config) }, store, time)
        auth.signIn("a@b.c", "secret-pass")
        val req = requests.single()
        assertEquals("/auth/v1/token", req.url.encodedPath)
        assertEquals("password", req.url.parameters["grant_type"])
        assertEquals("anon-key", req.headers["apikey"])
        assertTrue((req.body as TextContent).text.contains("\"email\":\"a@b.c\""))
        assertEquals("tok1", store.value?.accessToken)
        assertIs<AuthState.SignedIn>(auth.state.value)
        assertTrue(!store.value.toString().contains("tok1"), "токены не должны попадать в toString")
    }

    @Test fun invalidCredentialsMapped() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""", HttpStatusCode.BadRequest, json)
        })
        assertFailsWith<AuthException.InvalidCredentials> { SupabaseAuthClient(http, config).signIn("a@b.c", "bad") }
    }

    @Test fun signUpWithEmailConfirmation() = runTest {
        val http = HttpClient(MockEngine { respond("""{"id":"uid-1","email":"a@b.c"}""", HttpStatusCode.OK, json) })
        val result = SupabaseAuthClient(http, config).signUp("a@b.c", "long-password")
        assertIs<SignUpResult.ConfirmationRequired>(result)
    }

    @Test fun userExistsMapped() = runTest {
        val http = HttpClient(MockEngine { respond("""{"error_code":"user_already_exists","msg":"User already registered"}""", HttpStatusCode.UnprocessableEntity, json) })
        assertFailsWith<AuthException.UserExists> { SupabaseAuthClient(http, config).signUp("a@b.c", "long-password") }
    }

    @Test fun networkErrorMapped() = runTest {
        val http = HttpClient(MockEngine { throw IOException("no route") })
        assertFailsWith<AuthException.Network> { SupabaseAuthClient(http, config).signIn("a@b.c", "x") }
    }

    @Test fun notConfiguredWithoutUrl() = runTest {
        val http = HttpClient(MockEngine { error("не должно быть запросов") })
        assertFailsWith<AuthException.NotConfigured> { SupabaseAuthClient(http, SupabaseConfig("", "")).signIn("a", "b") }
    }

    @Test fun expiringTokenIsRefreshedAndExpiredSessionSignsOut() = runTest {
        var refreshCalls = 0
        var refreshFails = false
        val http = HttpClient(MockEngine { req ->
            assertEquals("refresh_token", req.url.parameters["grant_type"])
            refreshCalls++
            if (refreshFails) respond("""{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""", HttpStatusCode.BadRequest, json)
            else respond(sessionJson("tok2", time.now().epochSecond + 3600), HttpStatusCode.OK, json)
        })
        val store = MemoryStore().apply { value = AuthSession("tok1", "r1", time.now().epochSecond + 30, "uid-1", "a@b.c") }
        val auth = AuthManager({ SupabaseAuthClient(http, config) }, store, time)
        auth.restore()
        assertEquals("tok2", auth.accessToken())
        assertEquals(1, refreshCalls)
        assertEquals("tok2", auth.accessToken(), "свежий токен не обновляется повторно")
        assertEquals(1, refreshCalls)

        refreshFails = true
        assertNull(auth.accessToken(forceRefresh = true))
        assertIs<AuthState.SignedOut>(auth.state.value)
        assertNull(store.value)
    }

    @Test fun postgrestRetriesOnceAfter401WithFreshToken() = runTest {
        val tokens = mutableListOf<String?>()
        var calls = 0
        val http = HttpClient(MockEngine { req ->
            calls++
            tokens += req.headers["Authorization"]
            assertEquals("gt.2026-09-25T08:58:00Z", req.url.parameters["server_updated_at"])
            if (calls == 1) respond("{}", HttpStatusCode.Unauthorized, json) else respond("[]", HttpStatusCode.OK, json)
        })
        val remote = PostgrestRemote(http, config) { force -> if (force) "fresh" else "stale" }
        remote.fetchChanges("notes", Instant.parse("2026-09-25T08:58:00Z"), 500)
        assertEquals(listOf<String?>("Bearer stale", "Bearer fresh"), tokens)
    }

    @Test fun postgrestUpsertUsesMergeDuplicates() = runTest {
        var prefer: String? = null
        var onConflict: String? = null
        val http = HttpClient(MockEngine { req ->
            prefer = req.headers["Prefer"]; onConflict = req.url.parameters["on_conflict"]
            respond("", HttpStatusCode.Created)
        })
        PostgrestRemote(http, config) { "t" }.upsert("notes", listOf(kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals("resolution=merge-duplicates,return=minimal", prefer)
        assertEquals("id", onConflict)
    }

    @Test fun postgrestOfflineMapped() = runTest {
        val http = HttpClient(MockEngine { throw IOException("offline") })
        assertFailsWith<RemoteException.Offline> { PostgrestRemote(http, config) { "t" }.fetchChanges("notes", null, 10) }
    }
}
