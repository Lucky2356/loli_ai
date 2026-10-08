package ai.loli.core.health

import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.RuFormat
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import ai.loli.core.util.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Давление, пульс, вес, сон и показания счётчиков. Хранятся в журнале привычек (на телефоне, входят в резервную копию).
 * Давление: amount — верхнее, name — «130/85» или «130/85, пульс 70».
 */
class Vitals(private val store: HabitStore, private val time: TimeSource) {
    /** [access]: CREATE — запись, PRIVATE — просмотр. [monthlyMeterReminder] — поставить ежемесячное напоминание (день месяца). */
    data class Reply(val text: String, val changed: Boolean = false, val write: Boolean = false, val monthlyMeterReminder: Int? = null)

    companion object {
        const val BP = "bp"
        const val PULSE = "pulse"
        const val WEIGHT = "weight"
        const val SLEEP = "sleep"
        const val METER = "meter"

        private fun rx(p: String) = Rx.of(p)

        private val METERS = linkedMapOf(
            "холодн\\p{L}*\\s+вод\\p{L}*|хвс" to "Холодная вода",
            "горяч\\p{L}*\\s+вод\\p{L}*|гвс" to "Горячая вода",
            "электричеств\\p{L}*|электроэнерги\\p{L}*|свет\\p{L}*|электросчетчик\\p{L}*" to "Электричество",
            "газ\\p{L}*" to "Газ",
            "отоплени\\p{L}*|тепл\\p{L}*" to "Отопление",
            "вод\\p{L}*" to "Вода",
        )
        private val METER_ALT = METERS.keys.joinToString("|") { "(?:$it)" }
        private val METER_UNIT = mapOf("Холодная вода" to "м³", "Горячая вода" to "м³", "Вода" to "м³", "Электричество" to "кВт·ч", "Газ" to "м³", "Отопление" to "Гкал")
    }

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    private fun fmt(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(Locale("ru"), "%.1f", x)

    private fun meterName(s: String): String? = METERS.entries.firstOrNull { (k, _) -> rx("^(?:$k)$").matches(s.trim()) }?.value

    /** null — фраза не про здоровье и счётчики. */
    suspend fun handle(text: String): Reply? {
        val raw = text.trim().trimEnd('.', '!')
        val n = RuTokenizer.normalize(raw).trimEnd('?').trim()
        val d = DevicePhrases.digitize(n).replace(Regex("""(\d)\s*[,.]\s*(\d)"""), "$1.$2")
        val question = raw.endsWith("?") || rx("""^(?:какое|какой|какие|сколько|как|мой|моё|мое|мои|покажи|динамика|статистика)\b""").containsMatchIn(n)

        // --- Показания счётчиков
        if (rx("""показани|счетчик|счётчик""").containsMatchIn(n) || rx("""\b(?:холодн\p{L}*|горяч\p{L}*)\s+вод""").containsMatchIn(n)) {
            if (rx("""^(?:напоминай|напоминать|напомни)\b.*(?:сдавать|сдать|передавать|передать|подавать|подать)\s+показания""").containsMatchIn(n)) {
                val day = rx("""(\d{1,2})\s*(?:числа|число|го)""").find(d)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 28) ?: 20
                return Reply("", write = true, monthlyMeterReminder = day)
            }
            if (rx("""^(?:мои\s+|последние\s+|покажи\s+)?(?:мои\s+)?показания(?:\s+счетчиков)?$|^расход\s+по\s+счетчикам|^сколько\s+(?:я\s+|мы\s+)?(?:израсходовал\p{L}*|потратил\p{L}*|нажгл\p{L}*|накрутил\p{L}*)""").containsMatchIn(d)) {
                return Reply(meterReport(), write = false)
            }
            val pairs = rx("""($METER_ALT)\s*[:\-—]?\s*(\d+(?:\.\d+)?)""").findAll(d).mapNotNull { m ->
                val name = meterName(m.groupValues[1]) ?: return@mapNotNull null
                val v = num(m.groupValues[2]) ?: return@mapNotNull null
                name to v
            }.toList()
            if (pairs.isNotEmpty()) {
                val first = store.since(Instant.EPOCH, METER).isEmpty()
                val now = time.now()
                val lines = pairs.distinctBy { it.first }.map { (name, v) ->
                    val prev = store.since(Instant.EPOCH, METER).lastOrNull { it.name == name }
                    store.log(METER, name, v, now)
                    val diff = prev?.let { v - it.amount }?.takeIf { it >= 0 }
                    "$name ${fmt(v)}" + (diff?.let { " (+${fmt(it)} ${METER_UNIT[name]} с ${day(prev.at)})" } ?: "")
                }
                val hint = if (first) " Напоминать сдавать показания каждый месяц? Скажите «напоминай сдавать показания 20 числа»." else ""
                return Reply("Записала показания: ${lines.joinToString(", ")}.$hint", changed = true, write = true)
            }
        }

        // --- Давление
        rx("""(?:давлени\p{L}*|^ад)\s+(?:сейчас\s+|сегодня\s+|утром\s+|вечером\s+)?(\d{2,3})\s*(?:на|/|\\|и)\s*(\d{2,3})(?:[,\s]+(?:пульс|чсс)\s+(\d{2,3}))?""").find(d)?.let { m ->
            val sys = m.groupValues[1].toInt(); val dia = m.groupValues[2].toInt()
            if (sys !in 60..260 || dia !in 30..160 || dia >= sys) return null
            val pulse = m.groupValues[3].toIntOrNull()?.takeIf { it in 30..220 }
            val now = time.now()
            store.log(BP, "$sys/$dia" + (pulse?.let { ", пульс $it" } ?: ""), sys.toDouble(), now)
            pulse?.let { store.log(PULSE, "", it.toDouble(), now) }
            val note = when {
                sys >= 180 || dia >= 110 -> " Это очень высокое давление. Если плохо — вызовите скорую (103)."
                sys >= 140 || dia >= 90 -> " Выше нормы. Если повторяется — стоит показаться врачу."
                sys < 90 || dia < 60 -> " Пониженное. Если кружится голова — присядьте и выпейте воды."
                else -> ""
            }
            return Reply("Записала давление $sys на $dia" + (pulse?.let { ", пульс $it" } ?: "") + ".$note", changed = true, write = true)
        }
        rx("""^(?:мой\s+)?(?:пульс|чсс)\s+(\d{2,3})(?:\s+ударов)?$""").find(d)?.let { m ->
            val p = m.groupValues[1].toInt().takeIf { it in 30..220 } ?: return null
            store.log(PULSE, "", p.toDouble(), time.now())
            return Reply("Записала пульс $p.", changed = true, write = true)
        }

        // --- Вес
        (rx("""^(?:мой\s+)?вес\s+(?:сегодня\s+|сейчас\s+)?(\d{2,3}(?:\.\d)?)\s*(?:кг|килограмм\p{L}*|кило)?$""").find(d)
            ?: rx("""^(?:я\s+)?(?:сегодня\s+|сейчас\s+)?(?:вешу|взвесил\p{L}*)\s+(?:сегодня\s+)?(\d{2,3}(?:\.\d)?)\s*(?:кг|килограмм\p{L}*|кило)?$""").find(d))?.let { m ->
            val w = num(m.groupValues[1])?.takeIf { it in 20.0..300.0 } ?: return null
            val prev = store.since(Instant.EPOCH, WEIGHT).lastOrNull()
            store.log(WEIGHT, "", w, time.now())
            val diff = prev?.let { w - it.amount }
            val tail = when {
                diff == null -> ""
                Math.abs(diff) < 0.05 -> " Как и в прошлый раз."
                else -> " ${if (diff > 0) "+" else "−"}${fmt(Math.abs(Math.round(diff * 10) / 10.0))} кг с ${day(prev.at)}."
            }
            return Reply("Записала вес ${fmt(w)} кг.$tail", changed = true, write = true)
        }

        // --- Сон
        rx("""^(?:я\s+)?(?:сегодня\s+|ночью\s+|вчера\s+)?(?:спал|спала|спали|поспал|поспала|проспал|проспала)\s+(?:сегодня\s+|ночью\s+)?(?:всего\s+|только\s+|около\s+)?(\d{1,2}(?:\.\d)?)(\s+с\s+половиной)?\s+час\p{L}*$""").find(d)?.let { m ->
            val h = (num(m.groupValues[1]) ?: return null) + if (m.groupValues[2].isNotBlank()) 0.5 else 0.0
            if (h <= 0 || h > 20) return null
            store.log(SLEEP, "", h, time.now())
            val note = when {
                h < 6 -> " Маловато — постарайтесь сегодня лечь пораньше."
                h >= 9.5 -> " Хорошо выспались!"
                else -> ""
            }
            return Reply("Записала сон: ${hours(h)}.$note", changed = true, write = true)
        }

        // --- Вопросы
        if (!question && !rx("""за\s+(?:неделю|месяц)|динамик""").containsMatchIn(n)) return null
        val month = rx("""месяц""").containsMatchIn(n)
        val days = if (month) 30L else 7L
        return when {
            rx("""давлени""").containsMatchIn(n) -> Reply(bpReport(days))
            rx("""\bвес\b|вешу|похудел|поправил|набрал\p{L}*\s+вес""").containsMatchIn(n) -> Reply(weightReport(if (rx("""недел""").containsMatchIn(n)) 7 else 30))
            rx("""\bспал|\bсон\b|\bсна\b|высыпаю|высыпал""").containsMatchIn(n) -> Reply(sleepReport(days))
            rx("""пульс""").containsMatchIn(n) -> Reply(pulseReport(days))
            else -> null
        }
    }

