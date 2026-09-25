package ai.loli.core.ai

import io.ktor.client.HttpClient

/** Создаёт провайдера по настройкам. Новый провайдер добавляется сюда и в [AIProviderType]. */
object AIProviderFactory {
    fun create(http: HttpClient, config: AIConfig): AIProvider {
        if (!config.isComplete) throw AIException.NotConfigured()
        return when (config.type) {
            AIProviderType.ANTHROPIC -> AnthropicProvider(http, config)
            else -> OpenAiCompatibleProvider(http, config)
        }
    }

    fun createEmbeddings(http: HttpClient, config: AIConfig): EmbeddingProvider? {
        if (!config.isComplete || !config.embeddingsEnabled) return null
        if (config.type.defaultEmbeddingModel == null) return null
        return OpenAiCompatibleProvider(http, config)
    }
}
