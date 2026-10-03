package ai.loli.core.skills

import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Note
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx

/** Рецепт из заметки: ингредиенты (если есть отдельный блок) и шаги. */
class Recipe(val title: String, val ingredients: String?, val steps: List<String>)

/** Что сказали во время готовки. */
sealed interface CookingCommand {
    data object Next : CookingCommand
    data object Repeat : CookingCommand
    data object Back : CookingCommand
    data object Ingredients : CookingCommand
    data object Timer : CookingCommand
    data object Stop : CookingCommand
    data class Go(val step: Int) : CookingCommand
}

/** Режим готовки: ведёт по шагам рецепта из заметки, руки заняты — отвечает коротко. */
object Cooking {
    private val START = Rx.of("""^(?:давай\s+|давайте\s+)?(?:приготовим|готовим|готовить|будем\s+готовить|начн[её]м\s+готовить|начни\s+готовить|помоги\s+приготовить|помоги\s+мне\s+приготовить|как\s+приготовить\s+по\s+рецепту)\s*(.*)$|^режим\s+готовки\s*(.*)$""")

    /** Название блюда из фразы «давай приготовим борщ»; "" — блюдо не названо; null — это не про готовку. */
    fun startDish(text: String): String? {
        val m = START.find(RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?')) ?: return null
        return (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
    }

    fun command(text: String): CookingCommand? {
        val t = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?', ',')
        return when {
            Rx.of("""^(?:хватит|закончили|закончим|стоп|отбой|выйди\s+из)\s*(?:готов\w*|режим\w*\s+готовки)?$|^(?:хватит|закончи|заверши)\s+готовить$|^(?:всё|все)\s+(?:готово|приготовили)$""").containsMatchIn(t) && (t.contains("готов") || t.startsWith("закончи") || t.startsWith("хватит") || t.startsWith("отбой")) -> CookingCommand.Stop
            Rx.of("""^(?:дальше|далее|следующий(?:\s+шаг)?|что\s+дальше|давай\s+дальше|готово,?\s+дальше|сделал[аи]?|сделано|ок(?:ей)?\s+дальше)$""").containsMatchIn(t) -> CookingCommand.Next
            Rx.of("""^(?:повтори(?:\s+(?:шаг|ещё|еще))?|ещё раз|еще раз|что\s+там|что\s+делать|не\s+расслышал\w*)$""").containsMatchIn(t) -> CookingCommand.Repeat
            Rx.of("""^(?:назад|предыдущий(?:\s+шаг)?|вернись|на\s+шаг\s+назад)$""").containsMatchIn(t) -> CookingCommand.Back
            Rx.of("""^(?:что\s+(?:нужно|надо)(?:\s+взять)?|ингредиенты|какие\s+ингредиенты|что\s+входит|список\s+продуктов)$""").containsMatchIn(t) -> CookingCommand.Ingredients
            Rx.of("""^(?:поставь\s+)?таймер(?:\s+на\s+это)?$|^засеки(?:\s+время)?$""").containsMatchIn(t) -> CookingCommand.Timer
            else -> Rx.of("""^(?:шаг|перейди\s+к\s+шагу|на\s+шаг)\s+(\d+)$""").find(DevicePhrases.digitize(t))?.let { CookingCommand.Go(it.groupValues[1].toInt()) }
        }
    }

    /** Разбор заметки: «Ингредиенты: …» отдельно, остальное — шаги (по строкам или предложениям). */
    fun parse(note: Note): Recipe {
        val lines = note.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val head = Rx.of("""^ингредиент\w*\s*[:\-—]?\s*(.*)$""")
        val stepsHead = Rx.of("""^(?:приготовление|шаги|способ\s+приготовления|как\s+готовить|рецепт)\s*[:\-—]?\s*(.*)$""")
        var ingredients: String? = null
        val body = ArrayList<String>()
        var mode = 0 // 0 — до разделов, 1 — ингредиенты, 2 — шаги
        for (line in lines) {
            val low = RuTokenizer.normalize(line)
            val h = head.find(low)
            val s = stepsHead.find(low)
            when {
                h != null -> { mode = 1; ingredients = line.substring(line.length - h.groupValues[1].length).trim().ifEmpty { null } }
                s != null -> { mode = 2; s.groupValues[1].takeIf { it.isNotBlank() }?.let { body += line.substring(line.length - it.length) } }
                mode == 1 && looksLikeIngredients(line) -> ingredients = listOfNotNull(ingredients, line.trimStart('-', '•', '*', ' ')).joinToString(", ")
                mode == 1 -> { mode = 2; body += line }
                else -> body += line
            }
        }
        val steps = body.flatMap { l ->
            val clean = l.replace(Regex("""^\s*(?:\d+[.)]|[-•*])\s*"""), "")
            // Одна длинная строка — делим по предложениям.
            if (body.size == 1) clean.split(Regex("""(?<=[.!])\s+""")).filter { it.isNotBlank() } else listOf(clean)
        }.map { it.trim().trimEnd(';') }.filter { it.isNotEmpty() }
        return Recipe(note.title.replace(Regex("""(?i)^рецепт[:\s]+"""), "").ifBlank { note.title }, ingredients, steps)
    }

    /** Продолжение списка продуктов: пункт с маркером или короткая строка без точки; шаги заканчиваются точкой и не нумеруются как продукты. */
    private fun looksLikeIngredients(line: String): Boolean {
        if (Regex("""^\s*\d+[.)]""").containsMatchIn(line)) return false
        if (Regex("""^\s*[-•*]""").containsMatchIn(line)) return true
        return !line.trimEnd().endsWith(".") && line.split(Regex("""\s+""")).size <= 8 && !Regex("""(?iu)минут|час""").containsMatchIn(line)
    }

    /** Лучшая заметка под название блюда: совпадение слов в заголовке важнее, чем в тексте. */
    fun find(notes: List<Note>, dish: String): Note? {
        val want = ai.loli.core.nlp.TextAnalysis.stems(dish).toSet()
        if (want.isEmpty()) return null
        return notes.map { n ->
            val inTitle = ai.loli.core.nlp.TextAnalysis.stems(n.title).count { it in want }
            val inBody = ai.loli.core.nlp.TextAnalysis.stems(n.content).count { it in want }
            n to (inTitle * 3 + minOf(inBody, 3))
        }.filter { it.second >= 3 || (it.second > 0 && want.size == 1) }.maxByOrNull { it.second }?.first
    }
}

/** Идущая готовка. */
class CookingSession(val recipe: Recipe) {
    var index = 0
        private set
    val finished: Boolean get() = index >= recipe.steps.size

    fun intro(): String {
        val ing = recipe.ingredients?.let { " Понадобится: $it." } ?: ""
        return "Готовим: ${recipe.title}.$ing Всего ${RuFormat.count(recipe.steps.size, "шаг", "шага", "шагов")}. Говорите «дальше», «повтори» или «назад». Первый шаг: ${recipe.steps.first()}${timerHint(0)}"
    }

    private fun timerHint(i: Int): String = DevicePhrases.duration(recipe.steps[i])?.takeIf { it >= 30 }?.let {
        " Скажите «таймер», чтобы поставить на ${DevicePhrases.describeDuration(it)}."
    } ?: ""

    fun current(): String = "Шаг ${index + 1} из ${recipe.steps.size}. ${recipe.steps[index]}${timerHint(index)}"

    fun next(): String {
        if (index >= recipe.steps.lastIndex) { index = recipe.steps.size; return "Это был последний шаг. Блюдо готово — приятного аппетита!" }
        index++
        return current()
    }

    fun back(): String {
        if (index == 0) return "Это первый шаг. ${current()}"
        index--
        return current()
    }

    fun go(step: Int): String {
        if (step !in 1..recipe.steps.size) return "В рецепте ${RuFormat.count(recipe.steps.size, "шаг", "шага", "шагов")}."
        index = step - 1
        return current()
    }

    fun ingredients(): String = recipe.ingredients?.let { "Понадобится: $it." } ?: "Отдельного списка ингредиентов в заметке нет."

    /** Секунды из текущего шага для таймера. */
    fun timerSeconds(): Int? = if (finished) null else DevicePhrases.duration(recipe.steps[index])?.takeIf { it >= 30 }
}
