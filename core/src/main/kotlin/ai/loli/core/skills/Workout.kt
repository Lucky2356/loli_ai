package ai.loli.core.skills

import ai.loli.core.assistant.RuFormat
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx

/** Тренировка голосом: упражнения по очереди, на каждое — таймер Лоли. */
object Workout {
    data class Exercise(val name: String, val seconds: Int, val tip: String = "")
    data class Program(val name: String, val habit: String, val exercises: List<Exercise>) {
        val minutes: Int get() = (exercises.sumOf { it.seconds } + 59) / 60
    }

    val MORNING = Program(
        "Зарядка", "зарядка",
        listOf(
            Exercise("Ходьба на месте", 60, "поднимайте колени повыше"),
            Exercise("Вращения плечами", 30, "вперёд, потом назад"),
            Exercise("Наклоны в стороны", 30, "руки на поясе"),
            Exercise("Приседания", 45, "спина прямая, колени не выходят за носки"),
            Exercise("Отжимания", 30, "можно от стены или с колен"),
            Exercise("Выпады", 45, "по очереди каждой ногой"),
            Exercise("Планка", 30, "тело в одну линию"),
            Exercise("Растяжка", 60, "потянитесь вверх, потом наклонитесь к носкам"),
        ),
    )
    val STRETCH = Program(
        "Растяжка", "растяжка",
        listOf(
            Exercise("Наклоны головы", 30, "медленно, без рывков"),
            Exercise("Потягивание вверх", 30, "руки в замок над головой"),
            Exercise("Наклон к ногам", 45, "колени можно чуть согнуть"),
            Exercise("Бабочка", 45, "сидя, стопы вместе"),
            Exercise("Кошка-корова", 45, "на четвереньках, прогибайте и выгибайте спину"),
            Exercise("Поза ребёнка", 60, "расслабьтесь и дышите"),
        ),
    )
    val ABS = Program(
        "Пресс", "пресс",
        listOf(
            Exercise("Скручивания", 45),
            Exercise("Велосипед", 45, "локоть к противоположному колену"),
            Exercise("Планка", 40),
            Exercise("Подъёмы ног лёжа", 40, "поясница прижата к полу"),
            Exercise("Боковая планка, левая сторона", 30),
            Exercise("Боковая планка, правая сторона", 30),
        ),
    )
    val OFFICE = Program(
        "Разминка для спины", "разминка",
        listOf(
            Exercise("Вращения плечами", 30),
            Exercise("Повороты корпуса сидя", 30),
            Exercise("Растяжка шеи", 30, "ухо к плечу, по очереди"),
            Exercise("Сведение лопаток", 30),
            Exercise("Наклоны вперёд стоя", 30),
            Exercise("Гимнастика для глаз", 30, "посмотрите вдаль, потом на кончик носа"),
        ),
    )
    val ALL = listOf(MORNING, STRETCH, ABS, OFFICE)

    private val START = Rx.of("""^(?:давай\s+|давайте\s+)?(?:начни|начн[её]м|включи|запусти|делаем|сделаем|проведи|хочу|будем\s+делать|давай\s+сделаем)?\s*(?:мне\s+)?(?:утреннюю\s+)?(зарядк\p{L}*|тренировк\p{L}*|потренируемся|разминк\p{L}*|растяжк\p{L}*|упражнени\p{L}*\s+для\s+спины|упражнени\p{L}*\s+на\s+пресс|пресс|тренировк\p{L}*\s+на\s+пресс)(?:\s+(?:на|для)\s+(\S+(?:\s+\S+)?))?$""")

    /** Какую программу начать; null — это не про тренировку. */
    fun start(text: String): Program? {
        val t = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?')
        // «Я сделала зарядку» — отметка привычки, а не начало.
        if (Rx.of("""^(?:я\s+)?(?:сделал|сделала|сделали|закончил|закончила)\b""").containsMatchIn(t)) return null
        val m = START.find(t) ?: return null
        // Голое «зарядка» без глагола — тоже начало, но «тренировка завтра в 7» — нет (там хвост).
        val what = m.groupValues[1] + " " + m.groupValues[2]
        return when {
            Rx.of("""пресс""").containsMatchIn(what) -> ABS
            Rx.of("""растяжк""").containsMatchIn(what) -> STRETCH
            Rx.of("""спин|разминк|офис|шею|шеи""").containsMatchIn(what) -> OFFICE
            else -> MORNING
        }
    }

    fun isList(text: String): Boolean =
        Rx.of("""^(?:какие\s+(?:у\s+тебя\s+)?(?:есть\s+)?(?:тренировки|зарядки|упражнения)|список\s+тренировок)$""").containsMatchIn(RuTokenizer.normalize(text).trim().trimEnd('?', '.'))

    fun listText(): String = "Есть тренировки: " + ALL.joinToString("; ") { "${it.name.lowercase()} — ${RuFormat.count(it.minutes, "минута", "минуты", "минут")}" } +
        ". Скажите, например: «начни зарядку» или «давай растяжку»."

    sealed interface Command {
        data object Next : Command
        data object Skip : Command
        data object Repeat : Command
        data object Stop : Command
    }

    fun command(text: String): Command? {
        val t = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?', ',')
        return when {
            Rx.of("""^(?:хватит|стоп|закончим|закончили|заверши|прекрати|отбой)(?:\s+(?:тренировк\p{L}*|зарядк\p{L}*|разминк\p{L}*|растяжк\p{L}*))?$|^(?:я\s+)?устал[аи]?$|^больше\s+не\s+могу$""").containsMatchIn(t) -> Command.Stop
            Rx.of("""^(?:пропусти|пропустить|пропускаем|следующее\s+упражнение\s+без\s+этого|не\s+буду\s+это)$""").containsMatchIn(t) -> Command.Skip
            Rx.of("""^(?:дальше|далее|следующее|следующее\s+упражнение|давай\s+дальше|готово|сделал[аи]?|сделано|ок(?:ей)?|поехали|продолжаем|продолжай)$""").containsMatchIn(t) -> Command.Next
            Rx.of("""^(?:повтори|ещё раз|еще раз|какое\s+упражнение|что\s+делать|что\s+сейчас)$""").containsMatchIn(t) -> Command.Repeat
            else -> null
        }
    }
}

/** Идущая тренировка. */
class WorkoutSession(val program: Workout.Program) {
    var index = 0
        private set
    var done = 0
        private set
    val finished: Boolean get() = index >= program.exercises.size
    val current: Workout.Exercise? get() = program.exercises.getOrNull(index)

    fun intro(): String =
        "${program.name}: ${RuFormat.count(program.exercises.size, "упражнение", "упражнения", "упражнений")}, около ${RuFormat.count(program.minutes, "минуты", "минут", "минут")}. " +
            "Я засекаю время на каждое; после сигнала скажите «дальше». «Пропусти» — следующее, «хватит» — закончить. " + step()

    fun step(): String {
        val e = current ?: return ""
        val tip = if (e.tip.isNotBlank()) " — ${e.tip}" else ""
        return "${index + 1} из ${program.exercises.size}: ${e.name}, ${describe(e.seconds)}$tip."
    }

    /** Упражнение выполнено (или пропущено) — к следующему. */
    fun advance(skipped: Boolean) {
        if (!skipped) done++
        index++
    }

    private fun describe(s: Int): String = if (s % 60 == 0) RuFormat.count(s / 60, "минута", "минуты", "минут") else RuFormat.count(s, "секунда", "секунды", "секунд")
}
