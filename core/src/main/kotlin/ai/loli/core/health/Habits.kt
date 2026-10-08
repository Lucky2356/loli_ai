package ai.loli.core.health

import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.RelaxKind
import ai.loli.core.assistant.RuFormat
import ai.loli.core.db.LoliDatabase
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.util.Ids
import ai.loli.core.util.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import ai.loli.core.db.Habit_log as HabitRow

/** Запись журнала: стакан воды, принятая таблетка, отметка привычки. */
data class HabitEntry(val id: String, val kind: String, val name: String, val amount: Double, val at: Instant)

/** Журнал привычек и здоровья. Только на телефоне: это личные данные, в облако не уходят. */
class HabitStore(private val db: LoliDatabase, private val dispatcher: CoroutineDispatcher) {
    private val q get() = db.habitLogQueries

    suspend fun log(kind: String, name: String, amount: Double, at: Instant): HabitEntry = withContext(dispatcher) {
        val e = HabitEntry(Ids.newId(), kind, name, amount, at)
        q.insert(HabitRow(e.id, e.kind, e.name, e.amount, e.at.toEpochMilli()))
        e
    }

    suspend fun since(from: Instant, kind: String? = null): List<HabitEntry> = withContext(dispatcher) {
        (if (kind == null) q.selectSince(from.toEpochMilli()).executeAsList() else q.selectKindSince(kind, from.toEpochMilli()).executeAsList())
            .map { HabitEntry(it.id, it.kind, it.name, it.amount, Instant.ofEpochMilli(it.at)) }
    }

    suspend fun delete(id: String) = withContext(dispatcher) { q.deleteById(id); Unit }

    /** Восстановление записи из резервной копии (тот же id заменяется). */
    suspend fun restore(e: HabitEntry) = withContext(dispatcher) { q.restore(HabitRow(e.id, e.kind, e.name, e.amount, e.at.toEpochMilli())); Unit }

    suspend fun wipe() = withContext(dispatcher) { q.deleteAll(); Unit }
}

/** Что сказал человек про привычки и здоровье. */
sealed interface HabitCommand {
    data class Water(val glasses: Double) : HabitCommand
    data object WaterToday : HabitCommand
    data class WaterGoal(val glasses: Int) : HabitCommand
    data class Pill(val name: String) : HabitCommand
    data class PillAsk(val name: String?) : HabitCommand
    data class Mark(val habit: String) : HabitCommand
    data class Streak(val habit: String) : HabitCommand
    data object Summary : HabitCommand
    data object UndoLast : HabitCommand
    data class Relax(val kind: RelaxKind, val minutes: Int) : HabitCommand
    /** Своё событие для обратного отсчёта: «запомни, что отпуск 15 июля». */
    data class EventSave(val name: String, val date: LocalDate) : HabitCommand
    data class Countdown(val name: String) : HabitCommand
    data object Events : HabitCommand
}

object HabitPhrases {
    private fun rx(p: String) = ai.loli.core.nlp.Rx.of(p)

    /** Привычки, которые узнаём в «я сделала зарядку», «отметь пробежку» (основа слова → как называть). */
    val KNOWN = linkedMapOf(
        "зарядк" to "зарядка", "пробежк" to "пробежка", "тренировк" to "тренировка", "йог" to "йога", "растяжк" to "растяжка",
        "прогулк" to "прогулка", "медитаци" to "медитация", "чтени" to "чтение", "отжимани" to "отжимания", "приседани" to "приседания",
        "планк" to "планка", "английск" to "английский", "уборк" to "уборка", "дневник" to "дневник", "пресс" to "пресс",
        "бассейн" to "бассейн", "спортзал" to "спортзал", "велосипед" to "велосипед", "учеб" to "учёба", "уход за кож" to "уход за кожей",
    )

    private val DRINK = """(?:выпил[аи]?|попил[аи]?|выпью|пью|выдула|выдул)"""
    private val GLASS_WORDS = """(?:стакан\S*|кружк\S*|чашк\S*|бутылк\S*|литр\S*|пол-?литр\S*|полстакана|пол стакана|мл|миллилитр\S*|л)"""

    private val MONTHS = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private const val DATE = """(\d{1,2}\s+(?:январ|феврал|март|апрел|ма[яйе]|июн|июл|август|сентябр|октябр|ноябр|декабр)\S*(?:\s+\d{4})?|\d{1,2}\.\d{1,2}(?:\.\d{2,4})?)"""

