package ai.loli.core.review

import ai.loli.core.assistant.RuFormat
import ai.loli.core.domain.ConversationRepository
import ai.loli.core.model.MessageRole
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.util.TimeSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** «Что я говорила про ремонт на прошлой неделе», «о чём мы говорили вчера» — поиск по истории разговора. */
class ChatSearch(private val conversations: ConversationRepository, private val time: TimeSource) {
    data class Ask(val query: String?, val from: LocalDate?, val to: LocalDate?)

    companion object {
        private const val PERIOD = """(вчера|сегодня|позавчера|на\s+прошлой\s+неделе|на\s+этой\s+неделе|на\s+неделе|за\s+неделю|в\s+этом\s+месяце|в\s+прошлом\s+месяце|за\s+месяц|недавно)"""
        private val ABOUT = Rx.of("""^(?:а\s+)?(?:что|когда)\s+(?:я\s+)?(?:тебе\s+)?(?:говорил|говорила|говорили|рассказывал|рассказывала|спрашивал|спрашивала|писал|писала|упоминал|упоминала)\s+(?:тебе\s+)?(?:про|о|об|насчет|насчёт)\s+(.+?)(?:\s+$PERIOD)?$""")
        private val FIND = Rx.of("""^(?:найди|поищи|покажи)\s+(?:в\s+)?(?:наших\s+)?(?:разговорах|переписке|истории\s+(?:чата|разговоров)|чате|диалогах)\s+(?:про\s+|о\s+|об\s+)?(.+?)(?:\s+$PERIOD)?$""")
        private val TALKED = Rx.of("""^(?:о\s+ч[её]м|про\s+что)\s+мы\s+(?:с\s+тобой\s+)?(?:говорили|разговаривали|болтали)\s+$PERIOD$""")

        fun parse(text: String, today: LocalDate): Ask? {
            val n = RuTokenizer.normalize(text).trim().trimEnd('?', '.', '!')
            ABOUT.find(n)?.let { m -> return withPeriod(m.groupValues[1], m.groupValues[2], today) }
            FIND.find(n)?.let { m -> return withPeriod(m.groupValues[1], m.groupValues[2], today) }
            TALKED.find(n)?.let { m -> return withPeriod(null, m.groupValues[1], today) }
            return null
        }

        private fun withPeriod(query: String?, period: String, today: LocalDate): Ask {
            val p = period.replace(Regex("""\s+"""), " ")
            val (from, to) = when {
                p == "сегодня" -> today to today
                p == "вчера" -> today.minusDays(1) to today.minusDays(1)
                p == "позавчера" -> today.minusDays(2) to today.minusDays(2)
                p == "на прошлой неделе" -> today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
                p == "на этой неделе" || p == "на неделе" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
                p == "за неделю" || p == "недавно" -> today.minusDays(6) to today
                p == "в этом месяце" -> today.withDayOfMonth(1) to today
                p == "в прошлом месяце" -> today.minusMonths(1).withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
                p == "за месяц" -> today.minusDays(29) to today
                else -> null to null
            }
            return Ask(query?.trim()?.takeIf { it.isNotEmpty() }, from, to)
        }
    }

    suspend fun search(ask: Ask): String {
        val zone = time.zone()
        val today = time.today()
        val all = conversations.recent(5000).filter { it.role == MessageRole.USER }
            .filter { m ->
                val d = m.createdAt.atZone(zone).toLocalDate()
                (ask.from == null || !d.isBefore(ask.from)) && (ask.to == null || !d.isAfter(ask.to))
            }
        val period = when {
            ask.from == null -> ""
            ask.from == ask.to -> " " + RuFormat.date(ask.from, today).removePrefix("в ")
            else -> " с ${ask.from.dayOfMonth}.${"%02d".format(ask.from.monthValue)} по ${ask.to!!.dayOfMonth}.${"%02d".format(ask.to.monthValue)}"
        }
        val hits = if (ask.query == null) all.sortedByDescending { it.createdAt }.take(8) else {
            val want = TextAnalysis.stems(ask.query)
            if (want.isEmpty()) return "Про что искать? Скажите, например: «что я говорила про ремонт»."
            all.map { m ->
                val have = TextAnalysis.stems(m.content)
                m to want.count { w -> have.any { TextAnalysis.stemSimilarity(w, it) >= 0.75 } }
            }.filter { it.second > 0 && (want.size == 1 || it.second * 2 >= want.size) }
                .sortedWith(compareByDescending<Pair<ai.loli.core.model.ConversationMessage, Int>> { it.second }.thenByDescending { it.first.createdAt })
                .map { it.first }.take(5)
        }
        if (hits.isEmpty()) {
            return if (ask.query == null) "Разговоров$period не нашла." else "Не нашла в разговорах$period ничего про ${RuFormat.quote(ask.query)}."
        }
        val head = if (ask.query == null) "Вы говорили$period:" else "Про ${RuFormat.quote(ask.query)} вы говорили$period:"
        return head + "\n" + hits.sortedBy { it.createdAt }.joinToString("\n") { m ->
            val d = m.createdAt.atZone(zone)
            val day = RuFormat.date(d.toLocalDate(), today).removePrefix("в ")
            "• $day в ${RuFormat.time(d.toLocalTime())}: ${RuFormat.quote(m.content.take(120))}"
        }
    }
}
