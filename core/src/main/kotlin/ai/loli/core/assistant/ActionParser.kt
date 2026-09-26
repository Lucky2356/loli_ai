package ai.loli.core.assistant

import ai.loli.core.data.LoliJson
import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.model.Recurrence
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.core.nlp.RuTokenizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Превращает ответ AI (JSON) в проверенные действия. Ничего не выполняется «на доверии»:
 * типы, диапазоны, даты, ссылки на записи — всё валидируется; некорректные действия отбрасываются с причиной.
 *
 * @param handles соответствие меток кандидатов (#1, #2…) реальным id записей.
 */
class ActionParser(
    private val now: Instant,
    private val zone: ZoneId,
    private val handles: Map<String, String> = emptyMap(),
) {
    private val today: LocalDate = now.atZone(zone).toLocalDate()

    fun parse(raw: String): AssistantPlan {
        val obj = extractJsonObject(raw)
            ?: return AssistantPlan(reply = raw.trim().take(2000), actions = emptyList()) // модель ответила текстом — ничего не выполняем
        val reply = obj.s("reply").orEmpty().trim()
        val actionsJson = (obj["actions"] as? JsonArray).orEmpty()
        val actions = ArrayList<AssistantAction>()
        val rejected = ArrayList<String>()
        for (el in actionsJson.take(MAX_ACTIONS)) {
            val a = el as? JsonObject ?: continue
            try {
                parseAction(a)?.let { actions += it }
            } catch (e: ValidationException) {
                rejected += e.message.orEmpty()
            }
        }
        if (actionsJson.size > MAX_ACTIONS) rejected += "слишком много действий в одной команде"
        return AssistantPlan(
            reply = reply,
            actions = actions,
            expectFollowUp = (obj["expect_followup"] as? JsonPrimitive)?.booleanOrNull ?: false,
            topic = obj.s("topic")?.takeIf { it.isNotBlank() && it != "null" }?.take(200),
            rejected = rejected,
        )
    }

    private fun parseAction(a: JsonObject): AssistantAction? {
        val type = a.s("type")?.lowercase()?.trim() ?: throw ValidationException("действие без типа")
        return when (type) {
            "create_note", "create_idea" -> {
                val kind = if (type == "create_idea") NoteKind.IDEA else NoteKind.fromWire(a.s("kind"))
                val title = text(a.s("title"), 200)
                val content = text(a.s("content"), MAX_TEXT)
                if (title.isEmpty() && content.isEmpty()) throw ValidationException("пустая заметка")
                AssistantAction.CreateNote(kind, title.ifEmpty { content.lineSequence().first().take(80) }, content, stringList(a["tags"]).take(10))
            }
            "append_note", "append_to_note" -> {
                val content = text(a.s("content"), MAX_TEXT)
                if (content.isEmpty()) throw ValidationException("нечего добавить в запись")
                val kind = a.s("kind")?.let { NoteKind.fromWire(it) }
                val types = when (kind) {
                    NoteKind.IDEA -> setOf(RecordType.IDEA)
                    NoteKind.NOTE -> setOf(RecordType.NOTE)
                    null -> setOf(RecordType.NOTE, RecordType.IDEA)
                }
                AssistantAction.AppendNote(
                    target = target(a, types),
                    content = content,
                    titleIfNew = a.s("title_if_new")?.let { text(it, 200) }?.takeIf { it.isNotEmpty() },
                    kindIfNew = kind ?: NoteKind.NOTE,
                )
            }
            "update_note" -> {
                val title = a.s("title")?.let { text(it, 200) }?.takeIf { it.isNotEmpty() }
                val content = a.s("content")?.let { text(it, MAX_TEXT) }
                if (title == null && content == null) throw ValidationException("нечего изменить в записи")
                AssistantAction.UpdateNote(target(a, setOf(RecordType.NOTE, RecordType.IDEA), required = true), title, content)
            }
            "delete_note" -> AssistantAction.DeleteNote(target(a, setOf(RecordType.NOTE, RecordType.IDEA), required = true))
            "create_expense" -> {
                val amount = a.d("amount") ?: throw ValidationException("не указана сумма расхода")
                if (amount <= 0 || amount > 1_000_000_000) throw ValidationException("некорректная сумма расхода")
                val currency = (a.s("currency") ?: "RUB").uppercase().trim().let { c -> if (c.matches(Regex("[A-Z]{3}"))) c else Money.currencyWords[c.lowercase()] ?: "RUB" }
                val description = text(a.s("description"), 300)
                val date = a.s("date")?.let { parseDate(it) } ?: today
                if (date.isAfter(today.plusDays(1))) throw ValidationException("дата расхода в будущем")
                if (date.isBefore(today.minusYears(5))) throw ValidationException("слишком старая дата расхода")
                AssistantAction.CreateExpense(Money.toMinor(amount), currency, ExpenseCategories.normalize(a.s("category"), description), description, date)
            }
            "query_expenses" -> {
                val from = a.s("from")?.let { parseDate(it) }
                val to = a.s("to")?.let { parseDate(it) }
                val preset = PeriodPreset.fromWire(a.s("period")) ?: if (from == null) PeriodPreset.THIS_MONTH else null
                AssistantAction.QueryExpenses(preset, from, to ?: from?.let { today }, a.s("category")?.trim()?.takeIf { it.isNotEmpty() }, ReportMode.fromWire(a.s("mode")))
            }
            "delete_expenses", "delete_expense" -> {
                val ids = stringList(a["targets"]).mapNotNull { resolveHandle(it) } + listOfNotNull(a.s("target")?.let { resolveHandle(it) })
                val from = a.s("from")?.let { parseDate(it) }
                val to = a.s("to")?.let { parseDate(it) }
                val preset = PeriodPreset.fromWire(a.s("period"))
                if (ids.isEmpty() && preset == null && from == null) throw ValidationException("не указано, какие расходы удалить")
                AssistantAction.DeleteExpenses(ids, preset, from, to ?: from, a.s("category")?.takeIf { it.isNotBlank() })
            }
            "create_task" -> {
                val title = cleanTaskTitle(text(a.s("title"), 300))
                if (title.isEmpty()) throw ValidationException("пустая задача")
                AssistantAction.CreateTask(
                    title, text(a.s("details"), MAX_TEXT),
                    a.s("due_date")?.let { parseDate(it) },
                    a.s("due_time")?.let { parseTime(it) },
                )
            }
            "complete_task" -> AssistantAction.CompleteTask(target(a, setOf(RecordType.TASK), required = true), a.b("done") ?: true)
            "query_tasks" -> AssistantAction.QueryTasks(TaskFilter.fromWire(a.s("filter")))
            "delete_task" -> AssistantAction.DeleteTask(target(a, setOf(RecordType.TASK), required = true))
            "create_reminder" -> parseReminder(a)
            "cancel_reminder", "delete_reminder" -> AssistantAction.CancelReminder(target(a, setOf(RecordType.REMINDER), required = true))
            "query_reminders" -> AssistantAction.QueryReminders
            "remember", "create_memory" -> {
                val content = text(a.s("content"), 2000)
                if (content.isEmpty()) throw ValidationException("нечего запомнить")
                AssistantAction.Remember(content, (a.s("category") ?: "other").lowercase().take(30))
            }
            "forget_memory", "delete_memory" -> AssistantAction.ForgetMemory(target(a, setOf(RecordType.MEMORY), required = true))
            "query_memories" -> AssistantAction.QueryMemories(a.s("query")?.takeIf { it.isNotBlank() })
            "search" -> {
                val query = text(a.s("query"), 300)
                val keywords = stringList(a["keywords"]).take(15)
                if (query.isEmpty() && keywords.isEmpty()) throw ValidationException("пустой поисковый запрос")
                val types = stringList(a["types"]).mapNotNull { RecordType.fromWire(it) }.toSet().ifEmpty { null }
                AssistantAction.Search(query, keywords, types)
            }
            "clarify" -> AssistantAction.Clarify(text(a.s("question"), 500).ifEmpty { throw ValidationException("пустой уточняющий вопрос") })
            // Команда телефону: модель пересказывает её фразой, а разбирает её тот же офлайн-парсер — так AI не может выдумать опасную команду.
            "device" -> DevicePhrases.parse(RuTokenizer.normalize(text(a.s("phrase"), 300)), now, zone)
                ?: throw ValidationException("неизвестная команда телефону")
            "agenda" -> AssistantAction.Agenda(a.s("date")?.let { parseDate(it) } ?: today)
            "delete_last", "undo" -> AssistantAction.DeleteLast(RecordType.fromWire(a.s("record_type")))
            "update_last_expense" -> {
                val amount = a.d("amount")?.takeIf { it > 0 && it <= 1_000_000_000 }?.let { Money.toMinor(it) }
                val category = a.s("category")?.takeIf { it.isNotBlank() }?.let { ExpenseCategories.normalize(it) }
                if (amount == null && category == null) throw ValidationException("нечего исправить в расходе")
                AssistantAction.UpdateLastExpense(amount, category)
            }
            "none", "reply", "answer" -> null
            else -> throw ValidationException("неизвестное действие «$type»")
        }
    }

    private fun parseReminder(a: JsonObject): AssistantAction.CreateReminder {
        val text = text(a.s("text"), 500)
        if (text.isEmpty()) throw ValidationException("пустое напоминание")
        val recurrence = (a["recurrence"] as? JsonObject)?.let { parseRecurrence(it) }
        val inMinutes = a.d("in_minutes")
        val datetime = a.s("datetime")?.let { parseLocalDateTime(it) }
        val trigger: Instant = when {
            inMinutes != null -> {
                if (inMinutes <= 0 || inMinutes > 60 * 24 * 366) throw ValidationException("некорректный интервал напоминания")
                now.plusSeconds((inMinutes * 60).toLong())
            }
            datetime != null -> {
                val instant = datetime.atZone(zone).toInstant()
                if (recurrence != null && !instant.isAfter(now)) recurrence.nextAfter(now, zone, instant)
                else if (!instant.isAfter(now.minusSeconds(60))) throw ValidationException("время напоминания уже прошло")
                else instant
            }
            recurrence != null -> {
                val anchor = if (recurrence.frequency == Recurrence.Frequency.HOURLY) now
                else ZonedDateTime.of(today, recurrence.time ?: LocalTime.of(9, 0), zone).toInstant()
                recurrence.nextAfter(now, zone, anchor)
            }
            else -> throw ValidationException("не указано время напоминания")
        }
        if (trigger.isAfter(now.plusSeconds(60L * 60 * 24 * 366 * 5))) throw ValidationException("слишком далёкая дата напоминания")
        return AssistantAction.CreateReminder(text, trigger, recurrence)
    }

    private fun parseRecurrence(r: JsonObject): Recurrence {
        val freq = when (r.s("frequency")?.lowercase()) {
            "hourly" -> Recurrence.Frequency.HOURLY
            "daily" -> Recurrence.Frequency.DAILY
            "weekly" -> Recurrence.Frequency.WEEKLY
            "monthly" -> Recurrence.Frequency.MONTHLY
            "yearly", "annually" -> Recurrence.Frequency.YEARLY
            else -> throw ValidationException("неизвестная периодичность напоминания")
        }
        val interval = (r.d("interval") ?: 1.0).toInt()
        if (interval !in 1..366) throw ValidationException("некорректный интервал повторения")
        val days = stringList(r["days"]).mapNotNull { Recurrence.dayFromCode(it) }.toSet()
        val dom = r.d("day_of_month")?.toInt()?.takeIf { it in 1..31 }
        return Recurrence(freq, interval, days, r.s("time")?.let { parseTime(it) }, dom)
    }

    private fun target(a: JsonObject, types: Set<RecordType>, required: Boolean = false): TargetRef {
        val raw = a.s("target") ?: a.s("target_id") ?: a.s("id")
        val id = raw?.let { resolveHandle(it) }
        if (raw != null && id == null && raw.isNotBlank() && raw != "null") {
            // AI сослался на несуществующую метку — не угадываем, а ищем по запросу, если он есть.
            if (a.s("query").isNullOrBlank()) throw ValidationException("ссылка на неизвестную запись $raw")
        }
        val query = a.s("query")?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: a.s("target_query")?.trim()?.takeIf { it.isNotEmpty() }
        val ref = TargetRef(id, query, types)
        if (required && ref.isEmpty) throw ValidationException("не указано, с какой записью работать")
        return ref
    }

    private fun resolveHandle(value: String): String? {
        val v = value.trim()
        handles[v]?.let { return it }
        handles["#" + v.trimStart('#')]?.let { return it }
        return if (handles.containsValue(v)) v else null
    }

    private fun parseDate(v: String): LocalDate? = runCatching { LocalDate.parse(v.trim().take(10)) }.getOrElse {
        throw ValidationException("некорректная дата «$v»")
    }

    private fun parseTime(v: String): LocalTime? = runCatching {
        val t = v.trim()
        LocalTime.parse(if (Regex("""\d:\d{2}""").matches(t)) "0$t" else t.take(8))
    }.getOrElse { throw ValidationException("некорректное время «$v»") }

    private fun parseLocalDateTime(v: String): LocalDateTime? {
        val t = v.trim()
        return runCatching { ZonedDateTime.parse(t).withZoneSameInstant(zone).toLocalDateTime() }
            .recoverCatching { LocalDateTime.parse(t.take(19)) }
            .recoverCatching { LocalDateTime.parse(t.take(16)) }
            .getOrElse { throw ValidationException("некорректные дата и время «$v»") }
    }

    private fun text(v: String?, max: Int): String = v.orEmpty().trim().take(max)

    private fun stringList(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is JsonPrimitive -> listOfNotNull(e.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        else -> emptyList()
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.replace(" ", "")?.toDoubleOrNull() }
    private fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    class ValidationException(message: String) : Exception(message)

    companion object {
        const val MAX_ACTIONS = 10
        const val MAX_TEXT = 10_000

        /** Достаёт JSON-объект из ответа модели (допускает обёртку ```json и текст вокруг). */
        fun extractJsonObject(raw: String): JsonObject? {
            // Рассуждающие модели (DeepSeek R1, Qwen и др.) пишут размышления в <think>…</think> перед ответом.
            val text = raw.replace(Regex("""(?s)<think>.*?</think>"""), "").trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
            runCatching { return LoliJson.parseToJsonElement(text) as? JsonObject }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { LoliJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
        }
    }
}

/** Страховка от AI: убирает из названия задачи обращение и слова команды («Лоли, поставь задачу …»). */
internal fun cleanTaskTitle(raw: String): String {
    // AI иногда оставляет в названии «завтра», «в 17:00» — дата и время уже в due_date/due_time.
    val noDates = ai.loli.core.nlp.RuDateTimeParser().parse(ai.loli.core.nlp.SpokenTime.normalize(raw), java.time.LocalDate.now()).remainder
    return ActionTitle.clean(noDates.ifBlank { raw })
}
