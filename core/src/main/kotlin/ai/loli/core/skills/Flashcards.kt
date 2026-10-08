package ai.loli.core.skills

import ai.loli.core.assistant.RuFormat
import ai.loli.core.domain.MemoryRepository
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.nlp.TextAnalysis
import java.time.LocalDate

/**
 * Карточки для учёбы: «запомни слово apple — яблоко», «проверь меня по словам».
 * Повторение по Лейтнеру: угадали — карточка уходит в следующую коробку и спрашивается реже, ошиблись — снова завтра.
 * Хранятся в памяти (категория «Карточки»): «apple — яблоко · 2 · 2026-10-10».
 */
class Flashcards(private val memories: MemoryRepository) {
    data class Card(val id: String, val front: String, val back: String, val box: Int, val due: LocalDate)

    sealed interface Command {
        data class Add(val front: String, val back: String) : Command
        data object Quiz : Command
        data object Count : Command
        data class Remove(val front: String) : Command
    }

    companion object {
        const val CATEGORY = "Карточки"
        private val INTERVALS = listOf(1L, 2L, 4L, 7L, 14L)
        private fun rx(p: String) = Rx.of(p)

        fun parse(text: String): Command? {
            val raw = text.trim().trimEnd('.', '!')
            val n = RuTokenizer.normalize(raw).trimEnd('?')
            rx("""^(?:запомни|добавь|запиши|выучи|новая|новое)\s+(?:новое\s+|новую\s+)?(?:слово|карточку|карточка|перевод|термин|фразу)[:,]?\s+(.+?)\s*(?:—|–|-|:|\s+это\s+|\s+значит\s+|\s+переводится(?:\s+как)?\s+|\s+по-русски\s+)\s*(.+)$""").find(n)?.let { m ->
                val front = original(raw, m.groupValues[1]).trim().trim('«', '»', '"')
                val back = original(raw, m.groupValues[2]).trim().trim('«', '»', '"')
                if (front.isEmpty() || back.isEmpty() || front.length > 80 || back.length > 120) return null
                return Command.Add(front, back)
            }
            if (rx("""^(?:проверь|проверяй|потренируй|погоняй|поспрашивай|спроси|экзаменуй)\s+меня(?:\s+по\s+(?:словам|карточкам|английскому|словарю|терминам))?$|^(?:давай\s+)?(?:повторим|повторять|учить|поучим)\s+(?:слова|карточки|английский)$|^(?:тренировка|повторение)\s+(?:слов|карточек)$|^карточки$""").containsMatchIn(n)) return Command.Quiz
            if (rx("""^сколько\s+(?:у\s+меня\s+)?(?:карточек|слов\s+(?:в\s+карточках|на\s+повторение|выучено|я\s+учу))$|^мои\s+карточки$""").containsMatchIn(n)) return Command.Count
            rx("""^(?:удали|убери)\s+(?:карточку|слово\s+из\s+карточек)\s+(.+)$""").find(n)?.let { return Command.Remove(original(raw, it.groupValues[1]).trim()) }
            return null
        }

        private fun original(text: String, part: String): String {
            val i = RuTokenizer.normalize(text).indexOf(part)
            return if (i >= 0 && i + part.length <= text.length) text.substring(i, i + part.length) else part
        }

        /** Ответ засчитан: совпадение без учёта регистра и «ё», или все значимые слова похожи, или одна опечатка в коротком слове. */
        fun correct(answer: String, expected: String): Boolean {
            fun clean(s: String) = RuTokenizer.normalize(s).replace(Regex("""[^\p{L}\d ]"""), " ").replace(Regex("""\s+"""), " ").trim()
            val a = clean(answer).removePrefix("это ").removePrefix("наверное ").removePrefix("по моему ").trim()
            val e = clean(expected)
            if (a.isEmpty()) return false
            // Несколько вариантов перевода через запятую: подходит любой.
            val options = expected.split(',', ';', '/').map(::clean).filter { it.isNotEmpty() } + e
            return options.any { o ->
                a == o || TextAnalysis.levenshtein(a, o) <= (if (o.length >= 6) 1 else 0) || run {
                    val want = TextAnalysis.stems(o)
                    val have = TextAnalysis.stems(a)
                    want.isNotEmpty() && want.all { w -> have.any { TextAnalysis.stemSimilarity(w, it) >= 0.75 } }
                }
            }
        }
    }

    suspend fun all(): List<Card> = memories.all().filter { it.category == CATEGORY }.mapNotNull { m ->
        val parts = m.content.split(" · ")
        val pair = parts[0]
        val i = pair.indexOf(" — ")
        if (i <= 0) return@mapNotNull null
        Card(
            m.id, pair.substring(0, i), pair.substring(i + 3),
            parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 1,
            parts.getOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.MIN,
        )
    }

    suspend fun add(front: String, back: String, today: LocalDate): Boolean {
        val old = all().firstOrNull { RuTokenizer.normalize(it.front) == RuTokenizer.normalize(front) }
        old?.let { memories.delete(it.id) }
        memories.create("$front — $back · 1 · $today", CATEGORY)
        return old != null
    }

    suspend fun remove(front: String): Int {
        val hits = all().filter { RuTokenizer.normalize(it.front) == RuTokenizer.normalize(front) || RuTokenizer.normalize(it.back) == RuTokenizer.normalize(front) }
        hits.forEach { memories.delete(it.id) }
        return hits.size
    }

    /** Отметить ответ: угадали — в следующую коробку, нет — в первую, повтор завтра. */
    suspend fun grade(card: Card, ok: Boolean, today: LocalDate) {
        val box = if (ok) (card.box + 1).coerceAtMost(5) else 1
        val due = today.plusDays(INTERVALS[box - 1])
        memories.delete(card.id)
        memories.create("${card.front} — ${card.back} · $box · $due", CATEGORY)
    }

    /** Что повторять сегодня: сначала просроченные, потом самые «слабые»; если всё выучено — несколько старых для закрепления. */
    suspend fun dueToday(today: LocalDate, limit: Int = 10): List<Card> {
        val all = all()
        val due = all.filter { !it.due.isAfter(today) }.sortedWith(compareBy({ it.box }, { it.due }))
        return (if (due.isNotEmpty()) due else all.sortedBy { it.due }).take(limit)
    }
}

/** Идущая проверка по карточкам. */
class QuizSession(val cards: List<Flashcards.Card>) {
    var index = 0
        private set
    var right = 0
        private set
    val current: Flashcards.Card? get() = cards.getOrNull(index)
    val finished: Boolean get() = index >= cards.size

    fun question(): String {
        val c = current ?: return ""
        val latin = Regex("""[a-zA-Z]""").containsMatchIn(c.front)
        return "${index + 1} из ${cards.size}. " + if (latin) "Как переводится ${RuFormat.quote(c.front)}?" else "${RuFormat.quote(c.front)} — это?"
    }

    fun answer(ok: Boolean) { if (ok) right++; index++ }

    fun score(): String = "Итог: ${right} из ${cards.size}" + when {
        cards.isEmpty() -> "."
        right == cards.size -> " — отлично!"
        right * 2 >= cards.size -> " — хорошо. Ошибки спрошу завтра снова."
        else -> ". Ничего, повторение — мать учения: ошибки спрошу завтра."
    }
}
