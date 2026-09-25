package ai.loli.core.nlp

/** Нормализация и сравнение текстов для поиска и сопоставления записей. */
object TextAnalysis {
    private val stopWords = setOf(
        "и", "в", "во", "на", "с", "со", "к", "ко", "по", "о", "об", "от", "до", "за", "из", "у", "для", "про", "при", "над", "под",
        "а", "но", "или", "что", "чтобы", "как", "это", "этот", "эта", "эти", "то", "там", "тут", "же", "ли", "бы", "не", "ни",
        "я", "мне", "меня", "мой", "моя", "мое", "мои", "моих", "моей", "моему", "моим", "ты", "вы", "мы", "он", "она", "они", "его", "ее", "их",
        "все", "всё", "весь", "всю", "всех", "еще", "ещё", "уже", "так", "да", "нет", "очень", "можно", "нужно", "надо",
        "лоли", "пожалуйста", "найди", "покажи", "добавь", "запиши", "запомни", "создай", "сделай", "был", "была", "было", "были",
        "который", "которая", "которые", "какие", "какой", "какая", "где", "когда", "там", "туда", "сюда",
    )

    fun normalize(text: String): String = RuTokenizer.normalize(text)

    fun words(text: String): List<String> =
        Regex("[\\p{L}\\d]+").findAll(normalize(text)).map { it.value }.toList()

    /** Основы значимых слов (без стоп-слов). */
    fun stems(text: String): List<String> = words(text).filter { it !in stopWords && it.length > 1 }.map { w ->
        // Для коротких слов Snowball срезает слишком много («идеи» → «ид»), оставляем минимум 3 буквы.
        val s = RuStemmer.stem(w)
        if (s.length < 3 && w.length >= 3) w.take(3) else s
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** Похожесть двух основ 0..1 с учётом общего префикса (устойчиво к разным формам слова). */
    fun stemSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val min = minOf(a.length, b.length)
        if (min >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.85
        val common = a.commonPrefixWith(b).length
        if (common >= 5) return 0.75
        if (min >= 5) {
            val d = levenshtein(a, b)
            if (d == 1) return 0.7
        }
        return 0.0
    }
}
