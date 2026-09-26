package ai.loli.core.skills

import ai.loli.core.nlp.RuNumbers
import ai.loli.core.nlp.RuTokenizer
import kotlin.random.Random

/** Ход игры: ответ Лоли и закончилась ли игра. */
data class GameTurn(val text: String, val over: Boolean = false)

/** Голосовая игра. Пока идёт игра, реплики пользователя уходят в неё. */
sealed class Game {
    abstract val title: String
    abstract fun start(): String
    /** [known] — проверка города по сети (для «Городов»), null — без сети. */
    abstract suspend fun play(text: String, known: (suspend (String) -> Boolean)?): GameTurn

    // ------------------------------------------------------------------ Города
    class Cities(private val random: Random = Random.Default) : Game() {
        override val title = "Города"
        private val used = HashSet<String>()
        private var letter: Char? = null
        private var misses = 0

        override fun start(): String {
            val first = CITIES.random(random)
            used += key(first)
            letter = lastLetter(first)
            return "Играем в города! Я начинаю: $first. Вам на букву «${letter!!.uppercaseChar()}». Скажите «сдаюсь», чтобы закончить."
        }

        override suspend fun play(text: String, known: (suspend (String) -> Boolean)?): GameTurn {
            val t = text.trim().trimEnd('.', '!', '?')
            if (isGiveUp(t)) return GameTurn("Я победила! Было названо ${used.size} ${ai.loli.core.assistant.RuFormat.plural(used.size, "город", "города", "городов")}. Сыграем ещё — скажите «давай в города».", over = true)
            if (Regex("""(?iu)^(?:подскажи|подсказк|не знаю|какой город|помоги)""").containsMatchIn(t)) {
                val hint = CITIES.filter { key(it).first() == letter && key(it) !in used }.randomOrNull(random)
                return GameTurn(hint?.let { "Подсказка: например, $it. Но лучше своё!" } ?: "Я и сама не знаю городов на «${letter?.uppercaseChar()}». Скажите «сдаюсь»?")
            }
            val city = cleanCity(t)
            val k = key(city)
            if (k.isEmpty()) return GameTurn("Назовите город на букву «${letter?.uppercaseChar()}».")
            letter?.let { need ->
                if (k.first() != need) return GameTurn("${city.replaceFirstChar { it.uppercase() }} начинается не на «${need.uppercaseChar()}». Нужен город на «${need.uppercaseChar()}».")
            }
            if (k in used) return GameTurn("${city.replaceFirstChar { it.uppercase() }} уже был! Другой город на «${letter?.uppercaseChar()}».")
            val listed = CITIES.firstOrNull { key(it) == k }
            if (listed == null) {
                val ok = known?.let { runCatching { it(city) }.getOrDefault(false) }
                if (ok == false) {
                    misses++
                    return GameTurn("Не знаю такого города — «${city.replaceFirstChar { it.uppercase() }}». Попробуйте другой на «${letter?.uppercaseChar()}».")
                }
            }
            used += k
            val name = listed ?: city.split(' ', '-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val need = lastLetter(name)
            val mine = CITIES.filter { key(it).first() == need && key(it) !in used }.randomOrNull(random)
                ?: return GameTurn("$name — принято! А я не знаю больше городов на «${need.uppercaseChar()}». Вы победили! 🎉", over = true)
            used += key(mine)
            letter = lastLetter(mine)
            return GameTurn("$name — есть! Мой город: $mine. Вам на «${letter!!.uppercaseChar()}».")
        }

        companion object {
            val CITIES: List<String> by lazy {
                Cities::class.java.getResourceAsStream("/skills/cities.txt")?.bufferedReader(Charsets.UTF_8)?.readLines()
                    ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("Москва", "Архангельск", "Казань")
            }

            fun key(city: String): String = city.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}]"""), "")

            /** Последняя буква, с которой можно начать город: ь, ъ, ы, й пропускаем. */
            fun lastLetter(city: String): Char {
                val k = key(city)
                return k.lastOrNull { it !in "ьъый" } ?: k.last()
            }

            fun cleanCity(text: String): String = text.lowercase()
                .replace(Regex("""(?iu)^(?:город|ну|давай|может|пусть будет|а|тогда|мой город|я скажу|скажу)\s+"""), "")
                .replace(Regex("""(?iu)^(?:город)\s+"""), "")
                .trim(' ', ',', '.', '!', '?')
        }
    }

    // ------------------------------------------------------------------ Угадай число
    class GuessNumber(private val max: Int = 100, private val random: Random = Random.Default) : Game() {
        override val title = "Угадай число"
        private val secret = random.nextInt(1, max + 1)
        private var tries = 0

        override fun start() = "Я загадала число от 1 до $max. Угадайте! Я буду подсказывать: больше или меньше."

        override suspend fun play(text: String, known: (suspend (String) -> Boolean)?): GameTurn {
            if (isGiveUp(text)) return GameTurn("Это было число $secret. Сыграем ещё?", over = true)
            val n = Regex("""\d+""").find(ai.loli.core.assistant.DevicePhrases.digitize(text))?.value?.toIntOrNull()
                ?: return GameTurn("Назовите число от 1 до $max.")
            tries++
            return when {
                n < secret -> GameTurn("Больше, чем $n.")
                n > secret -> GameTurn("Меньше, чем $n.")
                else -> GameTurn("Угадали! Это $secret. ${ai.loli.core.assistant.RuFormat.count(tries, "попытка", "попытки", "попыток")}${if (tries <= 7) " — отличный результат!" else "."}", over = true)
            }
        }
    }

    // ------------------------------------------------------------------ Загадки
    class Riddles(private val random: Random = Random.Default) : Game() {
        override val title = "Загадки"
        private val order = RIDDLES.indices.shuffled(random).toMutableList()
        private var current = order.removeAt(0)
        private var attempts = 0
        private var score = 0

        override fun start() = "Отгадайте загадку! ${RIDDLES[current].first}"

        override suspend fun play(text: String, known: (suspend (String) -> Boolean)?): GameTurn {
            val (_, answers) = RIDDLES[current]
            val t = RuTokenizer.normalize(text)
            if (isGiveUp(text) && Regex("""(?iu)хватит|стоп|закончи|надоело|всё$|все$""").containsMatchIn(text)) {
                return GameTurn("Хорошо! Отгадано: $score. Приходите ещё за загадками.", over = true)
            }
            val giveUp = isGiveUp(text) || Regex("""(?iu)не знаю|ответ|скажи""").containsMatchIn(text)
            val correct = answers.any { a -> t.contains(RuTokenizer.normalize(a).take(maxOf(3, a.length - 2))) }
            return when {
                correct -> { score++; next("Правильно — ${answers.first()}!") }
                giveUp || attempts >= 2 -> next("Ответ: ${answers.first()}.")
                else -> { attempts++; GameTurn("Нет, не угадали. Попробуйте ещё или скажите «не знаю».") }
            }
        }

        private fun next(prefix: String): GameTurn {
            if (order.isEmpty()) return GameTurn("$prefix Загадки кончились — отгадано $score. Молодец!", over = true)
            current = order.removeAt(0); attempts = 0
            return GameTurn("$prefix Следующая: ${RIDDLES[current].first}")
        }
    }

    companion object {
        fun isGiveUp(text: String) = ai.loli.core.nlp.Rx.of("""(?iu)^(?:сдаюсь|я сдаюсь|хватит|стоп|закончим|закончи игру|конец игры|выход|надоело|не хочу играть|всё|все)\b""").containsMatchIn(text.trim())

        /** «Давай поиграем в города», «загадай число», «загадай загадку». */
        fun parseStart(text: String): Game? {
            val t = text.lowercase().replace('ё', 'е').trim().trimEnd('.', '!', '?')
            return when {
                Regex("""(?:играть|игра|поиграем|сыграем|давай|начнем)\s*(?:в|во)?\s*город|^города$|^игра в города$""").containsMatchIn(t) -> Cities()
                Regex("""угада(?:й|ть|ю)\s+число|загада(?:й|ла)\s+число|игра\s+(?:в\s+)?(?:угадай\s+)?число""").containsMatchIn(t) -> GuessNumber()
                Regex("""загад(?:ай|ывай)\s+(?:мне\s+|нам\s+)?загадк|(?:давай|хочу|расскажи|поиграем в|сыграем в)\s+загадк|^загадк[аиу]$|^загадай что[- ]нибудь$""").containsMatchIn(t) -> Riddles()
                else -> null
            }
        }

        /** Во что можно поиграть. */
        fun isWhatToPlay(text: String) = Regex("""(?iu)^(?:давай\s+)?(?:поиграем|сыграем|поиграй со мной|во что (?:можно )?поиграть|какие (?:есть )?игры|хочу (?:по)?играть)$""").containsMatchIn(text.trim().trimEnd('?', '!', '.'))

        val RIDDLES: List<Pair<String, List<String>>> = listOf(
            "Зимой и летом одним цветом. Что это?" to listOf("ёлка", "ель", "елка", "сосна"),
            "Без окон, без дверей — полна горница людей." to listOf("огурец"),
            "Сидит дед, во сто шуб одет. Кто его раздевает, тот слёзы проливает." to listOf("лук", "луковица"),
            "Висит груша — нельзя скушать." to listOf("лампочка", "лампа"),
            "Не лает, не кусает, а в дом не пускает." to listOf("замок"),
            "Два кольца, два конца, посередине гвоздик." to listOf("ножницы"),
            "Кто утром ходит на четырёх ногах, днём на двух, а вечером на трёх?" to listOf("человек"),
            "Что можно увидеть с закрытыми глазами?" to listOf("сон"),
            "Что становится больше, если его поставить вверх ногами?" to listOf("число 6", "шесть", "6"),
            "У кого есть шляпа без головы и нога без сапога?" to listOf("гриб"),
            "Растёт она вниз головой, не летом растёт, а зимой. Но солнце её припечёт — заплачет она и умрёт." to listOf("сосулька"),
            "Белая морковка зимой растёт." to listOf("сосулька"),
            "Зубов много, а ничего не ест." to listOf("расчёска", "расческа", "гребень"),
            "Что всегда идёт, но никогда не приходит?" to listOf("завтра", "время"),
            "Что можно приготовить, но нельзя съесть?" to listOf("уроки", "урок"),
            "Кто говорит на всех языках?" to listOf("эхо"),
            "Чем больше из неё берёшь, тем больше она становится." to listOf("яма"),
            "Летом серый, зимой белый. Кто это?" to listOf("заяц"),
            "Хвост пушистый, мех золотистый, в лесу живёт, в деревне кур крадёт." to listOf("лиса", "лисица"),
            "Зимой спит, летом ульи ворошит." to listOf("медведь"),
            "Без рук, без ног, а ворота открывает." to listOf("ветер"),
            "Течёт, течёт — не вытечет, бежит, бежит — не выбежит." to listOf("река"),
            "Над бабушкиной избушкой висит хлеба краюшка." to listOf("месяц", "луна"),
            "Кругом вода, а с питьём беда." to listOf("море", "океан"),
            "Сто одёжек и все без застёжек." to listOf("капуста"),
            "Сидит девица в темнице, а коса на улице." to listOf("морковь", "морковка"),
            "Маленький, удаленький, сквозь землю прошёл, красную шапочку нашёл." to listOf("гриб", "подосиновик"),
            "Что принадлежит вам, но другие пользуются этим чаще?" to listOf("имя"),
            "Не море, не земля, корабли не плавают, а ходить нельзя." to listOf("болото"),
            "Какой рукой лучше размешивать чай?" to listOf("ложкой", "ложка"),
            "Что нельзя удержать и десяти минут, хотя оно легче пёрышка?" to listOf("дыхание", "вдох"),
            "Шёл муж с женой, брат с сестрой да шурин с зятем. Сколько всего человек?" to listOf("три", "3", "трое"),
        )
    }
}

/** Выбор случайной игры, если человек просто хочет поиграть. */
fun randomGame(random: Random = Random.Default): Game = listOf<() -> Game>({ Game.Cities() }, { Game.GuessNumber() }, { Game.Riddles() }).random(random)()
