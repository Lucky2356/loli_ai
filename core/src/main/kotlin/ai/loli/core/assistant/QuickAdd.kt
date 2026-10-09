package ai.loli.core.assistant

import ai.loli.core.model.NoteKind
import java.time.Instant
import java.time.ZoneId

/**
 * Быстрое добавление с экранов (поле «Добавить» на вкладках): короткая фраза без команды —
 * «завтра в 9 позвонить маме», «кофе 250», «купить хлеб в пятницу» — превращается ровно в одно действие
 * нужного вида. Разговор и история чата не затрагиваются.
 */
object QuickAdd {
    enum class Kind { TASK, REMINDER, EXPENSE, NOTE, IDEA }

    sealed interface Result {
        data class Ok(val action: AssistantAction) : Result
        /** Не хватает данных — подсказка, как написать. */
        data class Hint(val text: String) : Result
    }

    private val parser = LocalCommandParser()

    fun plan(kind: Kind, input: String, now: Instant, zone: ZoneId): Result {
        val text = input.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return Result.Hint(EMPTY)
        return when (kind) {
            Kind.TASK -> {
                val found = pick<AssistantAction.CreateTask>(withPrefix(text, TASK_PREFIX, "добавь задачу "), now, zone)
                Result.Ok(found?.takeIf { it.title.isNotBlank() } ?: AssistantAction.CreateTask(cap(text), "", null, null))
            }
            Kind.REMINDER -> {
                val found = pick<AssistantAction.CreateReminder>(withPrefix(text, REMINDER_PREFIX, "напомни "), now, zone)
                when {
                    found == null -> Result.Hint("Не поняла, когда напомнить. Например: «завтра в 9 позвонить маме» или «через 20 минут выключить духовку».")
                    found.triggerAt.isBefore(now) && found.recurrence == null -> Result.Hint("Это время уже прошло — укажите будущее.")
                    else -> Result.Ok(found)
                }
            }
            Kind.EXPENSE -> {
                val found = pick<AssistantAction.CreateExpense>(withPrefix(text, EXPENSE_PREFIX, "потратила "), now, zone)
                if (found == null || found.amountMinor <= 0) Result.Hint("Нужна сумма. Например: «кофе 250» или «такси 640 вчера».") else Result.Ok(found)
            }
            Kind.NOTE, Kind.IDEA -> {
                val k = if (kind == Kind.IDEA) NoteKind.IDEA else NoteKind.NOTE
                val first = text.substringBefore(". ").take(60)
                Result.Ok(AssistantAction.CreateNote(k, cap(first), text))
            }
        }
    }

    private inline fun <reified T : AssistantAction> pick(text: String, now: Instant, zone: ZoneId): T? =
        parser.parse(text, now, zone)?.actions?.filterIsInstance<T>()?.singleOrNull()

    /** Если человек сам начал с команды («напомни …»), второй раз её не добавляем. */
    private fun withPrefix(text: String, already: Regex, prefix: String) = if (already.containsMatchIn(text)) text else prefix + text

    private fun cap(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    private const val EMPTY = "Напишите, что добавить."
    private val TASK_PREFIX = Regex("^(?:добавь|создай|запиши|поставь|нов\\w*)\\s+(?:мне\\s+)?(?:задач|дел)", RegexOption.IGNORE_CASE)
    private val REMINDER_PREFIX = Regex("^(?:напомни|напоминание|разбуди)", RegexOption.IGNORE_CASE)
    private val EXPENSE_PREFIX = Regex("^(?:потратил|потратила|потрачено|заплатил|заплатила|купил|купила|расход|получил|получила|зарплата|доход)", RegexOption.IGNORE_CASE)
}
