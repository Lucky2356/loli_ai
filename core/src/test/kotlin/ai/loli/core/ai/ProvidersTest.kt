package ai.loli.core.ai

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvidersTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val req = AIRequest("system prompt", listOf(ChatMessage(ChatMessage.Role.USER, "привет")))

    @Test fun openAiRequestFormat() = runTest {
        var captured: HttpRequestData? = null
        val http = HttpClient(MockEngine { r ->
            captured = r
            respond("""{"model":"gpt-5-mini","choices":[{"message":{"content":"{\"reply\":\"ok\"}"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, json)
        })
        val provider = OpenAiCompatibleProvider(http, AIConfig(AIProviderType.OPENAI, null, null, "sk-test-123456789"))
        val res = provider.complete(req)
        assertEquals("{\"reply\":\"ok\"}", res.text)
        val r = captured!!
        assertEquals("https://api.openai.com/v1/chat/completions", r.url.toString())
        assertEquals("Bearer sk-test-123456789", r.headers["Authorization"])
        val body = (r.body as TextContent).text
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test fun geminiUsesOpenAiCompatibleEndpoint() = runTest {
        var url = ""
        val http = HttpClient(MockEngine { r -> url = r.url.toString(); respond("""{"choices":[{"message":{"content":"x"}}]}""", HttpStatusCode.OK, json) })
        OpenAiCompatibleProvider(http, AIConfig(AIProviderType.GEMINI, null, null, "AIza-key")).complete(req)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", url)
    }

    @Test fun anthropicRequestFormat() = runTest {
        var captured: HttpRequestData? = null
        val http = HttpClient(MockEngine { r ->
            captured = r
            respond("""{"model":"claude-opus-5","stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"reply\":\"ok\"}"}]}""", HttpStatusCode.OK, json)
        })
        val provider = AnthropicProvider(http, AIConfig(AIProviderType.ANTHROPIC, null, "claude-opus-5", "sk-ant-key-123456"))
        assertEquals("{\"reply\":\"ok\"}", provider.complete(req).text)
        val r = captured!!
        assertEquals("https://api.anthropic.com/v1/messages", r.url.toString())
        assertEquals("sk-ant-key-123456", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertEquals(AnthropicProvider.FALLBACK_BETA, r.headers["anthropic-beta"])
        val body = (r.body as TextContent).text
        assertTrue(body.contains("\"system\":\"system prompt\""))
        assertTrue(body.contains("\"effort\":\"low\""))
        assertTrue(body.contains("\"fallbacks\":\"default\""))
    }

    @Test fun anthropicEffortSupportByModel() {
        assertTrue(AnthropicProvider.supportsEffort("claude-opus-5"))
        assertTrue(AnthropicProvider.supportsEffort("claude-sonnet-5"))
        assertTrue(AnthropicProvider.supportsEffort("claude-sonnet-4-6"))
        assertFalse(AnthropicProvider.supportsEffort("claude-haiku-4-5"))
        assertFalse(AnthropicProvider.supportsEffort("claude-sonnet-4-5"))
    }

    @Test fun anthropicRefusal() = runTest {
        val http = HttpClient(MockEngine { respond("""{"stop_reason":"refusal","content":[]}""", HttpStatusCode.OK, json) })
        assertFailsWith<AIException.Refused> { AnthropicProvider(http, AIConfig(AIProviderType.ANTHROPIC, null, "claude-sonnet-5", "k")).complete(req) }
    }

    @Test fun errorsMapped() = runTest {
        suspend fun status(code: HttpStatusCode): AIException = try {
            val http = HttpClient(MockEngine { respond("""{"error":{"message":"bad key sk-abcdefghijklmnop"}}""", code, json) })
            OpenAiCompatibleProvider(http, AIConfig(AIProviderType.OPENAI, null, null, "k")).complete(req)
            error("должно было упасть")
        } catch (e: AIException) { e }
        val unauthorized = status(HttpStatusCode.Unauthorized)
        assertTrue(unauthorized is AIException.Unauthorized)
        assertFalse(unauthorized.message!!.contains("abcdefghijklmnop"), "ключ не должен попадать в сообщение об ошибке")
        assertTrue(status(HttpStatusCode.TooManyRequests) is AIException.RateLimited)
        assertTrue(status(HttpStatusCode.InternalServerError) is AIException.Server)
        val http = HttpClient(MockEngine { throw IOException("offline") })
        assertFailsWith<AIException.Network> { OpenAiCompatibleProvider(http, AIConfig(AIProviderType.OPENAI, null, null, "k")).complete(req) }
    }

    @Test fun retriesWithoutJsonFormatOn400() = runTest {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { r ->
            bodies += (r.body as TextContent).text
            if (bodies.size == 1) respond("""{"error":"response_format not supported"}""", HttpStatusCode.BadRequest, json)
            else respond("""{"choices":[{"message":{"content":"ok"}}]}""", HttpStatusCode.OK, json)
        })
        OpenAiCompatibleProvider(http, AIConfig(AIProviderType.CUSTOM, "http://localhost:11434/v1", "llama3", "")).complete(req)
        assertEquals(2, bodies.size)
        assertFalse(bodies[1].contains("response_format"))
    }

    @Test fun embeddingsParsed() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""", HttpStatusCode.OK, json)
        })
        val v = OpenAiCompatibleProvider(http, AIConfig(AIProviderType.OPENAI, null, null, "k")).embed(listOf("a", "b"))
        assertEquals(1.0f, v[0][0])
        assertEquals(1.0f, v[1][1])
    }

    @Test fun configToStringHidesKey() {
        assertFalse(AIConfig(AIProviderType.OPENAI, null, null, "sk-very-secret").toString().contains("very-secret"))
    }
}

class EndpointSecurityTest {
    @Test fun httpOnlyForLocalNetwork() {
        fun secure(url: String) = AIConfig(AIProviderType.CUSTOM, url, "m", "").isSecureEndpoint
        assertTrue(secure("https://api.example.com/v1"))
        assertTrue(secure("http://192.168.1.10:11434/v1"))
        assertTrue(secure("http://localhost:1234/v1"))
        assertTrue(secure("http://172.20.0.5/v1"))
        assertFalse(secure("http://api.example.com/v1"))
        assertFalse(secure("http://172.40.0.5/v1"))
        assertFailsWith<AIException.InsecureEndpoint> {
            AIProviderFactory.create(io.ktor.client.HttpClient(MockEngine { error("no") }), AIConfig(AIProviderType.CUSTOM, "http://evil.com/v1", "m", "k"))
        }
    }
}
