package ai.loli.core.nlp

/**
 * Токен исходного текста. [norm] — нижний регистр, «ё» → «е».
 * [number] заполняется для числовых токенов (цифры или числительные, объединённые в один токен).
 * [start]/[end] — позиции в исходной строке (end — исключительно).
 */
data class Tok(
    val text: String,
    val norm: String,
    val start: Int,
    val end: Int,
    val number: Double? = null,
    val isTime: Boolean = false,
) {
    val isNumber: Boolean get() = number != null
    val intValue: Int? get() = number?.takeIf { it == Math.floor(it) && it < Int.MAX_VALUE }?.toInt()
}

object RuTokenizer {
    // Слово, число с разделителями (1 200 / 12,50 / 10:30 / 15.10.2026) или символ валюты.
    private val tokenRegex = Regex("""\d{1,3}(?:[  ]\d{3})+(?:[.,]\d+)?|\d+(?:[:.,]\d+)*|[\p{L}]+(?:-[\p{L}]+)*|[₽$€]""")

    fun normalize(s: String): String = s.lowercase().replace('ё', 'е')

    fun tokenize(text: String): List<Tok> {
        val raw = tokenRegex.findAll(text).map { m ->
            val t = m.value
            val n = normalize(t)
            when {
                n.matches(Regex("""\d{1,2}:\d{2}""")) -> Tok(t, n, m.range.first, m.range.last + 1, isTime = true)
                n.matches(Regex("""\d{1,2}\.\d{1,2}(?:\.\d{2,4})?""")) && !n.matches(Regex("""\d+\.\d{3,}""")) ->
                    // «15.10» — скорее дата, числом не считаем. Десятичные дроби пишутся через запятую.
                    Tok(t, n, m.range.first, m.range.last + 1)
                n.first().isDigit() -> Tok(t, n, m.range.first, m.range.last + 1, number = parseDigits(n))
                else -> Tok(t, n, m.range.first, m.range.last + 1)
            }
        }.toList()
        return RuNumbers.mergeNumberWords(raw)
    }

    private fun parseDigits(s: String): Double? =
        s.replace(" ", "").replace(" ", "").replace(',', '.').toDoubleOrNull()
}
