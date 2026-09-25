package ai.loli.core.ai

/**
 * Абстракция языковой модели. Бизнес-логика ассистента зависит только от этого интерфейса —
 * провайдер (OpenAI, Gemini, Anthropic, локальная модель) выбирается в настройках.
 */
interface AIProvider {
    val type: AIProviderType
    val model: String
    suspend fun complete(request: AIRequest): AIResponse
}

/** Векторные представления текста для семантического поиска. Поддерживается не всеми провайдерами. */
interface EmbeddingProvider {
    val embeddingModel: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
}

data class AIRequest(
    val system: String,
    val messages: List<ChatMessage>,
    /** Просить модель вернуть строго JSON-объект. */
    val jsonMode: Boolean = true,
    val maxTokens: Int = 4096,
)

data class AIResponse(val text: String, val model: String?, val stopReason: String?)

/** Ошибки AI-слоя с понятными сообщениями для пользователя. */
sealed class AIException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : AIException("AI не настроен: укажите провайдера, модель и API-ключ в настройках.")
    class Unauthorized(detail: String) : AIException("API-ключ не принят провайдером ($detail). Проверьте ключ в настройках.")
    class RateLimited : AIException("Превышен лимит запросов к AI. Попробуйте чуть позже.")
    class Network(cause: Throwable?) : AIException("Нет связи с AI-сервисом. Проверьте интернет.", cause)
    class Server(code: Int, detail: String) : AIException("AI-сервис вернул ошибку $code: $detail")
    class InvalidResponse(detail: String) : AIException("AI вернул ответ в неожиданном формате: $detail")
    class Refused : AIException("Модель отказалась отвечать на этот запрос.")
    class InsecureEndpoint : AIException("Незашифрованный адрес (http://) разрешён только для серверов в локальной сети. Используйте https://.")
}

enum class AIProviderType(
    val id: String,
    val title: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val defaultEmbeddingModel: String?,
    val endpointEditable: Boolean,
) {
    OPENAI(
        "openai", "OpenAI", "https://api.openai.com/v1", "gpt-5-mini",
        listOf("gpt-5-mini", "gpt-5", "gpt-5-nano", "gpt-4.1-mini"), "text-embedding-3-small", false,
    ),
    GEMINI(
        "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
        listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite"), "gemini-embedding-001", false,
    ),
    ANTHROPIC(
        "anthropic", "Anthropic Claude", "https://api.anthropic.com", "claude-opus-5",
        listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"), null, true,
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5-mini",
        listOf("openai/gpt-5-mini", "anthropic/claude-sonnet-5", "google/gemini-2.5-flash"), null, false,
    ),
    DEEPSEEK(
        "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner"), null, false,
    ),
    MISTRAL(
        "mistral", "Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest",
        listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest"), null, false,
    ),
    GROQ(
        "groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
        listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant"), null, false,
    ),
    XAI(
        "xai", "xAI Grok", "https://api.x.ai/v1", "grok-4",
        listOf("grok-4", "grok-3-mini"), null, false,
    ),
    CUSTOM(
        "custom", "Свой сервер (Ollama, LM Studio, vLLM)", "http://192.168.1.10:11434/v1", "",
        emptyList(), null, true,
    );

    val isOpenAiCompatible: Boolean get() = this != ANTHROPIC

    companion object {
        fun fromId(id: String?): AIProviderType = entries.firstOrNull { it.id == id } ?: OPENAI
        fun fromIdOrNull(id: String?): AIProviderType? = entries.firstOrNull { it.id == id }
    }
}

/** Настройки подключения. [apiKey] никогда не попадает в toString()/логи. */
class AIConfig(
    val type: AIProviderType,
    endpoint: String?,
    model: String?,
    val apiKey: String,
    val embeddingsEnabled: Boolean = true,
) {
    val endpoint: String = (endpoint?.takeIf { it.isNotBlank() } ?: type.defaultEndpoint).trim().trimEnd('/')
    val model: String = (model?.takeIf { it.isNotBlank() } ?: type.defaultModel).trim()

    /** http:// допускается только для локальной сети (свой сервер Ollama/LM Studio); в интернет — только https://. */
    val isSecureEndpoint: Boolean get() {
        val lower = endpoint.lowercase()
        if (lower.startsWith("https://")) return true
        if (!lower.startsWith("http://")) return false
        val host = lower.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
    }

    val isComplete: Boolean get() = model.isNotBlank() && endpoint.isNotBlank() && (apiKey.isNotBlank() || type == AIProviderType.CUSTOM)

    override fun toString(): String = "AIConfig(type=${type.id}, endpoint=$endpoint, model=$model, apiKey=${if (apiKey.isBlank()) "<empty>" else "***"})"
}
