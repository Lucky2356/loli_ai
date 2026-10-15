package ai.loli.core.nlp

/**
 * Поиск имени ассистента (wake word) в распознанном тексте.
 * Офлайн-распознаватель может услышать «Лоли» как «лолли», «лоле», «ляли», «роли» —
 * поэтому сравнение фонетическое и нечёткое.
 */
class WakeWordMatcher(
    wakeWord: String,
    private val threshold: Double = 0.75,
    /**
     * Как офлайн-распознаватель пишет это имя на самом деле («ноль», «ну ли» вместо «Лоли»). Считаются обращением
     * только в начале фразы и только если после них есть команда — чтобы «ноль» в обычной речи не будил ассистента.
     */
    private val aliases: Set<String> = emptySet(),
) {
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
        aliasMatch(text, tokens)?.let { return it }
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

    private fun aliasMatch(text: String, tokens: List<MatchResult>): Match? {
        if (aliases.isEmpty()) return null
        for (n in 2 downTo 1) {
            if (tokens.size <= n) continue
            val head = tokens.subList(0, n).joinToString(" ") { RuTokenizer.normalize(it.value) }
            if (head in aliases) {
                val command = text.substring(tokens[n - 1].range.last + 1).trim().trimStart(',', '.', '!', ':', '-', '—', ' ')
                if (command.isNotBlank()) return Match(command, threshold)
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
        /** Как распознаватель слышит «Лоли»: для имени, похожего на «Лоли», — эти варианты; для других имён — ничего. */
        fun misheardAliases(name: String): Set<String> =
            if (WakeWordMatcher(name).similarity("лоли") >= 0.85) LOLI_MISHEARS else emptySet()

        private val LOLI_MISHEARS = setOf(
            "ноль", "ноли", "ноле", "нолик", "лоль", "лол", "лоле", "ляли", "ляля", "лола", "лоля", "олли", "лолли", "нолли",
            "ну ли", "но ли", "ноль ноль", "ло ли", "а ли",
        )

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
