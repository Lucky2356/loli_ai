package ai.loli.core.personal

import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.RuFormat
import ai.loli.core.domain.MemoryRepository
import ai.loli.core.nlp.Calculator
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import java.time.LocalDate
import java.time.YearMonth

/**
 * Сроки и гарантии: «гарантия на телевизор до мая 2027», «молоко до пятницы», «паспорт истекает в 2030».
 * Одна запись в памяти (категория «Сроки») на вещь: «Гарантия на телевизор — до 2027-05-31».
 */
class DeadlineBook(private val memories: MemoryRepository) {
    data class Deadline(val id: String, val title: String, val date: LocalDate)

    suspend fun all(): List<Deadline> = memories.all().filter { it.category == CATEGORY }.mapNotNull { m ->
        val r = Regex("""^(.+) — до (\d{4}-\d{2}-\d{2})$""").find(m.content) ?: return@mapNotNull null
        Deadline(m.id, r.groupValues[1], runCatching { LocalDate.parse(r.groupValues[2]) }.getOrNull() ?: return@mapNotNull null)
    }.sortedBy { it.date }

    suspend fun find(query: String): List<Deadline> = all().filter { sameName(it.title, query) }

    /** Сохраняет (заменяя старую запись о том же); возвращает прежнюю, если была. */
    suspend fun put(title: String, date: LocalDate): Deadline? {
        val old = find(title).firstOrNull()
        old?.let { memories.delete(it.id) }
        memories.create("$title — до $date", CATEGORY)
        return old
    }

    suspend fun remove(query: String): List<Deadline> {
        val hits = find(query)
        hits.forEach { memories.delete(it.id) }
        return hits
    }

    companion object {
        const val CATEGORY = "Сроки"
        /** Начало текста напоминаний о сроке — по нему их находим, чтобы заменить или удалить. */
        const val REMINDER_MARK = "Срок: "

        private fun rx(p: String) = Rx.of(p)
        private val WEEKDAYS_GEN = mapOf(
            "понедельника" to "в понедельник", "вторника" to "во вторник", "среды" to "в среду", "четверга" to "в четверг",
            "пятницы" to "в пятницу", "субботы" to "в субботу", "воскресенья" to "в воскресенье",
        )
        private val MONTHS = listOf("январ", "феврал", "март", "апрел", "ма[йяе]", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

        /** «Мая 2027» → 31 мая 2027, «в 2030» → 1 января 2030, иначе — обычный разбор дат («до пятницы», «15 марта»). */
        fun date(raw: String, today: LocalDate): LocalDate? {
            val t = DevicePhrases.digitize(RuTokenizer.normalize(raw).trim().trim(',', '.')).removePrefix("в ").removePrefix("во ").trim()
            Regex("""^(\d{4})(?:\s+год\p{L}*)?$""").find(t)?.let { return LocalDate.of(it.groupValues[1].toInt(), 1, 1) }
            Regex("""^(?:конц\p{L}*\s+)?(\p{L}+)\s+(\d{4})(?:\s+год\p{L}*)?$""").find(t)?.let { m ->
                val idx = MONTHS.indexOfFirst { Regex("^$it").containsMatchIn(m.groupValues[1]) }
                if (idx >= 0) return YearMonth.of(m.groupValues[2].toInt(), idx + 1).atEndOfMonth()
            }
            Regex("""^(?:конц\p{L}*\s+)?(\p{L}+)$""").find(t)?.let { m ->
                val idx = MONTHS.indexOfFirst { Regex("^$it\\p{L}*$").matches(m.groupValues[1]) }
                if (idx >= 0) {
                    val ym = YearMonth.of(today.year, idx + 1).let { if (it.atEndOfMonth().isBefore(today)) it.plusYears(1) else it }
                    return ym.atEndOfMonth()
                }
            }
            // «До понедельника» — родительный падеж дня недели.
            val day = WEEKDAYS_GEN.entries.fold(t) { acc, (gen, acc2) -> acc.replace(Regex("(?<!\\p{L})$gen(?!\\p{L})"), acc2) }
            val parsed = RuDateTimeParser().parse(day, today)
            if (parsed.remainder.isNotBlank()) return null
            return parsed.spec.date
        }

        private const val DOCS = """гарантия|гарантию|страховка|страховку|страховой\s+полис|полис\s+\p{L}+|полис|осаго|каско|паспорт|загранпаспорт|загран|права|водительские\s+права|абонемент|договор|аренда|лицензия|виза|медкнижка|техосмотр|сертификат|прописка|регистрация|пропуск|справка"""
        private const val FOOD = """молоко|кефир|йогурт|творог|сметана|сыр|мясо|фарш|курица|рыба|хлеб|яйца|колбаса|сосиски|сок|лекарство|таблетки|сироп|мазь|капли|консервы|торт|салат|пельмени"""
        private const val UNTIL = """(?:действует\s+до|действителен\s+до|действительна\s+до|годен\s+до|годна\s+до|годно\s+до|годны\s+до|хранится\s+до|истекает|истечет|заканчивается|закончится|кончается|кончится|до)"""

        /** Название и срок из фразы; null — не про сроки. */
        fun parse(text: String, today: LocalDate): Pair<String, LocalDate>? {
            val n = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?')
            val body = n.replace(rx("""^(?:запомни|запиши|сохрани)[,:]?\s+(?:что\s+)?"""), "").replace(rx("""^(?:у\s+меня\s+|мой\s+|моя\s+|мое\s+|моё\s+|мои\s+)"""), "")
            val m = rx("""^((?:срок\s+годности\s+)?(?:$DOCS)(?:\s+(?:на|для|у|от)\s+[^,]+?)?)\s+$UNTIL\s+(?:в\s+|во\s+)?(.+)$""").find(body)
                ?: rx("""^(?:срок\s+годности\s+(?:у\s+)?)?((?:$FOOD)(?:\s+\p{L}+)?)\s+(?:годн\p{L}*\s+|хранится\s+|можно\s+есть\s+|нужно\s+съесть\s+)?до\s+(.+)$""").find(body)
                ?: return null
            val date = date(m.groupValues[2], today) ?: return null
            val title = original(text, m.groupValues[1].replace(Regex("""^срок\s+годности\s+(?:у\s+)?"""), "")).trim().replaceFirstChar { it.uppercase() }
                .replace(Regex("""^Гарантию"""), "Гарантия").replace(Regex("""^Страховку"""), "Страховка")
            if (title.isEmpty() || title.length > 60) return null
            return title to date
        }

        private fun original(text: String, part: String): String {
            val i = RuTokenizer.normalize(text).indexOf(part)
            return if (i >= 0 && i + part.length <= text.length) text.substring(i, i + part.length) else part
        }
    }
}

/**
 * Цели накоплений: «коплю на отпуск 100 тысяч», «отложила 5000 на отпуск», «сколько осталось до отпуска».
 * Запись в памяти (категория «Цели»): «Цель: Отпуск — 15000 из 100000 ₽».
 */
class GoalBook(private val memories: MemoryRepository) {
    data class Goal(val id: String, val name: String, val saved: Double, val target: Double) {
        val percent: Int get() = if (target <= 0) 0 else ((saved / target) * 100).toInt().coerceIn(0, 100)
        val left: Double get() = (target - saved).coerceAtLeast(0.0)
    }

