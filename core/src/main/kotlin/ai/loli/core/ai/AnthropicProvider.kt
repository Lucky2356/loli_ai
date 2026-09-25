package ai.loli.core.ai

import ai.loli.core.data.LoliJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Провайдер Anthropic Claude (Messages API, `POST /v1/messages`).
 * Реализован поверх общего HTTP-клиента, как и остальные провайдеры, — без отдельного SDK,
 * чтобы все провайдеры имели одинаковый транспорт, логирование без секретов и тесты на MockEngine.
 */
class AnthropicProvider(
    private val http: HttpClient,
    private val config: AIConfig,
) : AIProvider {

    override val type: AIProviderType get() = AIProviderType.ANTHROPIC
    override val model: String get() = config.model

    override suspend fun complete(request: AIRequest): AIResponse = aiCall {
        val officialApi = config.endpoint.contains("api.anthropic.com")
        val useFallbacks = officialApi && supportsServerFallback(config.model)
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", request.maxTokens.coerceAtLeast(1024))
            put("system", request.system)
            put("messages", buildJsonArray {
                // Messages API требует, чтобы диалог начинался с реплики пользователя.
                request.messages.dropWhile { it.role != ChatMessage.Role.USER }.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == ChatMessage.Role.USER) "user" else "assistant")
                        put("content", m.content)
                    })
                }
            })
            // Голосовому ассистенту важна скорость ответа: низкое усилие рассуждений.
            if (supportsEffort(config.model)) put("output_config", buildJsonObject { put("effort", "low") })
            if (useFallbacks) put("fallbacks", "default")
        }
        val response = http.post("${config.endpoint}/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.apiKey)
            header("anthropic-version", API_VERSION)
            if (useFallbacks) header("anthropic-beta", FALLBACK_BETA)
            setBody(body.toString())
        }
        response.throwIfError()
        val json = LoliJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val stop = json["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stop == "refusal") throw AIException.Refused()
        val text = (json["content"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (text.isBlank()) throw AIException.InvalidResponse("пустой ответ (stop_reason=$stop)")
        AIResponse(text, json["model"]?.jsonPrimitive?.contentOrNull, stop)
    }

    companion object {
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val modelRegex = Regex("""claude-(opus|sonnet|fable|mythos|haiku)-(\d+)(?:-(\d{1,2}))?(?:$|-\d{8}$|\b)""")

        /** Параметр effort поддерживают Opus/Sonnet 4.6+ и все модели 5-го поколения; Haiku — нет. */
        fun supportsEffort(model: String): Boolean {
            val m = modelRegex.find(model) ?: return false
            val family = m.groupValues[1]
            if (family == "haiku") return false
            val major = m.groupValues[2].toInt()
            val minor = m.groupValues[3].toIntOrNull() ?: 0
            return major >= 5 || (major == 4 && minor >= 6)
        }

        /** Серверный fallback при отказе классификаторов — для Opus 5 и Fable 5.1. */
        fun supportsServerFallback(model: String): Boolean =
            model == "claude-opus-5" || model == "claude-fable-5-1"
    }
}