    private fun date(s: String, today: LocalDate): LocalDate? {
        Regex("""^(\d{1,2})\s+(\S+)(?:\s+(\d{4}))?$""").find(s.trim())?.let { m ->
            val idx = MONTHS.indexOfFirst { m.groupValues[2].startsWith(it) }.takeIf { it >= 0 } ?: return null
            val year = m.groupValues[3].toIntOrNull()
            val d = runCatching { LocalDate.of(year ?: today.year, idx + 1, m.groupValues[1].toInt()) }.getOrNull() ?: return null
            return if (year == null && d.isBefore(today)) d.plusYears(1) else d
        }
        Regex("""^(\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?$""").find(s.trim())?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val d = runCatching { LocalDate.of(y ?: today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return null
            return if (y == null && d.isBefore(today)) d.plusYears(1) else d
        }
        return null
    }

    fun parse(text: String, today: LocalDate = LocalDate.now()): HabitCommand? {
        val raw = text.trim()
        val t = DevicePhrases.digitize(RuTokenizer.normalize(raw).trim().trimEnd('.', '!', '?', ',')).replace(Regex("""\s+"""), " ")

        // Свои события и обратный отсчёт.
        (rx("""^(?:запомни|запиши|сохрани)[,:]?\s+что\s+(?:у меня\s+|у нас\s+)?(?:мой\s+|моя\s+|мое\s+|наш\s+|наша\s+|наше\s+)?(.+?)\s+(?:будет\s+|начинается\s+|начнется\s+|состоится\s+|назначен\S*\s+)?(?:на\s+)?$DATE$""").find(t)
            ?: rx("""^(?:мой|моя|мое|наш|наша|наше)\s+(\S+(?:\s+\S+)?)\s+(?:будет\s+|начинается\s+|начнется\s+)?$DATE$""").find(t))?.let { m ->
            val name = m.groupValues[1].trim()
            val d = date(m.groupValues[2], today)
            if (d != null && name.isNotEmpty() && !rx("""рождени|^др$|днюх""").containsMatchIn(name)) return HabitCommand.EventSave(name, d)
        }
        rx("""^(?:а\s+)?сколько\s+(?:еще\s+)?(?:осталось\s+)?(?:дней\s+|день\s+|времени\s+)?(?:осталось\s+)?до\s+(?:моего\s+|моей\s+|нашего\s+|нашей\s+|моих\s+|наших\s+)?(.+)$|^через сколько\s+(?:дней\s+)?(?:у меня\s+|будет\s+)?(.+)$""").find(t)?.let { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
            if (name.isNotEmpty()) return HabitCommand.Countdown(name)
        }
        if (rx("""^(?:мои|какие)\s+(?:события|даты)(?:\s+(?:я\s+)?(?:запомнил[аи]?|записал[аи]?))?$|^что\s+(?:у меня\s+)?(?:впереди|запланировано из событий)$""").containsMatchIn(t)) return HabitCommand.Events
        val question = raw.trimEnd().endsWith("?") || rx("""(?:^|\s)ли(?:\s|$)""").containsMatchIn(t)

        // Дыхание и медитация.
        if (rx("""^(?:стоп|хватит|останови|закончи|прекрати)\s+(?:дыхани\S*|медитаци\S*|упражнени\S*)|^(?:останови|выключи)\s+(?:медитацию|дыхательное упражнение)""").containsMatchIn(t)) {
            return HabitCommand.Relax(RelaxKind.STOP, 0)
        }
        val minutes = rx("""(\d+)\s*(?:минут|мин)""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 60)
        if (rx("""^(?:давай\s+)?(?:помедитируем|помедитировать|медитаци[яю]|медитируем|включи медитацию|начни медитацию|хочу помедитировать|давай медитацию)(?:\s|$)""").containsMatchIn(t) && !t.startsWith("медитация сделана")) {
            return HabitCommand.Relax(RelaxKind.MEDITATION, minutes ?: 5)
        }
        if (rx("""^(?:давай\s+)?(?:подышим|подышать|сделаем дыхательн\S*|дыхательн\S*\s+(?:упражнени|гимнастик|практик)\S*|включи дыхательн\S*|помоги\s+(?:мне\s+)?(?:успокоиться|расслабиться|уснуть)|хочу\s+(?:успокоиться|расслабиться)|дыхание 4 7 8|техника дыхания)""").containsMatchIn(t)) {
            return HabitCommand.Relax(RelaxKind.BREATHING, minutes ?: 2)
        }

        // Отмена последней отметки.
        if (rx("""^(?:отмени|удали|убери)\s+(?:последн\S+\s+)?(?:отметк\S*|стакан\S*|запись о воде|воду)$""").containsMatchIn(t)) return HabitCommand.UndoLast

        // Вода.
        rx("""(?:норма|цель)\s+(?:воды\s+)?(?:в день\s+)?(\d+)\s+стакан""").find(t)?.let { return HabitCommand.WaterGoal(it.groupValues[1].toInt().coerceIn(1, 30)) }
        if (rx("""сколько\s+(?:я\s+)?(?:сегодня\s+)?(?:воды\s+)?(?:я\s+)?(?:выпил[аи]?|попил[аи]?)(?:\s+(?:сегодня|воды))*$|сколько\s+(?:сегодня\s+)?(?:стаканов|воды)\s+(?:я\s+)?(?:выпил[аи]?|попил[аи]?)|^(?:моя\s+)?(?:норма|статистика)\s+воды|^(?:сколько\s+)?(?:мне\s+)?(?:еще|ещё)\s+(?:пить|выпить)\s+воды|^как\s+(?:у меня\s+)?с\s+водой""").containsMatchIn(t)) {
            return HabitCommand.WaterToday
        }
        val water = rx("""^(?:я\s+)?(?:(?:только что|сейчас|уже)\s+)?(?:$DRINK|отметь|запиши|засчитай|добавь)\s+(?:еще\s+|ещё\s+)?(?:(\d+(?:[.,]\d+)?|один|одну|пол|полтора|полторы)\s+)?($GLASS_WORDS)?\s*(?:воды|водички|водицы|воду)?$""").find(t)
        if (water != null && (t.contains("вод") || (water.groupValues[2].isNotEmpty() && rx("""^(?:я\s+)?$DRINK""").containsMatchIn(t) && !rx("""кофе|чая|чай|сока|молока|пива|вина|кефира""").containsMatchIn(t)))) {
            val unit = water.groupValues[2]
            if (unit.isNotEmpty() || t.contains("вод")) return HabitCommand.Water(glasses(water.groupValues[1], unit))
        }
        (Regex("""^\+\s*(\d+)\s+стакан""").find(raw.lowercase()) ?: rx("""^плюс\s*(\d+)\s+стакан""").find(t))?.let { return HabitCommand.Water(it.groupValues[1].toDouble()) }

        // Лекарства.
        val med = """(таблетк\S*|лекарств\S*|витамин\S*|пилюл\S*|капл\S*|сироп\S*|антибиотик\S*|омег\S*|магни\S*|железо|кальци\S*|противозачаточн\S*|от давления|от аллергии)"""
        if (rx("""^(?:а\s+)?(?:я\s+)?(?:сегодня\s+)?(?:принимал[аи]?|пил[аи]?|выпил[аи]?|приняла?|принял)\s+(?:ли\s+)?(?:я\s+)?(?:сегодня\s+)?(?:свои\s+|мои\s+)?$med""").containsMatchIn(t) &&
            (question || rx("""принимал|^(?:а\s+)?(?:я\s+)?(?:сегодня\s+)?пил[аи]?\s""").containsMatchIn(t))) {
            return HabitCommand.PillAsk(rx("""$med(?:\s+(?!сегодня)(\S+(?:\s+\S+)?))?""").find(t)?.value?.let { pillName(it) })
        }
        if (rx("""^(?:когда\s+я\s+(?:последний раз\s+)?(?:принимал[аи]?|пил[аи]?)|я\s+(?:сегодня\s+)?(?:уже\s+)?(?:принимал[аи]?|пил[аи]?)\s+таблетк)""").containsMatchIn(t)) {
            return HabitCommand.PillAsk(null)
        }
        rx("""^(?:я\s+)?(?:(?:только что|сейчас|уже)\s+)?(?:приняла?|принял|выпил[аи]?|выпил|отметь|запиши)\s+(?:свою\s+|свои\s+|мою\s+|мои\s+|утреннюю\s+|вечернюю\s+)?(.*$med.*)$""").find(t)?.let { m ->
            if (!question) return HabitCommand.Pill(pillName(m.groupValues[1]))
        }

        // Привычки.
        rx("""^(?:отметь|засчитай)\s+(?:привычку\s+)?(?:что\s+я\s+(?:сделал[аи]?\s+)?)?(.+?)(?:\s+(?:сегодня|выполнен\S*|сделан\S*))?$""").find(t)?.let { m ->
            val h = habitName(m.groupValues[1]) ?: if (t.contains("привычку")) m.groupValues[1] else null
            if (h != null && !rx("""календар|напоминани|задач|список|покупк|купленн""").containsMatchIn(m.groupValues[1])) return HabitCommand.Mark(h)
        }
        rx("""^(?:я\s+)?(?:сегодня\s+)?(?:уже\s+)?(?:сделал[аи]?|сделал|сходил[аи]?\s+(?:на|в)|позанимал[аи]?сь|провел[аи]?|прошел|прошла|почитал[аи]?|позанимался)\s+(.+?)(?:\s+сегодня)?$""").find(t)?.let { m ->
            habitName(m.groupValues[1])?.let { if (!question) return HabitCommand.Mark(it) }
        }
        if (rx("""^(?:я\s+)?(?:сегодня\s+)?(?:побегал[аи]?|пробежал[аи]?|помедитировал[аи]?|позанимался спортом|позанималась спортом|сделал[аи]? зарядку)$""").containsMatchIn(t)) {
            return HabitCommand.Mark(when {
                t.contains("бег") || t.contains("бежал") -> "пробежка"
                t.contains("медит") -> "медитация"
                t.contains("зарядк") -> "зарядка"
                else -> "тренировка"
            })
        }
        rx("""сколько\s+(?:дней\s+)?подряд\s+(?:я\s+)?(?:делаю|делала|делал|занимаюсь|хожу на|хожу в)?\s*(.+)$|^(?:моя\s+)?серия\s+(.+)$|^как\s+(?:у меня\s+)?(?:дела\s+)?с\s+(.+)$""").find(t)?.let { m ->
            val what = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            habitName(what)?.let { return HabitCommand.Streak(it) }
        }
        if (rx("""^(?:мои\s+привычки|статистика\s+(?:привычек|здоровья)|мой день здоровья|как мои привычки|что я сегодня отметил[аи]?|трекер привычек|привычки)(?:\s+за\s+(?:неделю|сегодня|день))?$""").containsMatchIn(t)) return HabitCommand.Summary
        return null
    }

    private fun glasses(n: String, unit: String): Double {
        val count = when (n) { "", "один", "одну" -> 1.0; "пол" -> 0.5; "полтора", "полторы" -> 1.5; else -> n.replace(',', '.').toDoubleOrNull() ?: 1.0 }
        return when {
            unit.startsWith("пол") && unit.contains("литр") -> 2.0
            unit.startsWith("полстакан") || unit.startsWith("пол стакан") -> 0.5
            unit == "мл" || unit.startsWith("миллилитр") -> count / 250
            unit == "л" || unit.startsWith("литр") -> count * 4
            unit.startsWith("бутылк") -> count * 2
            else -> count
        }
    }

    fun habitName(s: String): String? {
        val t = s.trim().lowercase().replace('ё', 'е')
        return KNOWN.entries.firstOrNull { (stem, _) -> Regex("""(?<![\p{L}])$stem""").containsMatchIn(t) }?.value
    }

    private fun pillName(s: String): String = s.trim()
        .replace(Regex("""^таблетк\S*"""), "таблетка").replace(Regex("""^витамин\S*"""), "витамин").replace(Regex("""^лекарств\S*"""), "лекарство")
        .replace(Regex("""\s+(?:сегодня|утром|вечером|уже)$"""), "").trim()
}

/** Навык «Привычки и здоровье». */
class Habits(private val store: HabitStore, private val time: TimeSource) {
    /** Давление, вес, сон, счётчики — в том же журнале. */
    val vitals: Vitals by lazy { Vitals(store, time) }

    data class Reply(val text: String, val device: DeviceCommand? = null, val changed: Boolean = false, val private: Boolean = true)

    private var lastLogged: HabitEntry? = null

    suspend fun handle(text: String): Reply? {
        val cmd = HabitPhrases.parse(text, time.today()) ?: return null
        return run(cmd)
    }

    /** Отметка только что сделана — «отмени последнее» относится к ней. */
    fun recentlyLogged(): Boolean = lastLogged?.let { Duration.between(it.at, time.now()) <= Duration.ofMinutes(3) } == true

    /** null — команда не про наши данные («сколько дней до Нового года» — ответит календарь). */
    suspend fun run(cmd: HabitCommand): Reply? {
        val now = time.now()
        val dayStart = time.today().atStartOfDay(time.zone()).toInstant()
        return when (cmd) {
            is HabitCommand.Water -> {
                lastLogged = store.log(WATER, "вода", cmd.glasses, now)
                val total = store.since(dayStart, WATER).sumOf { it.amount }
                val goal = goal()
                val tail = if (total >= goal) " Норма на сегодня выполнена!" else " Осталось ${fmt(goal - total)} до нормы."
                Reply("Записала: +${glassesText(cmd.glasses)}. Сегодня ${fmt(total)} из $goal.$tail", changed = true)
            }
            HabitCommand.WaterToday -> {
                val total = store.since(dayStart, WATER).sumOf { it.amount }
                val goal = goal()
                Reply(when {
                    total == 0.0 -> "Сегодня воды ещё не отмечали. Норма — $goal стаканов. Скажите «выпила стакан воды», и я запишу."
                    total >= goal -> "Сегодня — ${glassesText(total)} воды из $goal. Норма выполнена!"
                    else -> "Сегодня — ${glassesText(total)} воды из $goal. Ещё ${fmt(goal - total)} до нормы."
                })
            }
            is HabitCommand.WaterGoal -> {
                store.log(GOAL, "вода", cmd.glasses.toDouble(), now)
                Reply("Хорошо, норма воды — ${glassesText(cmd.glasses.toDouble())} в день.", changed = true)
            }
            is HabitCommand.Pill -> {
                lastLogged = store.log(PILL, cmd.name, 1.0, now)
                Reply("Отметила: ${cmd.name}, ${RuFormat.time(now.atZone(time.zone()).toLocalTime())}.", changed = true)
            }
            is HabitCommand.PillAsk -> {
                val today = store.since(dayStart, PILL).filter { cmd.name == null || similar(it.name, cmd.name) }
                if (today.isNotEmpty()) {
                    Reply("Да, сегодня: " + today.joinToString(", ") { "${it.name} в ${RuFormat.time(it.at.atZone(time.zone()).toLocalTime())}" } + ".")
                } else {
                    val last = store.since(now.minus(Duration.ofDays(30)), PILL).lastOrNull { cmd.name == null || similar(it.name, cmd.name) }
                    Reply("Сегодня отметок нет." + (last?.let { " Последний раз — ${day(it.at)}: ${it.name}." } ?: " Скажите «я приняла таблетку», и я запишу."))
                }
            }
            is HabitCommand.Mark -> {
                val already = store.since(dayStart, HABIT).any { it.name == cmd.habit }
                if (!already) lastLogged = store.log(HABIT, cmd.habit, 1.0, now)
                val streak = streak(cmd.habit)
                Reply((if (already) "${cmd.habit.replaceFirstChar { it.uppercase() }} сегодня уже отмечена." else "Отметила: ${cmd.habit}.") +
                    if (streak > 1) " Серия — ${RuFormat.count(streak, "день", "дня", "дней")} подряд!" else "", changed = !already)
            }
            is HabitCommand.Streak -> {
                val s = streak(cmd.habit)
                Reply(if (s == 0) "Пока нет серии: «${cmd.habit}» за последние дни не отмечена." else "${cmd.habit.replaceFirstChar { it.uppercase() }}: ${RuFormat.count(s, "день", "дня", "дней")} подряд.")
            }
            HabitCommand.Summary -> {
                val today = store.since(dayStart)
                val water = today.filter { it.kind == WATER }.sumOf { it.amount }
                val pills = today.filter { it.kind == PILL }
                val habits = today.filter { it.kind == HABIT }.map { it.name }.distinct()
                val week = store.since(dayStart.minus(Duration.ofDays(6)), HABIT).groupBy { it.name }.mapValues { e -> e.value.map { day(it.at) }.distinct().size }
                val lines = buildList {
                    add("• Вода: ${glassesText(water)} из ${goal()}")
                    add("• Лекарства: " + if (pills.isEmpty()) "не отмечены" else pills.joinToString(", ") { it.name })
                    add("• Привычки сегодня: " + if (habits.isEmpty()) "пока ничего" else habits.joinToString(", "))
                    if (week.isNotEmpty()) add("• За неделю: " + week.entries.joinToString(", ") { "${it.key} — ${RuFormat.count(it.value, "день", "дня", "дней")}" })
                }
                Reply("Ваш день:\n" + lines.joinToString("\n"))
            }
            HabitCommand.UndoLast -> {
                val last = lastLogged ?: store.since(dayStart).lastOrNull { it.kind != GOAL && it.kind != EVENT } ?: return Reply("Сегодня отметок нет — нечего отменять.")
                store.delete(last.id)
                lastLogged = null
                Reply("Убрала отметку: ${if (last.kind == WATER) glassesText(last.amount) + " воды" else last.name}.", changed = true)
            }
            is HabitCommand.EventSave -> {
                store.log(EVENT, cmd.name, cmd.date.toEpochDay().toDouble(), now)
                val days = java.time.temporal.ChronoUnit.DAYS.between(time.today(), cmd.date)
                Reply("Запомнила: ${cmd.name} — ${RuFormat.date(cmd.date, time.today())}. Это через ${RuFormat.count(days.toInt(), "день", "дня", "дней")}. Спросите «сколько дней до ${cmd.name.substringBefore(' ')}».", changed = true)
            }
            is HabitCommand.Countdown -> {
                val today = time.today()
                val events = store.since(Instant.EPOCH, EVENT).groupBy { it.name }.map { it.value.last() }
                    .filter { LocalDate.ofEpochDay(it.amount.toLong()) >= today }
                val hit = events.firstOrNull { similar(it.name, cmd.name) }
                if (hit != null) {
                    val d = LocalDate.ofEpochDay(hit.amount.toLong())
                    val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
                    return Reply(when (days) {
                        0L -> "${hit.name.replaceFirstChar { it.uppercase() }} — сегодня!"
                        1L -> "${hit.name.replaceFirstChar { it.uppercase() }} — уже завтра!"
                        else -> "До события «${hit.name}» — ${RuFormat.count(days.toInt(), "день", "дня", "дней")} (${RuFormat.date(d, today)})."
                    })
                }
                val (d, title) = ai.loli.core.skills.Almanac.findTitled(cmd.name, today) ?: return null
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
                Reply(if (days == 0L) "$title — сегодня!" else "До праздника «$title» — ${RuFormat.count(days.toInt(), "день", "дня", "дней")} (${RuFormat.date(d, today)}).", private = false)
            }
            HabitCommand.Events -> {
                val today = time.today()
                val events = store.since(Instant.EPOCH, EVENT).groupBy { it.name }.map { it.value.last() }
                    .map { it.name to LocalDate.ofEpochDay(it.amount.toLong()) }.filter { it.second >= today }.sortedBy { it.second }
                Reply(if (events.isEmpty()) "Событий не записано. Скажите, например: «запомни, что отпуск 15 июля»."
                else "Впереди:\n" + events.joinToString("\n") { (n, d) -> "• $n — ${RuFormat.date(d, today)}" })
            }
            is HabitCommand.Relax -> when (cmd.kind) {
                RelaxKind.STOP -> Reply("Останавливаю.", DeviceCommand.Relax(RelaxKind.STOP, 0), private = false)
                RelaxKind.BREATHING -> Reply(
                    "Дышим по схеме 4-7-8: вдох на 4 счёта, задержка на 7, медленный выдох на 8. ${RuFormat.count(cmd.minutes, "минута", "минуты", "минут")}, я подскажу голосом. Сядьте удобно.",
                    DeviceCommand.Relax(RelaxKind.BREATHING, cmd.minutes), private = false,
                )
                RelaxKind.MEDITATION -> Reply(
                    "Медитация на ${RuFormat.count(cmd.minutes, "минуту", "минуты", "минут")}. Закройте глаза и следите за дыханием — я скажу, когда закончим.",
                    DeviceCommand.Relax(RelaxKind.MEDITATION, cmd.minutes), private = false,
                )
            }
        }
    }

    /** Отменить только что сделанную отметку («отмени последнее» сразу после «выпила воды»). */
    suspend fun undoRecent(): Reply? = if (recentlyLogged()) run(HabitCommand.UndoLast) else null

    /** Разбор фразы (для движка): что это за команда, без выполнения. */
    fun parse(text: String): HabitCommand? = HabitPhrases.parse(text, time.today())

    private suspend fun goal(): Int = store.since(Instant.EPOCH, GOAL).lastOrNull()?.amount?.toInt() ?: 8

    /** Сколько дней подряд отмечена привычка, считая сегодня (или вчера, если сегодня ещё нет). */
    private suspend fun streak(habit: String): Int {
        val days = store.since(time.today().minusDays(400).atStartOfDay(time.zone()).toInstant(), HABIT)
            .filter { it.name == habit }.map { it.at.atZone(time.zone()).toLocalDate() }.toSet()
        var d = time.today()
        if (d !in days) d = d.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    private fun day(at: Instant): String {
        val d = at.atZone(time.zone()).toLocalDate()
        return when (d) {
            time.today() -> "сегодня"
            time.today().minusDays(1) -> "вчера"
            else -> DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru")).format(d)
        }
    }

    private fun similar(a: String, b: String): Boolean {
        val sa = TextAnalysis.stems(a).toSet()
        return TextAnalysis.stems(b).any { it in sa } || a.contains(b) || b.contains(a)
    }

    private fun fmt(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(java.util.Locale("ru"), "%.1f", x)

    private fun glassesText(x: Double): String =
        if (x % 1.0 == 0.0) RuFormat.count(x.toInt(), "стакан", "стакана", "стаканов") else "${fmt(x)} стакана"


    // ------------------------------------------------------------------ Дневник дня

    private var awaitMood = false
    private var pendingMood: Int? = null
    private var diaryAskedAt = time.now()

    private fun diaryFresh(): Boolean = Duration.between(diaryAskedAt, time.now()) <= Duration.ofMinutes(3)

    private fun moodScore(t: String): Int? {
        Regex("""(?<![\d.,])([1-5])(?:\s*(?:из|/)\s*5)?(?![\d.,])""").find(t)?.let { return it.groupValues[1].toInt() }
        val d = ai.loli.core.assistant.DevicePhrases.digitize(t)
        Regex("""(?<![\d.,])([1-5])(?![\d.,])""").find(d)?.let { return it.groupValues[1].toInt() }
        return when {
            Regex("""ужасн|отвратительн|кошмар""").containsMatchIn(t) -> 1
            Regex("""плох|грустн|тяжел|тяжёл|устал""").containsMatchIn(t) -> 2
            Regex("""нормальн|так себе|средн|обычн|неплох""").containsMatchIn(t) -> 3
            Regex("""хорош|добр|good|спокойн""").containsMatchIn(t) -> 4
            Regex("""отличн|прекрасн|великолепн|замечательн|супер|отлично""").containsMatchIn(t) -> 5
            else -> null
        }
    }

    private suspend fun saveMood(score: Int, note: String): String {
        store.log(MOOD, note, score.toDouble(), time.now())
        val saved = "Записала: настроение $score из 5${if (note.isNotBlank()) " — $note" else ""}."
        return when {
            score <= 2 && moodLow() -> "$saved Уже не первый трудный день. Берегите себя: может, отдых пораньше или прогулка? Если захотите — подышим вместе: «давай подышим»."
            score <= 2 -> "$saved Жаль, что день не задался. Завтра будет лучше — а я помогу с делами."
            score >= 5 -> "$saved Отличный день — так держать!"
            else -> saved
        }
    }

    /** Журнал за период [from, to) — для итогов недели и ленты дня. */
    suspend fun log(from: java.time.Instant, to: java.time.Instant): List<HabitEntry> = store.since(from).filter { it.at.isBefore(to) }

    /** Настроение последних дней низкое: хотя бы две оценки за 3 дня, в среднем не выше 2,5. */
    suspend fun moodLow(): Boolean {
        val from = time.today().minusDays(2).atStartOfDay(time.zone()).toInstant()
        val moods = store.since(from, MOOD)
        return moods.size >= 2 && moods.map { it.amount }.average() <= 2.5
    }

    /**
     * Дневник дня: «как прошёл день» → настроение 1–5 → пара слов; «итоги дня»; «настроение за неделю».
     * [tasksDone] — сколько задач закрыто сегодня, [spend] — строка про траты за день (если есть).
     */
    suspend fun diary(text: String, tasksDone: suspend () -> Int, spend: suspend () -> String?, startsCommand: Boolean): Reply? {
        val t = ai.loli.core.nlp.RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?')
        // Ждём пару слов к уже названной оценке.
        pendingMood?.let { score ->
            pendingMood = null
            if (diaryFresh() && !startsCommand) {
                val note = if (Regex("""^(?:нет|не надо|ничего|пропусти|всё|все|хватит)$""").containsMatchIn(t)) "" else text.trim().replaceFirstChar { it.uppercase() }.take(200)
                return Reply(saveMood(score, note), changed = true)
            }
            val saved = saveMood(score, "")
            if (!startsCommand) return Reply(saved, changed = true)
            // Новая команда: оценку сохраняем без слов, а фразу отдаём дальше.
        }
        if (awaitMood) {
            awaitMood = false
            if (diaryFresh()) {
                val score = moodScore(t)
                if (score != null) {
                    pendingMood = score; diaryAskedAt = time.now()
                    return Reply("Оценка $score из 5. Добавите пару слов о дне? Скажите их или «нет».", changed = false)
                }
                if (Regex("""^(?:нет|не надо|отмена|потом|не хочу)$""").containsMatchIn(t)) return Reply("Хорошо, в другой раз.")
            }
        }
        if (Regex("""^(?:как\s+(?:прош[её]л|был)\s+(?:мой\s+|этот\s+)?день|запиши\s+(?:как\s+прош[её]л\s+день|настроение\s+дня)|дневник(?:\s+дня)?|подведи\s+день)$""").containsMatchIn(t)) {
            awaitMood = true; diaryAskedAt = time.now()
            return Reply("Как настроение от 1 до 5?")
        }
        if (Regex("""^(?:как\s+я\s+себя\s+чувствовал[аи]?|какое\s+(?:у\s+меня\s+)?было\s+настроение|настроение|мо[её]\s+настроение)\s*(?:на\s+этой\s+неделе|за\s+неделю|на\s+неделе)$""").containsMatchIn(t)) {
            return Reply(moodWeek())
        }
        Regex("""^(?:моё\s+|мое\s+)?настроение\s+(?:сегодня\s+)?(.+)$""").find(t)?.let { m ->
            val score = moodScore(m.groupValues[1]) ?: return null
            return Reply(saveMood(score, ""), changed = true)
        }
        if (Regex("""^(?:итоги\s+дня|подведи\s+итоги(?:\s+дня)?|как\s+я\s+провел[аи]?\s+день|что\s+(?:я\s+)?сегодня\s+сделал[аи]?)$""").containsMatchIn(t)) {
            return Reply(daySummary(tasksDone(), spend()))
        }
        return null
    }

    /** «Итоги дня» для вечерней сводки — тот же текст, что и по голосовой команде. */
    suspend fun daySummaryText(tasksDone: Int, spend: String?): String = daySummary(tasksDone, spend)

    private suspend fun daySummary(tasksDone: Int, spend: String?): String {
        val from = time.today().atStartOfDay(time.zone()).toInstant()
        val today = store.since(from)
        val mood = today.lastOrNull { it.kind == MOOD }
        val water = today.filter { it.kind == WATER }.sumOf { it.amount }
        val habits = today.filter { it.kind == HABIT }.map { it.name }.distinct()
        val parts = buildList {
            add(mood?.let { "Настроение: ${it.amount.toInt()} из 5${if (it.name.isNotBlank()) " (${it.name})" else ""}." } ?: "Настроение сегодня не записано — скажите «как прошёл день».")
            add(if (tasksDone > 0) "Закрыто задач: $tasksDone." else "Закрытых задач сегодня нет.")
            spend?.let { add(it) }
            if (water > 0) add("Воды: ${glassesText(water)}.")
            if (habits.isNotEmpty()) add("Привычки: ${habits.joinToString(", ")}.")
        }
        return "Итоги дня. " + parts.joinToString(" ")
    }

    private suspend fun moodWeek(): String {
        val from = time.today().minusDays(6).atStartOfDay(time.zone()).toInstant()
        val moods = store.since(from, MOOD)
        if (moods.isEmpty()) return "За неделю настроение не записано. Скажите «как прошёл день» вечером."
        val byDay = moods.groupBy { it.at.atZone(time.zone()).toLocalDate() }.toSortedMap()
        val avg = moods.map { it.amount }.average()
        val days = byDay.entries.joinToString(", ") { (d, list) ->
            "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("ru"))} ${fmt(list.map { it.amount }.average())}"
        }
        return "Среднее настроение за неделю — ${fmt(Math.round(avg * 10) / 10.0)} из 5. По дням: $days."
    }

    companion object {
        const val MOOD = "mood"
        const val WATER = "water"
        const val PILL = "pill"
        const val HABIT = "habit"
        const val GOAL = "goal"
        const val EVENT = "event"
    }
}