    suspend fun all(): List<Goal> = memories.all().filter { it.category == CATEGORY }.mapNotNull { m ->
        val r = Regex("""^Цель: (.+) — (\d+(?:[.,]\d+)?) из (\d+(?:[.,]\d+)?) ₽$""").find(m.content) ?: return@mapNotNull null
        Goal(m.id, r.groupValues[1], r.groupValues[2].replace(',', '.').toDouble(), r.groupValues[3].replace(',', '.').toDouble())
    }

    suspend fun find(name: String): Goal? = all().firstOrNull { sameName(it.name, name) }

    suspend fun create(name: String, target: Double): Goal {
        val old = find(name)
        old?.let { memories.delete(it.id) }
        return save(Goal("", old?.name ?: name, old?.saved ?: 0.0, target))
    }

    suspend fun add(goal: Goal, delta: Double): Goal {
        memories.delete(goal.id)
        return save(goal.copy(saved = (goal.saved + delta).coerceAtLeast(0.0)))
    }

    suspend fun remove(goal: Goal) { memories.delete(goal.id) }

    private suspend fun save(g: Goal): Goal {
        val m = memories.create("Цель: ${g.name} — ${Calculator.format(round(g.saved))} из ${Calculator.format(round(g.target))} ₽", CATEGORY)
        return g.copy(id = m.id)
    }

    private fun round(v: Double) = Math.round(v * 100) / 100.0

    companion object {
        const val CATEGORY = "Цели"

        fun money(v: Double): String = "${Calculator.format(Math.round(v * 100) / 100.0)} ₽"

        fun describe(g: Goal): String =
            if (g.saved >= g.target) "Цель ${RuFormat.quote(g.name)} достигнута: ${money(g.saved)} из ${money(g.target)}!"
            else "${RuFormat.quote(g.name)}: ${money(g.saved)} из ${money(g.target)} (${g.percent}%), осталось ${money(g.left)}."
    }
}
