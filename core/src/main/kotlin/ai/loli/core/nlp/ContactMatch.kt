package ai.loli.core.nlp

/**
 * Какой контакт имел в виду человек: «позвони Алексею» — не Александру, «маме» — не Мамедову.
 * Поиск по контактам телефона отдаёт всех, у кого имя начинается похоже; выбирает лучшего эта оценка.
 */
object ContactMatch {
    /** Чем больше, тем лучше; 0 — контакт не подходит. */
    fun score(query: String, displayName: String): Int {
        val q = norm(query)
        val name = norm(displayName)
        if (q.isEmpty() || name.isEmpty()) return 0
        if (q == name) return 100
        val qs = TextAnalysis.stems(query).ifEmpty { q.split(' ').filter { it.isNotEmpty() } }
        val ns = TextAnalysis.stems(displayName).ifEmpty { name.split(' ').filter { it.isNotEmpty() } }
        if (qs.isEmpty() || ns.isEmpty()) return 0
        // Все слова запроса нашлись среди слов имени: «Ивану Петрову» → «Иван Петров».
        if (qs.all { s -> ns.any { it == s } }) return 90 - (ns.size - qs.size).coerceAtMost(5)
        if (qs.all { s -> ns.any { it.startsWith(s) && s.length >= 3 } }) return 60
        if (qs.all { s -> ns.any { s.startsWith(it) && it.length >= 3 } }) return 50
        return 0
    }

    /** Индекс лучшего имени или null, если ни одно не подошло. При равных оценках — первое. */
    fun best(query: String, names: List<String>): Int? =
        names.indices.map { it to score(query, names[it]) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').trim()
}
