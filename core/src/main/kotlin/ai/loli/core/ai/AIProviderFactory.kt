package ai.loli.core.ai

import io.ktor.client.HttpClient

/** Создаёт провайдера по настройкам. Новый провайдер добавляется сюда и в [AIProviderType]. */
object AIProviderFactory {
    fun create(http: HttpClient, config: AIConfig): AIProvider {
        if (!config.isComplete) throw AIException.NotConfigured()
        if (!config.isSecureEndpoint) throw AIException.InsecureEndpoint()
        return when (config.type) {
            AIProviderType.ANTHROPIC -> AnthropicProvider(http, config)
            else -> OpenAiCompatibleProvider(http, config)
        }
    }

    /**
     * Цепочка из нескольких провайдеров в порядке приоритета. Незаполненные (без ключа) и небезопасные
     * пропускаются; если подходящих нет — [AIException.NotConfigured] (или [AIException.InsecureEndpoint]).
     */
    fun createChain(http: HttpClient, configs: List<AIConfig>): AIProvider {
        val complete = configs.filter { it.isComplete }
        val usable = complete.filter { it.isSecureEndpoint }
        if (usable.isEmpty()) throw if (complete.isNotEmpty()) AIException.InsecureEndpoint() else AIException.NotConfigured()
        val providers = usable.map { create(http, it) }
        return providers.singleOrNull() ?: ChainAIProvider(providers)
    }

    /** Первый провайдер цепочки, умеющий эмбеддинги (OpenAI, Gemini). */
    fun createEmbeddings(http: HttpClient, configs: List<AIConfig>): EmbeddingProvider? =
        configs.firstNotNullOfOrNull { createEmbeddings(http, it) }

    fun createEmbeddings(http: HttpClient, config: AIConfig): EmbeddingProvider? {
        if (!config.isComplete || !config.embeddingsEnabled || !config.isSecureEndpoint) return null
        if (config.type.defaultEmbeddingModel == null) return null
        return OpenAiCompatibleProvider(http, config)
    }
}
