package ai.loli.core.nlp

/**
 * Поиск имени ассистента (wake word) в распознанном тексте.
 * Офлайн-распознаватель может услышать «Лоли» как «лолли», «лоле», «ляли», «роли» —
 * поэтому сравнение фонетическое и нечёткое.
 */
class WakeWordMatcher(wakeWord: String, private val threshold: Double = 0.75) {
    private val target = phonetic(RuTokenizer.normalize(wakeWord.trim()))
    private val targetWords = RuTokenizer.normalize(wakeWord.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
    private val prefixes = setOf("эй", "окей", "ок", "о", "привет", "слушай", "hey", "ok")

    data class Match(val command: String, val confidence: Double)

    /**
     * Ищет имя в начале фразы (допускаются вводные «эй», «окей», «привет»).
     * Возвращает команду после имени (может быть пустой) или null, если имени нет.
     */
    fun match(text: String): Match? {
        val tokens = Regex("[\\p{L}\\d]+").findAll(text).toList()
        if (tokens.isEmpty()) return null
        val n = targetWords.size.coerceAtLeast(1)
        for (start in 0..minOf(2, tokens.size - 1)) {
            if (start > 0 && RuTokenizer.normalize(tokens[start - 1].value) !in prefixes) break
            if (start + n > tokens.size) break
            val candidate = tokens.subList(start, start + n).joinToString(" ") { RuTokenizer.normalize(it.value) }
            val score = similarity(candidate)
            if (score >= threshold) {
                val commandStart = tokens[start + n - 1].range.last + 1
                val command = text.substring(commandStart).trim().trimStart(',', '.', '!', ':', '-', '—', ' ')
                return Match(command, score)
            }
        }
        return null
    }

    /** Есть ли имя где-либо в тексте (для частичных результатов распознавания). */
    fun containsWakeWord(text: String): Boolean {
        val words = Regex("[\\p{L}]+").findAll(text).map { RuTokenizer.normalize(it.value) }.toList()
        val n = targetWords.size.coerceAtLeast(1)
        if (words.size < n) return false
        return (0..words.size - n).any { similarity(words.subList(it, it + n).joinToString(" ")) >= threshold }
    }

    fun similarity(candidate: String): Double {
        val p = phonetic(candidate)
        if (p == target) return 1.0
        val maxLen = maxOf(p.length, target.length)
        if (maxLen == 0) return 0.0
        val d = TextAnalysis.levenshtein(p, target)
        // Короткие имена требуют почти точного совпадения, чтобы не срабатывать на всё подряд.
        if (target.length <= 4 && d > 1) return 0.0
        return 1.0 - d.toDouble() / maxLen
    }

    companion object {
        /** Упрощённая фонетическая свёртка русского: аканье, иканье, оглушение, сдвоенные согласные. */
        fun phonetic(word: String): String {
            val s = StringBuilder()
            for (c in word) {
                val m = when (c) {
                    'о', 'а', 'я' -> 'а'
                    'е', 'и', 'э', 'ы', 'ё' -> 'и'
                    'ю', 'у' -> 'у'
                    'б' -> 'п'; 'в' -> 'ф'; 'г' -> 'к'; 'д' -> 'т'; 'ж' -> 'ш'; 'з' -> 'с'
                    'ь', 'ъ', '-', ' ' -> null
                    else -> c
                }
                if (m != null && (s.isEmpty() || s.last() != m)) s.append(m)
            }
            return s.toString()
        }
    }
}
