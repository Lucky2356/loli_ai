package ai.loli.core.nlp

import ai.loli.core.assistant.RuFormat

/**
 * Кухонные меры без интернета: «стакан муки в граммах», «сколько грамм в столовой ложке сахара».
 * Значения приблизительные (стакан — 250 мл), поэтому в ответе всегда «примерно».
 */
object Kitchen {
    private enum class Measure(val stem: String, val title: String, val genitive: String) {
        GLASS("стакан", "стакан", "стакана"),
        TBSP("столов", "столовая ложка", "столовой ложки"),
        TSP("чайн", "чайная ложка", "чайной ложки"),
    }

    /** Граммов в стакане 250 мл / столовой / чайной ложке. */
    private class Food(val stem: String, val title: String, val glass: Int, val tbsp: Int, val tsp: Int)

    private val foods = listOf(
        Food("мук", "муки", 160, 25, 8), Food("сахар", "сахара", 200, 25, 8), Food("сол", "соли", 325, 30, 10),
        Food("молок", "молока", 250, 18, 5), Food("вод", "воды", 250, 18, 5), Food("рис", "риса", 200, 20, 7),
        Food("греч", "гречки", 210, 25, 8), Food("манк", "манки", 200, 25, 8), Food("масл", "масла", 230, 17, 5),
        Food("мед", "мёда", 350, 30, 10), Food("мёд", "мёда", 350, 30, 10), Food("сметан", "сметаны", 250, 25, 10),
        Food("крахмал", "крахмала", 160, 30, 10), Food("овсян", "овсянки", 90, 12, 4),
    )

    private val RX = Rx.of("""(?:сколько\s+(?:грамм\w*\s+)?(?:будет\s+|это\s+|в\s+)?|переведи\s+|скажи\s+)?(?:(\d+(?:[.,]\d+)?)\s+)?(стакан\w*|столов\w+\s+ложк\w*|чайн\w+\s+ложк\w*|ложк\w*)\s+([а-яё]+)(?:\s+(?:в|во)\s+грамм\w*)?\s*[?.!]*$""")

    /** Ответ или null, если это не вопрос о кухонных мерах. */
    fun answer(text: String): String? {
        val t = RuTokenizer.normalize(text).trim()
        if (!Rx.of("""грамм|граммах|сколько\s+(?:грамм|весит)|в\s+граммах""").containsMatchIn(t)) return null
        val m = RX.find(t) ?: return null
        val count = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        val mWord = m.groupValues[2]
        val measure = when {
            mWord.startsWith("стакан") -> Measure.GLASS
            mWord.startsWith("столов") -> Measure.TBSP
            mWord.startsWith("чайн") -> Measure.TSP
            else -> Measure.TBSP // «ложка муки» — по умолчанию столовая
        }
        val word = m.groupValues[3]
        val food = foods.firstOrNull { word.startsWith(it.stem) } ?: return null
        val per = when (measure) { Measure.GLASS -> food.glass; Measure.TBSP -> food.tbsp; Measure.TSP -> food.tsp }
        val grams = Math.round(per * count).toInt()
        val subject = when {
            count == 1.0 -> "${measure.title.replaceFirstChar { it.uppercase() }} ${food.title}"
            count == Math.floor(count) -> "${count.toInt()} ${plural(measure, count.toInt())} ${food.title}"
            else -> "${Calculator.format(count)} ${measure.genitive} ${food.title}"
        }
        return "$subject — примерно $grams ${RuFormat.plural(grams, "грамм", "грамма", "граммов")}."
    }

    private fun plural(m: Measure, n: Int): String = when (m) {
        Measure.GLASS -> RuFormat.plural(n, "стакан", "стакана", "стаканов")
        Measure.TBSP -> RuFormat.plural(n, "столовая ложка", "столовые ложки", "столовых ложек")
        Measure.TSP -> RuFormat.plural(n, "чайная ложка", "чайные ложки", "чайных ложек")
    }
}
