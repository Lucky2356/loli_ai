package ai.loli.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.loli.app.AppContainer
import ai.loli.core.ai.AIProviderFactory
import ai.loli.core.ai.AIProviderType
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.ChatMessage
import ai.loli.core.model.ConversationMessage
import ai.loli.core.model.NoteKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

private val started = SharingStarted.WhileSubscribed(5_000)

data class HomeSummary(
    val notes: Int = 0,
    val ideas: Int = 0,
    val activeTasks: Int = 0,
    val overdueTasks: Int = 0,
    val todayExpensesMinor: Long = 0,
    val monthExpensesMinor: Long = 0,
    val activeReminders: Int = 0,
    val memories: Int = 0,
)

class HomeViewModel(val c: AppContainer) : ViewModel() {
    val voice = c.voice.state
    val lastReply = c.voice.lastReply
    val online = c.network.online
    val auth = c.auth.state
    val sync = c.syncEngine.status
    val settings = c.settings.settings

    val history: StateFlow<List<ConversationMessage>> = c.store.conversations.observeRecent(12)
        .map { it.reversed() }
        .stateIn(viewModelScope, started, emptyList())

    val summary: StateFlow<HomeSummary> = combine(
        c.store.notes.observe(null), c.store.tasks.observe(), c.store.expenses.observe(), c.store.reminders.observe(), c.store.memories.observe(),
    ) { notes, tasks, expenses, reminders, memories ->
        val today = LocalDate.now(ZoneId.systemDefault())
        val now = java.time.LocalTime.now()
        HomeSummary(
            notes = notes.count { it.kind == NoteKind.NOTE },
            ideas = notes.count { it.kind == NoteKind.IDEA },
            activeTasks = tasks.count { !it.done },
            overdueTasks = tasks.count { it.isOverdue(today, now) },
            todayExpensesMinor = expenses.filter { it.occurredOn == today && it.currency == "RUB" }.sumOf { it.amountMinor },
            monthExpensesMinor = expenses.filter { it.occurredOn.withDayOfMonth(1) == today.withDayOfMonth(1) && it.currency == "RUB" }.sumOf { it.amountMinor },
            activeReminders = reminders.count { it.active },
            memories = memories.size,
        )
    }.stateIn(viewModelScope, started, HomeSummary())

    fun aiConfigured(): Boolean = c.aiConfigured()
    fun listen() = c.voice.startListening()
    fun stop() = c.voice.stop()
    fun send(text: String) = c.voice.submitText(text)
    fun confirm(yes: Boolean) = c.voice.confirm(yes)
    fun clearError() = c.voice.clearError()
}

/** Проверка подключения к конкретному AI-провайдеру коротким запросом. */
suspend fun testAiConnection(c: AppContainer, type: AIProviderType): Result<String> = runCatching {
    val cfg = c.aiConfig(type)
    val provider = AIProviderFactory.create(c.http, cfg)
    val response = provider.complete(
        AIRequest(
            system = "Ты тестируешь подключение. Ответь одним словом: готово.",
            messages = listOf(ChatMessage(ChatMessage.Role.USER, "Проверка связи")),
            jsonMode = false,
            maxTokens = 1024,
        ),
    )
    "Работает (${response.model ?: cfg.model}): ${response.text.trim().take(60)}"
}
