package ai.loli.core.ai

import ai.loli.core.data.LoliJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Провайдер для API в формате OpenAI Chat Completions.
 * Покрывает OpenAI, Google Gemini (OpenAI-совместимый endpoint), OpenRouter,
 * а также локальные серверы (Ollama, LM Studio, vLLM).
 */
class OpenAiCompatibleProvider(
    private val http: HttpClient,
    private val config: AIConfig,
) : AIProvider, EmbeddingProvider {

    override val type: AIProviderType get() = config.type
    override val model: String get() = config.model
    override val embeddingModel: String = config.type.defaultEmbeddingModel.orEmpty()

    override suspend fun complete(request: AIRequest): AIResponse = aiCall {
        try {
            send(request, useJsonFormat = request.jsonMode)
        } catch (e: AIException.Server) {
            // Некоторые совместимые серверы не поддерживают response_format — повторяем без него.
            if (request.jsonMode && e.message?.contains("400") == true) send(request, useJsonFormat = false) else throw e
        }
    }

    private suspend fun send(request: AIRequest, useJsonFormat: Boolean): AIResponse {
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", request.system) })
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == ChatMessage.Role.USER) "user" else "assistant")
                        put("content", m.content)
                    })
                }
            })
            if (useJsonFormat) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val response = http.post("${config.endpoint}/chat/completions") {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
            if (config.type == AIProviderType.OPENROUTER) header("X-Title", "Loli Assistant")
            setBody(body.toString())
        }
        response.throwIfError()
        val json = LoliJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val choice = (json["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AIException.InvalidResponse("нет choices")
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val content = choice.obj("message")?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull
        if (content.isNullOrBlank()) {
            if (finish == "content_filter") throw AIException.Refused()
            throw AIException.InvalidResponse("пустой ответ (finish_reason=$finish)")
        }
        return AIResponse(content, json["model"]?.jsonPrimitive?.contentOrNull, finish)
    }

    override suspend fun listModels(): List<ModelInfo> = aiCall {
        val response = http.get("${config.endpoint}/models") {
            if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
        }
        response.throwIfError()
        val data = LoliJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"] as? JsonArray
            ?: throw AIException.InvalidResponse("нет списка моделей")
        data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // Gemini отдаёт «models/gemini-2.5-flash», в запросах нужен id без префикса.
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && !it.startsWith("models/") }
                ?: (o["display_name"] as? JsonPrimitive)?.contentOrNull ?: id
            ModelInfo(id, name)
        }.filter { isChatModel(it.id) }.distinctBy { it.id }.sortedBy { it.id }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = aiCall {
        if (embeddingModel.isBlank()) throw AIException.InvalidResponse("провайдер не поддерживает эмбеддинги")
        if (texts.isEmpty()) return@aiCall emptyList()
        val body = buildJsonObject {
            put("model", embeddingModel)
            put("input", JsonArray(texts.map { JsonPrimitive(it.take(4000)) }))
        }
        val response = http.post("${config.endpoint}/embeddings") {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
            setBody(body.toString())
        }
        response.throwIfError()
        val data = LoliJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray
            ?: throw AIException.InvalidResponse("нет data")
        val sorted = data.map { it.jsonObject }.sortedBy { it["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
        sorted.map { item ->
            val arr = item["embedding"]?.jsonArray ?: throw AIException.InvalidResponse("нет embedding")
            FloatArray(arr.size) { i -> arr[i].jsonPrimitive.floatOrNull ?: 0f }
        }.also { if (it.size != texts.size) throw AIException.InvalidResponse("число векторов не совпадает") }
    }
}