    private fun day(at: Instant): String {
        val d = at.atZone(time.zone()).toLocalDate()
        val today = time.today()
        return when (d) {
            today -> "сегодня"
            today.minusDays(1) -> "вчера"
            else -> "${d.dayOfMonth} ${genitive(d)}"
        }
    }

    private fun genitive(d: LocalDate) = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")[d.monthValue - 1]

    private fun hours(h: Double): String {
        val whole = h.toInt()
        val half = h - whole >= 0.25
        // «Два с половиной часа», «семь с половиной часов» — падеж по целому числу.
        return if (half) "$whole с половиной ${RuFormat.plural(whole, "час", "часа", "часов")}" else RuFormat.count(whole, "час", "часа", "часов")
    }

    private fun since(days: Long): Instant = time.today().minusDays(days - 1).atStartOfDay(time.zone()).toInstant()

    private suspend fun bpReport(days: Long): String {
        val list = store.since(since(days), BP)
        if (list.isEmpty()) return "Давление за ${if (days > 7) "месяц" else "неделю"} не записано. Скажите, например: «давление 120 на 80, пульс 70»."
        val parsed = list.mapNotNull { e -> Regex("""^(\d+)/(\d+)""").find(e.name)?.let { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), e.at) } }
        val last = parsed.last()
        val avgS = parsed.map { it.first }.average().toInt(); val avgD = parsed.map { it.second }.average().toInt()
        val high = parsed.count { it.first >= 140 || it.second >= 90 }
        return "Последнее давление — ${last.first} на ${last.second} (${day(last.third)}). " +
            (if (parsed.size > 1) "За ${if (days > 7) "месяц" else "неделю"} в среднем $avgS на $avgD, замеров: ${parsed.size}, от ${parsed.minOf { it.first }} до ${parsed.maxOf { it.first }} верхнее." else "") +
            (if (high > 0) " Повышенное — ${RuFormat.count(high, "раз", "раза", "раз")}." else "")
    }

    private suspend fun weightReport(days: Int): String {
        val all = store.since(Instant.EPOCH, WEIGHT)
        if (all.isEmpty()) return "Вес ещё не записан. Скажите, например: «вес 68,5»."
        val last = all.last()
        val from = since(days.toLong())
        val base = all.firstOrNull { !it.at.isBefore(from) }?.takeIf { it !== last }
        val change = base?.let { last.amount - it.amount }
        return "Вес — ${fmt(last.amount)} кг (${day(last.at)})." +
            (change?.let { c -> if (Math.abs(c) < 0.05) " За ${if (days > 7) "месяц" else "неделю"} без изменений." else " За ${if (days > 7) "месяц" else "неделю"}: ${if (c > 0) "+" else "−"}${fmt(Math.abs(Math.round(c * 10) / 10.0))} кг." } ?: "")
    }

    private suspend fun sleepReport(days: Long): String {
        val list = store.since(since(days), SLEEP)
        if (list.isEmpty()) return "Сон не записан. Скажите утром, например: «спала 7 часов»."
        val avg = list.map { it.amount }.average()
        val short = list.count { it.amount < 6 }
        return "Сон за ${if (days > 7) "месяц" else "неделю"}: в среднем ${fmt(Math.round(avg * 10) / 10.0)} ч, последняя ночь — ${fmt(list.last().amount)} ч." +
            (if (short > 0) " Меньше 6 часов — ${RuFormat.count(short, "ночь", "ночи", "ночей")}." else "") +
            (if (avg < 7) " Стоит ложиться пораньше." else "")
    }

    private suspend fun pulseReport(days: Long): String {
        val list = store.since(since(days), PULSE)
        if (list.isEmpty()) return "Пульс не записан. Скажите, например: «пульс 72»."
        return "Пульс: последний ${list.last().amount.toInt()}, в среднем ${list.map { it.amount }.average().toInt()} за ${if (days > 7) "месяц" else "неделю"}."
    }

    private suspend fun meterReport(): String {
        val all = store.since(Instant.EPOCH, METER)
        if (all.isEmpty()) return "Показаний пока нет. Скажите, например: «показания: холодная вода 345, горячая 210, свет 12500»."
        val lines = all.groupBy { it.name }.map { (name, list) ->
            val last = list.last()
            val prev = list.dropLast(1).lastOrNull()
            val used = prev?.let { last.amount - it.amount }?.takeIf { it >= 0 }
            "• $name: ${fmt(last.amount)} (${day(last.at)})" + (used?.let { " — израсходовано ${fmt(Math.round(it * 100) / 100.0)} ${METER_UNIT[name]} с ${day(prev.at)}" } ?: "")
        }
        return "Показания счётчиков:\n" + lines.joinToString("\n")
    }

    /** Точки для графика на экране: (дата, значение) за [days] дней. */
    suspend fun series(kind: String, days: Long): List<Pair<Instant, Double>> = store.since(since(days), kind).map { it.at to it.amount }
}
