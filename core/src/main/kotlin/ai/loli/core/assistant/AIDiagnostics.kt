package ai.loli.core.assistant

import ai.loli.core.ai.AIException
import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.ChatMessage
import java.time.Instant
import java.time.ZoneId

/**
 * Проверка подключения тем же запросом, что и в работе: полный системный промпт, режим JSON, разбор действий.
 * Ничего не выполняется и не сохраняется. Так видно проблему, из-за которой Лоли уходит в локальный режим,
 * даже если «привет» модель отвечает нормально.
 */
object AIDiagnostics {
    suspend fun check(provider: AIProvider, name: String, now: Instant, zone: ZoneId): String {
        val system = PromptBuilder.build(name, now, zone, emptyList(), emptyList(), null, null, false)
        val started = System.nanoTime()
        val response = provider.complete(
            AIRequest(system = system, messages = listOf(ChatMessage(ChatMessage.Role.USER, "Добавь задачу проверить подключение на завтра"))),
        )
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        ActionParser.extractJsonObject(response.text)
            ?: throw AIException.InvalidResponse("модель ответила не командами, а текстом: «${response.text.trim().take(80)}». Выберите другую модель")
        val plan = ActionParser(now, zone).parse(response.text)
        val understood = plan.actions.any { it is AssistantAction.CreateTask }
        val speed = "%.1f".format(seconds).replace('.', ',')
        return buildString {
            append("Работает: ${response.model ?: provider.model}, ответ за $speed с")
            append(if (understood) ", команды понимает." else ". Модель отвечает, но команду поняла неточно — для команд лучше модель помощнее.")
            if (seconds > 12) append(" Модель медленная для голоса — выберите «mini»/«flash»-версию.")
        }
    }
}
