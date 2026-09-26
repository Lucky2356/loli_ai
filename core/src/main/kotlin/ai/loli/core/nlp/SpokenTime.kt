package ai.loli.core.nlp

/**
 * Время, которое распознаватель речи пишет словами, → цифры, понятные разбору дат:
 * «семнадцать ноль ноль» → «17:00», «пятнадцать ноль пять» → «15:05», «пол шестого» → «17:30»,
 * «без пятнадцати восемь» → «7:45», «четверть девятого» → «8:15», «двадцать минут третьего» → «14:20».
 *
 * Разговорные «пол шестого», «без четверти восемь» без «утра/вечера» — это обычно день/вечер,
 * поэтому часы с 1 до 6 переводятся во вторую половину дня.
 */
object SpokenTime {
    private val HOURS: Map<String, Int> = buildMap {
        listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
            "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать",
            "восемнадцать", "девятнадцать", "двадцать").forEachIndexed { i, w -> put(w, i) }
        put("час", 1); put("двадцать один", 21); put("двадцать два", 22); put("двадцать три", 23)
    }
    private val DIGITS = mapOf("ноль" to 0, "один" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9)
    /** «шестого» → 6: в «пол шестого» и «четверть девятого» называется следующий час. */
    private val ORDINAL_GEN = mapOf("первого" to 1, "второго" to 2, "третьего" to 3, "четвертого" to 4, "четвёртого" to 4,
        "пятого" to 5, "шестого" to 6, "седьмого" to 7, "восьмого" to 8, "девятого" to 9, "десятого" to 10,
        "одиннадцатого" to 11, "двенадцатого" to 12)
    private val MINUTES_GEN = mapOf("пяти" to 5, "десяти" to 10, "пятнадцати" to 15, "четверти" to 15, "двадцати" to 20,
        "двадцати пяти" to 25, "тридцати" to 30)
    private val MINUTES_NOM = mapOf("пять" to 5, "десять" to 10, "пятнадцать" to 15, "двадцать" to 20, "двадцать пять" to 25)
    private val PART_OF_DAY = setOf("утра", "дня", "вечера", "ночи")

    private const val L = """(?<![\p{L}\d])"""
    private const val R = """(?![\p{L}\d])"""
    private val hourAlt = HOURS.keys.sortedByDescending { it.length }.joinToString("|")
    private val digitAlt = DIGITS.keys.joinToString("|")
    private val ordAlt = ORDINAL_GEN.keys.joinToString("|")
    private val minGenAlt = MINUTES_GEN.keys.sortedByDescending { it.length }.joinToString("|")
    private val minNomAlt = MINUTES_NOM.keys.sortedByDescending { it.length }.joinToString("|")

    private val ZERO = Regex("""(?iu)$L(\d{1,2}|$hourAlt)\s+ноль\s+(\d|$digitAlt)$R(\s+(?:утра|дня|вечера|ночи))?""")
    private val HALF = Regex("""(?iu)${L}пол\s*-?\s*($ordAlt)$R(\s+(?:утра|дня|вечера|ночи))?""")
    private val QUARTER = Regex("""(?iu)${L}четверть\s+($ordAlt)$R(\s+(?:утра|дня|вечера|ночи))?""")
    private val MINUTES_PAST = Regex("""(?iu)$L(\d{1,2}|$minNomAlt)\s+минут\p{L}*\s+($ordAlt)$R(\s+(?:утра|дня|вечера|ночи))?""")
    private val WITHOUT = Regex("""(?iu)${L}без\s+(\d{1,2}|$minGenAlt)(?:\s+минут)?\s+(\d{1,2}|$hourAlt)$R(\s+(?:утра|дня|вечера|ночи))?""")

    fun normalize(text: String): String {
        var t = text
        t = ZERO.replace(t) { m ->
            val h = hour(m.groupValues[1]) ?: return@replace m.value
            val d = m.groupValues[2].toIntOrNull() ?: DIGITS[m.groupValues[2].lowercase()] ?: return@replace m.value
            format(h, d, m.groupValues[3], spoken = false)
        }
        t = HALF.replace(t) { m -> ORDINAL_GEN[m.groupValues[1].lowercase()]?.let { format(prev(it), 30, m.groupValues[2]) } ?: m.value }
        t = QUARTER.replace(t) { m -> ORDINAL_GEN[m.groupValues[1].lowercase()]?.let { format(prev(it), 15, m.groupValues[2]) } ?: m.value }
        t = MINUTES_PAST.replace(t) { m ->
            val min = m.groupValues[1].toIntOrNull() ?: MINUTES_NOM[m.groupValues[1].lowercase()] ?: return@replace m.value
            val h = ORDINAL_GEN[m.groupValues[2].lowercase()] ?: return@replace m.value
            if (min !in 1..59) m.value else format(prev(h), min, m.groupValues[3])
        }
        t = WITHOUT.replace(t) { m ->
            val min = m.groupValues[1].toIntOrNull() ?: MINUTES_GEN[m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")] ?: return@replace m.value
            val h = hour(m.groupValues[2]) ?: return@replace m.value
            if (min !in 1..30 || h !in 1..24) m.value else format(prev(h), 60 - min, m.groupValues[3])
        }
        return t
    }

    private fun hour(w: String): Int? = w.toIntOrNull()?.takeIf { it in 0..24 } ?: HOURS[w.lowercase().replace(Regex("\\s+"), " ")]

    /** Предыдущий час: «пол первого» — 12:30, «без пятнадцати час» — 12:45. */
    private fun prev(h: Int): Int = if (h == 1) 12 else h - 1

    /** [partWord] — « вечера» и т.п. (сохраняется, его учтёт разбор дат); без него часы 1–6 — вторая половина дня. */
    private fun format(h: Int, m: Int, partWord: String, spoken: Boolean = true): String {
        val part = partWord.trim().lowercase()
        val hour = if (spoken && part.isEmpty() && h in 1..6) h + 12 else h
        return "%d:%02d".format(hour % 24, m) + (if (part in PART_OF_DAY) " $part" else "")
    }
}
