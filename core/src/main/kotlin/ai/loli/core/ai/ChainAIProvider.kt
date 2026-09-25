package ai.loli.core.ai

import ai.loli.core.util.Logger
import kotlinx.coroutines.CancellationException

/**
 * Несколько AI-провайдеров одновременно: запрос идёт первому по приоритету,
 * а при ошибке (нет сети, лимит, неверный ключ, сбой сервиса) — автоматически следующему.
 * Ассистент видит один [AIProvider] и не зависит от того, сколько провайдеров подключено.
 */
class ChainAIProvider(val providers: List<AIProvider>) : AIProvider {
    init {
        require(providers.isNotEmpty()) { "нужен хотя бы один провайдер" }
    }

    /** Провайдер, ответивший последним (для подписи ответа в интерфейсе). */
    @Volatile var lastUsed: AIProvider? = null
        private set

    override val type: AIProviderType get() = (lastUsed ?: providers.first()).type
    override val model: String get() = (lastUsed ?: providers.first()).model

    override suspend fun complete(request: AIRequest): AIResponse {
        var firstError: AIException? = null
        for (provider in providers) {
            try {
                return provider.complete(request).also { lastUsed = provider }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AIException) {
                Logger.w(TAG, "${provider.type.id} недоступен (${e::class.simpleName}), пробую следующий")
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: AIException.NotConfigured()
    }

    private companion object {
        const val TAG = "AIChain"
    }
}
