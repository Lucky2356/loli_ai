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

    fun createEmbeddings(http: HttpClient, config: AIConfig): EmbeddingProvider? {
        if (!config.isComplete || !config.embeddingsEnabled || !config.isSecureEndpoint) return null
        if (config.type.defaultEmbeddingModel == null) return null
        return OpenAiCompatibleProvider(http, config)
    }
}
