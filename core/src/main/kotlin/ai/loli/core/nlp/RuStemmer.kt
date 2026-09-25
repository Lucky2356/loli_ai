package ai.loli.core.nlp

/**
 * Стеммер для русского языка по алгоритму Snowball (Porter, 2002).
 * Нужен для поиска: «холодильнике» и «холодильник» дают одну основу «холодильник».
 */
object RuStemmer {
    private const val VOWELS = "аеиоуыэюя"

    private val perfectiveGerund1 = listOf("вшись", "вши", "в")
    private val perfectiveGerund2 = listOf("ившись", "ывшись", "ивши", "ывши", "ив", "ыв")
    private val adjective = listOf(
        "ими", "ыми", "его", "ого", "ему", "ому", "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом",
        "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею",
    ).sortedByDescending { it.length }
    private val participle1 = listOf("ем", "нн", "вш", "ющ", "щ")
    private val participle2 = listOf("ивш", "ывш", "ующ")
    private val reflexive = listOf("ся", "сь")
    private val verb1 = listOf("ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют", "ны", "ть", "ешь", "нно")
        .sortedByDescending { it.length }
    private val verb2 = listOf(
        "ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил", "ыл", "им", "ым", "ен", "ило", "ыло",
        "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю",
    ).sortedByDescending { it.length }
    private val noun = listOf(
        "иями", "ями", "ами", "иях", "ием", "ией", "иям", "ев", "ов", "ие", "ье", "еи", "ии", "ей", "ой", "ий", "ям", "ем",
        "ам", "ом", "ах", "ях", "ию", "ью", "ия", "ья", "а", "е", "и", "й", "о", "у", "ы", "ь", "ю", "я",
    ).sortedByDescending { it.length }
    private val superlative = listOf("ейше", "ейш")
    private val derivational = listOf("ость", "ост")

    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 4096
    }

    fun stem(input: String): String {
        val word = RuTokenizer.normalize(input)
        if (word.length < 3 || word.any { it !in 'а'..'я' }) return word
        synchronized(cache) { cache[word]?.let { return it } }
        val result = doStem(word)
        synchronized(cache) { cache[word] = result }
        return result
    }

    private fun doStem(word: String): String {
        val rv = word.indexOfFirst { it in VOWELS }.let { if (it < 0) return word else it + 1 }
        val r1 = regionAfterVowelConsonant(word, 0)
        val r2 = regionAfterVowelConsonant(word, r1)
        var w = word

        // Шаг 1
        val gerund = findEnding(w, rv, perfectiveGerund2) ?: findEndingAfterAYa(w, rv, perfectiveGerund1)
        if (gerund != null) {
            w = w.dropLast(gerund)
        } else {
            findEnding(w, rv, reflexive)?.let { w = w.dropLast(it) }
            val adj = findEnding(w, rv, adjective)
            if (adj != null) {
                w = w.dropLast(adj)
                val part = findEnding(w, rv, participle2) ?: findEndingAfterAYa(w, rv, participle1)
                if (part != null) w = w.dropLast(part)
            } else {
                val v = findEnding(w, rv, verb2) ?: findEndingAfterAYa(w, rv, verb1)
                if (v != null) {
                    w = w.dropLast(v)
                } else {
                    findEnding(w, rv, noun)?.let { w = w.dropLast(it) }
                }
            }
        }
        // Шаг 2
        if (w.endsWith("и") && w.length - 1 >= rv) w = w.dropLast(1)
        // Шаг 3
        findEnding(w, r2, derivational)?.let { w = w.dropLast(it) }
        // Шаг 4
        when {
            w.endsWith("нн") && w.length - 2 >= rv -> w = w.dropLast(1)
            else -> {
                val sup = findEnding(w, rv, superlative)
                if (sup != null) {
                    w = w.dropLast(sup)
                    if (w.endsWith("нн") && w.length - 2 >= rv) w = w.dropLast(1)
                } else if (w.endsWith("ь") && w.length - 1 >= rv) {
                    w = w.dropLast(1)
                }
            }
        }
        return w
    }

    private fun regionAfterVowelConsonant(word: String, from: Int): Int {
        var i = from
        while (i < word.length - 1) {
            if (word[i] in VOWELS && word[i + 1] !in VOWELS) return i + 2
            i++
        }
        return word.length
    }

    private fun findEnding(word: String, region: Int, endings: List<String>): Int? =
        endings.firstOrNull { word.endsWith(it) && word.length - it.length >= region }?.length

    private fun findEndingAfterAYa(word: String, region: Int, endings: List<String>): Int? =
        endings.sortedByDescending { it.length }.firstOrNull {
            val pos = word.length - it.length
            word.endsWith(it) && pos - 1 >= region && (word[pos - 1] == 'а' || word[pos - 1] == 'я')
        }?.length
}
