package ai.loli.core.nlp

/**
 * Разбор русских числительных: «восемьсот пятьдесят» → 850, «тысяча двести» → 1200,
 * «полторы тысячи» → 1500, «2 тысячи» → 2000, «пару» → 2.
 * Распознаватели речи обычно выдают цифры, но офлайн-модели (Vosk) — слова.
 */
object RuNumbers {
    private val units = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "одно" to 1, "одного" to 1, "одной" to 1,
        "два" to 2, "две" to 2, "двух" to 2, "три" to 3, "трех" to 3, "четыре" to 4, "четырех" to 4,
        "пять" to 5, "пяти" to 5, "шесть" to 6, "шести" to 6, "семь" to 7, "семи" to 7,
        "восемь" to 8, "восьми" to 8, "девять" to 9, "девяти" to 9,
    )
    private val teens = mapOf(
        "десять" to 10, "десяти" to 10, "одиннадцать" to 11, "одиннадцати" to 11, "двенадцать" to 12, "двенадцати" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15, "пятнадцати" to 15, "шестнадцать" to 16,
        "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
    )
    private val tens = mapOf(
        "двадцать" to 20, "двадцати" to 20, "тридцать" to 30, "тридцати" to 30, "сорок" to 40, "сорока" to 40,
        "пятьдесят" to 50, "пятидесяти" to 50, "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
    )
    private val hundreds = mapOf(
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900,
    )
    private val thousandWords = setOf(
        "тысяча", "тысячи", "тысяч", "тысячу", "тыс", "тысячей", "к",
        "тыща", "тыщи", "тыщ", "тыщу", "косарь", "косаря", "косарей", "косарю",
    )
    /** Разговорные суммы: «полтинник», «сотка», «пятихатка». */
    private val slang = mapOf(
        "полтинник" to 50, "полтос" to 50, "сотка" to 100, "сотку" to 100, "сотню" to 100, "сотни" to 100,
        "пятихатка" to 500, "пятихатку" to 500, "пятисотка" to 500, "пятисотку" to 500,
    )
    private val millionWords = setOf("миллион", "миллиона", "миллионов", "млн")
    private val halfWords = setOf("полторы", "полтора")
    private val pairWords = setOf("пару", "пара", "пары")

    fun isNumberWord(w: String): Boolean =
        w in units || w in teens || w in tens || w in hundreds || w in halfWords || w in pairWords || w in slang ||
            w in thousandWords.minus("к") || w in millionWords

    /** Объединяет подряд идущие числительные (и цифры с множителями «тысяч») в один числовой токен. */
    fun mergeNumberWords(tokens: List<Tok>): List<Tok> {
        val out = ArrayList<Tok>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val (value, consumed) = parseAt(tokens, i)
            if (consumed > 0 && value != null) {
                val first = tokens[i]
                val last = tokens[i + consumed - 1]
                out += Tok(
                    text = tokens.subList(i, i + consumed).joinToString(" ") { it.text },
                    norm = tokens.subList(i, i + consumed).joinToString(" ") { it.norm },
                    start = first.start, end = last.end, number = value,
                )
                i += consumed
            } else {
                out += tokens[i]
                i++
            }
        }
        return out
    }

    /** Пытается прочитать число начиная с позиции [start]. Возвращает (значение, число токенов). */
    private fun parseAt(tokens: List<Tok>, start: Int): Pair<Double?, Int> {
        var total = 0.0
        var group = 0.0
        var hasH = false; var hasT = false; var hasU = false; var hasDigits = false
        var anyInGroup = false
        var any = false
        var lastWasMultiplier = false
        var i = start
        loop@ while (i < tokens.size) {
            val t = tokens[i]
            val w = t.norm
            when {
                t.number != null -> {
                    if (anyInGroup) break@loop
                    group = t.number; hasDigits = true
                }
                w in hundreds -> { if (hasH || hasT || hasU || hasDigits) break@loop; group += hundreds.getValue(w); hasH = true }
                w in teens -> { if (hasT || hasU || hasDigits) break@loop; group += teens.getValue(w); hasT = true; hasU = true }
                w in tens -> { if (hasT || hasU || hasDigits) break@loop; group += tens.getValue(w); hasT = true }
                w in units -> { if (hasU || hasDigits) break@loop; group += units.getValue(w); hasU = true }
                w in halfWords -> { if (anyInGroup) break@loop; group = 1.5; hasDigits = true }
                w in pairWords -> { if (anyInGroup) break@loop; group = 2.0; hasDigits = true }
                w in slang -> { if (anyInGroup) break@loop; group = slang.getValue(w).toDouble(); hasDigits = true }
                w in thousandWords || w in millionWords -> {
                    if (lastWasMultiplier) break@loop
                    if (w == "к" && (!anyInGroup || i == 0 || t.start != tokens[i - 1].end)) break@loop // «2к» — только слитно
                    val mult = if (w in millionWords) 1_000_000.0 else 1000.0
                    total += (if (anyInGroup) group else 1.0) * mult
                    group = 0.0; hasH = false; hasT = false; hasU = false; hasDigits = false
                    anyInGroup = false; any = true; lastWasMultiplier = true
                    i++
                    continue@loop
                }
                else -> break@loop
            }
            anyInGroup = true; any = true; lastWasMultiplier = false
            i++
        }
        val consumed = i - start
        if (!any || consumed == 0) return null to 0
        return (total + group) to consumed
    }
}
