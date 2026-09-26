package ai.loli.core.nlp

/**
 * Регулярные выражения для кириллицы, одинаково работающие на JVM и Android.
 * В JDK 19+ `\b` и `\w` по умолчанию ASCII-only, а на Android (ICU) — Unicode.
 * Поэтому `\b` и `\w` заменяются явными Unicode-классами.
 */
object Rx {
    private const val WORD = "[\\p{L}\\p{N}_]"
    private const val BOUNDARY = "(?:(?<!$WORD)(?=$WORD)|(?<=$WORD)(?!$WORD))"

    fun unicode(pattern: String): String = pattern.replace("\\b", BOUNDARY).replace("\\w", WORD)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** Скомпилированные выражения кэшируются: разбор фразы вызывает их десятки раз. */
    fun of(pattern: String): Regex = cache.getOrPut(pattern) { Regex(unicode(pattern)) }
}
