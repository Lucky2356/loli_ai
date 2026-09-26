package ai.loli.core.ai

import ai.loli.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
            var attempt = 0
            while (true) {
                try {
                    return provider.complete(request).also { lastUsed = provider }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AIException) {
                    // Кратковременный сбой (сеть моргнула, сервис перегружен, лимит) — одна повторная попытка.
                    val transient = e is AIException.Network || e is AIException.RateLimited || (e is AIException.Server && e.code >= 500)
                    if (transient && attempt++ < RETRIES) {
                        delay(if (e is AIException.RateLimited) 1500L else 600L)
                        continue
                    }
                    Logger.w(TAG, "${provider.type.id} недоступен (${e::class.simpleName}), пробую следующий")
                    if (firstError == null) firstError = e
                    break
                }
            }
        }
        throw firstError ?: AIException.NotConfigured()
    }

    private companion object {
        const val TAG = "AIChain"
        const val RETRIES = 1
    }
}
