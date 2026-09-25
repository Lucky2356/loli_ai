package ai.loli.core.nlp

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Простые вычисления голосом: «сколько будет 250 умножить на 4», «15 процентов от 2000», «(1200+800)/2».
 */
object Calculator {
    private val wordOps = listOf(
        Regex("""\bумножить на\b|\bумноженное на\b|\bпомножить на\b""") to "*",
        Regex("""\bразделить на\b|\bделить на\b|\bподелить на\b|\bразделенное на\b""") to "/",
        Regex("""\bплюс\b|\bприбавить\b""") to "+",
        Regex("""\bминус\b|\bотнять\b|\bвычесть\b""") to "-",
    ).map { (r, op) -> Regex(Rx.unicode(r.pattern)) to op }

    /** Вычисляет выражение из фразы; null, если это не арифметика. */
    fun evaluate(text: String): Double? {
        var t = RuTokenizer.normalize(text)
            .replace(Regex("""^(сколько будет|посчитай|вычисли|подсчитай|реши|сколько)\s*"""), "")
            .replace("×", "*").replace("÷", "/").trim().trimEnd('?', '.', '=')
        val explicitCalc = Regex("""^(сколько будет|посчитай|вычисли|подсчитай|реши)""").containsMatchIn(RuTokenizer.normalize(text).trim())
        // «x»/«х» и «на» считаем умножением только между числами: «3 х 4», «250 на 4» (последнее — только после «сколько будет»)
        t = t.replace(Regex("""(?<=\d)\s*[хx]\s*(?=\d)"""), " * ")
        if (explicitCalc) t = t.replace(Regex("""(?<=\d)\s+на\s+(?=\d)"""), " * ")
        // Проценты: «15 процентов от 2000» / «15% от 2000»
        Regex(Rx.unicode("""^(\d+(?:[.,]\d+)?)\s*(?:%|процент\w*)\s+от\s+(\d+(?:[.,]\d+)?)$""")).find(t)?.let { m ->
            val p = m.groupValues[1].replace(',', '.').toDouble()
            val v = m.groupValues[2].replace(',', '.').toDouble()
            return p * v / 100
        }
        // Числительные словами → цифры
        val tokens = RuTokenizer.tokenize(t)
        if (tokens.any { it.isNumber }) {
            val sb = StringBuilder()
            var cursor = 0
            for (tok in tokens) {
                sb.append(t, cursor, tok.start)
                sb.append(if (tok.isNumber) formatNum(tok.number!!) else tok.text)
                cursor = tok.end
            }
            sb.append(t.substring(cursor))
            t = sb.toString()
        }
        for ((r, op) in wordOps) t = r.replace(t, " $op ")
        t = t.replace(" ", "").replace(',', '.')
        if (!Regex("""^[\d.+\-*/()]+$""").matches(t)) return null
        if (!Regex("""[+\-*/]""").containsMatchIn(t.drop(1))) return null
        return runCatching { Parser(t).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    fun format(v: Double): String {
        val bd = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = bd.toPlainString().replace('.', ',')
        return s
    }

    private fun formatNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double { val v = expr(); require(i == s.length); return v }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
            return v
        }
        private fun factor(): Double {
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '(') { i++; val v = expr(); require(s[i++] == ')'); return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
