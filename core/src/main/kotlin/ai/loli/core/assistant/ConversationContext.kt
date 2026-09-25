package ai.loli.core.assistant

import ai.loli.core.ai.ChatMessage
import ai.loli.core.model.RecordType
import ai.loli.core.util.Ids
import ai.loli.core.util.TimeSource
import java.time.Duration
import java.time.Instant

/** Ссылка на запись, с которой шла работа (для «туда», «к ней», «ещё»). */
data class RecordRef(val type: RecordType, val id: String, val title: String)

/** Операция, ожидающая подтверждения пользователя. */
data class DestructiveOp(val type: RecordType, val id: String, val title: String, val cancelOnly: Boolean = false)

data class PendingConfirmation(val question: String, val operations: List<DestructiveOp>)

/** Уточнение «какую запись вы имели в виду» с вариантами. */
data class PendingChoice(
    val question: String,
    val options: List<RecordRef>,
    val action: AssistantAction,
    /** Действия из той же фразы, которые выполнятся после уточнения. */
    val remaining: List<AssistantAction> = emptyList(),
)

/**
 * Краткосрочный контекст диалога. Ограничен по числу реплик, объёму и времени бездействия,
 * чтобы история не росла бесконечно и старая тема не «прилипала» к новым командам.
 */
private val DIALOG_IDLE: Duration = Duration.ofMinutes(3)

class ConversationContext(
    private val time: TimeSource,
    private val maxMessages: Int = 12,
    private val maxChars: Int = 8_000,
    private val ttl: Duration = Duration.ofMinutes(15),
) {
    var conversationId: String = Ids.newId(); private set
    private val turns = ArrayDeque<ChatMessage>()
    private val recentRecords = ArrayDeque<RecordRef>()
    var focus: RecordRef? = null; private set
    var topic: String? = null
    var pendingConfirmation: PendingConfirmation? = null
    var pendingChoice: PendingChoice? = null
    var dialogMode: Boolean = false
    /** Ожидаем дозаполнения (сумма расхода, время напоминания, «сохранить как заметку?»). */
    var pendingSlot: SlotRequest? = null
    /** Последняя созданная в разговоре запись — для «отмени последнее». */
    var lastCreated: RecordRef? = null
    private var lastActivity: Instant = time.now()

    val messages: List<ChatMessage> get() = turns.toList()
    val recent: List<RecordRef> get() = recentRecords.toList()

    /** Сбрасывает контекст, если пользователь долго молчал. Возвращает true при сбросе. */
    fun touch(): Boolean {
        val now = time.now()
        val idle = Duration.between(lastActivity, now)
        val expired = idle > ttl
        if (expired) reset()
        // Диалог (мозговой штурм) завершается сам после короткой паузы.
        if (dialogMode && idle > DIALOG_IDLE) dialogMode = false
        lastActivity = now
        return expired
    }

    fun addTurn(user: String, assistant: String) {
        turns.addLast(ChatMessage(ChatMessage.Role.USER, user))
        if (assistant.isNotBlank()) turns.addLast(ChatMessage(ChatMessage.Role.ASSISTANT, assistant))
        while (turns.size > maxMessages || turns.sumOf { it.content.length } > maxChars) turns.removeFirst()
        // История для модели должна начинаться с реплики пользователя.
        while (turns.isNotEmpty() && turns.first().role != ChatMessage.Role.USER) turns.removeFirst()
    }

    fun touchRecord(ref: RecordRef) {
        focus = ref
        recentRecords.removeAll { it.id == ref.id }
        recentRecords.addFirst(ref)
        while (recentRecords.size > 6) recentRecords.removeLast()
    }

    fun forgetRecord(id: String) {
        if (focus?.id == id) focus = null
        recentRecords.removeAll { it.id == id }
    }

    fun reset() {
        conversationId = Ids.newId()
        turns.clear(); recentRecords.clear()
        focus = null; topic = null
        pendingConfirmation = null; pendingChoice = null; pendingSlot = null
        lastCreated = null
        dialogMode = false
    }
}
