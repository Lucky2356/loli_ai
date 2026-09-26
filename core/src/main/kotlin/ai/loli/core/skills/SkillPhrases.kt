package ai.loli.core.skills

import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.nlp.RuNumbers
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Разобранная команда навыка. */
sealed interface SkillCommand {
    data class Weather(val query: WeatherQuery) : SkillCommand
    data class Rates(val query: RatesQuery) : SkillCommand
    data class News(val topic: NewsTopic) : SkillCommand
    data class NewsDetails(val index: Int) : SkillCommand
    data class Fact(val query: String) : SkillCommand
    data class ReadMessages(val from: String?) : SkillCommand
    data class ReplyMessage(val to: String?, val text: String, val raw: String) : SkillCommand
    data class Screen(val mode: ScreenMode) : SkillCommand
    data class ContactNumber(val name: String) : SkillCommand
    data class Calendar(val from: LocalDate, val to: LocalDate, val nextOnly: Boolean = false) : SkillCommand
    data class RadioPlay(val query: String?) : SkillCommand
    data object RadioStop : SkillCommand
    data object RadioNext : SkillCommand
    data object FindPhone : SkillCommand
    data object TimersLeft : SkillCommand
    data class TimersCancel(val label: String?) : SkillCommand
    data class SavePlace(val name: String) : SkillCommand
    data class PlaceRemind(val text: String, val place: String, val onLeave: Boolean) : SkillCommand
    data object ListPlaces : SkillCommand
    data class SetName(val name: String) : SkillCommand
    data object AskName : SkillCommand
    data class SetCity(val city: String) : SkillCommand
    data class StartGame(val game: Game?) : SkillCommand
    data class Tale(val request: Tales.Request) : SkillCommand
}

enum class ScreenMode { READ, SUMMARY, TRANSLATE }

/** Разбор фраз новых навыков. Всё офлайн; сеть нужна уже для ответа. */
object SkillPhrases {
    private fun rx(p: String) = Rx.of(p)

    fun norm(text: String): String = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?', ',').replace(Regex("""\s+"""), " ")

    /** Фраза — это запись/напоминание/задача, а не вопрос («напомни взять зонт»). */
    private val RECORD_VERB = rx("""^(?:напомни|запиши|записать|добавь|создай|поставь задачу|заметка|заметку|купи|список|сохрани заметку|потратил|потратила|заплатил|заплатила)\b""")

    fun parse(text: String, today: LocalDate, assistantName: String = "Лоли"): SkillCommand? {
        val t = norm(text)
        if (t.isEmpty()) return null
        profile(t, text)?.let { return it }
        places(t, text)?.let { return it }
        if (RECORD_VERB.containsMatchIn(t)) return null
        // Явный поиск в интернете — это команда телефону, а не вопрос навыку.
        if (rx("""^(?:найди|поищи|посмотри)\s+в\s+(?:интернете|гугле|яндексе|сети)|^(?:загугли|погугли)\b""").containsMatchIn(t)) return null
        weather(t, text, today)?.let { return SkillCommand.Weather(it) }
        rates(t)?.let { return SkillCommand.Rates(it) }
        news(t)?.let { return it }
        messages(t, text)?.let { return it }
        screen(t)?.let { return SkillCommand.Screen(it) }
        contact(t)?.let { return SkillCommand.ContactNumber(it) }
        calendar(t, today)?.let { return it }
        radio(t)?.let { return it }
        if (isFindPhone(t)) return SkillCommand.FindPhone
        timers(t)?.let { return it }
        Game.parseStart(t)?.let { return SkillCommand.StartGame(it) }
        if (Game.isWhatToPlay(t)) return SkillCommand.StartGame(null)
        Tales.parse(t)?.let { return SkillCommand.Tale(it) }
        fact(t, assistantName)?.let { return SkillCommand.Fact(it) }
        return null
    }

    // ------------------------------------------------------------------ Погода

    private val WEATHER = rx(
        """погод|прогноз|(?:будет|пойдет|ожидается|обещают|идет)\s+(?:ли\s+)?(?:сегодня\s+|завтра\s+|сейчас\s+)?(?:дожд|снег|гроз|ливень)|(?:дождь|снег)\s+(?:сегодня\s+|завтра\s+)?(?:будет|пойдет|ожидается)|зонт|сколько\s+(?:сейчас\s+)?градусов|какая\s+(?:сейчас\s+|сегодня\s+|завтра\s+)?температура|(?:холодно|тепло|жарко|прохладно)\s+(?:ли\s+)?(?:сегодня|завтра|на улице|сейчас)|на улице\s+(?:холодно|тепло|жарко|дождь)|как\s+(?:мне\s+)?одеться|что\s+(?:мне\s+)?надеть""",
    )

    private val DATE_WORDS = setOf(
        "сегодня", "завтра", "послезавтра", "сейчас", "неделю", "неделе", "выходные", "выходных", "понедельник", "вторник", "среду", "четверг",
        "пятницу", "субботу", "воскресенье", "течение", "ближайшие", "ближайшее", "будет", "ли", "погода", "погоду", "какая", "утром", "вечером",
        "днем", "ночью", "улице", "городе", "моем", "нашем", "тоже", "а", "и", "сколько", "градусов",
    )

    fun weather(t: String, original: String, today: LocalDate): WeatherQuery? {
        if (!WEATHER.containsMatchIn(t)) return null
        if (rx("""\bпогод\S*\s+в\s+доме\b|прогноз\s+(?:расход|продаж|бюджет)""").containsMatchIn(t)) return null
        val aspect = when {
            t.contains("зонт") -> WeatherQuery.Aspect.UMBRELLA
            rx("""дожд|ливень|гроз""").containsMatchIn(t) -> WeatherQuery.Aspect.RAIN
            rx("""\bснег""").containsMatchIn(t) -> WeatherQuery.Aspect.SNOW
            rx("""на неделю|на неделе|на выходн|на 7 дней|на семь дней|на несколько дней""").containsMatchIn(t) -> WeatherQuery.Aspect.WEEK
            else -> WeatherQuery.Aspect.GENERAL
        }
        return WeatherQuery(place = weatherPlace(t, original), date = dayOf(t, today), aspect = aspect)
    }

    /** День в вопросе: сегодня/сейчас → null. */
    fun dayOf(t: String, today: LocalDate): LocalDate? {
        if (t.contains("послезавтра")) return today.plusDays(2)
        if (rx("""\bзавтра\b""").containsMatchIn(t)) return today.plusDays(1)
        rx("""через\s+(\d+|два|три|четыре|пять|шесть)\s+дн""").find(DevicePhrases.digitize(t))?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: mapOf("два" to 2, "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6)[m.groupValues[1]] ?: 1
            return today.plusDays(n.toLong())
        }
        val days = listOf(
            "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "среду" to DayOfWeek.WEDNESDAY, "четверг" to DayOfWeek.THURSDAY,
            "пятницу" to DayOfWeek.FRIDAY, "субботу" to DayOfWeek.SATURDAY, "воскресенье" to DayOfWeek.SUNDAY,
        )
        for ((w, d) in days) if (rx("""\b$w\b""").containsMatchIn(t)) {
            val date = today.with(TemporalAdjusters.nextOrSame(d))
            return if (date == today) null else date
        }
        return null
    }

    /** «в Казани», «во Владимире», «в Нижнем Новгороде», «в Ростове-на-Дону»; «дома» — сохранённое место. */
    fun weatherPlace(t: String, original: String): String? {
        if (rx("""\b(?:дома|у меня дома|у дома)\b""").containsMatchIn(t)) return "дом"
        val o = original.trim().trimEnd('.', '!', '?').replace(Regex("""\s+"""), " ")
        val m = Regex("""(?iu)(?:^|\s)(?:в|во)\s+""").findAll(o).toList()
        for (hit in m) {
            val words = o.substring(hit.range.last + 1).split(' ').filter { it.isNotBlank() }
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size && out.size < 3) {
                val w = words[i].trim(',', '.', '?', '!')
                val n = RuTokenizer.normalize(w)
                if (n in DATE_WORDS || n.isEmpty() || n.any { it.isDigit() }) break
                if (n == "на" && i + 1 < words.size && RuTokenizer.normalize(words[i + 1]).trim(',', '?').let { it in setOf("дону", "амуре", "волге", "неве") }) {
                    out += "на"; out += words[i + 1].trim(',', '.', '?', '!'); i += 2; continue
                }
                if (n in setOf("на", "в", "во", "и", "а", "у", "по", "с", "к")) break
                out += w
                i++
            }
            val place = out.joinToString(" ").trim()
            if (place.isNotEmpty() && RuTokenizer.normalize(place) !in DATE_WORDS && !rx("""^(?:понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье|выходные|течение|ближайш)""").containsMatchIn(RuTokenizer.normalize(place))) {
                return place.split('-').joinToString("-") { part -> part.split(' ').joinToString(" ") { w -> if (w == "на") w else w.replaceFirstChar { it.uppercase() } } }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Курсы

    fun rates(t: String): RatesQuery? {
        if (rx("""^курс(?:ы)?\s+валют|^какой\s+курс$|^курсы$""").containsMatchIn(t)) return RatesQuery()
        val codes = RatesService.codesIn(t)
        if (codes.isEmpty()) return null
        val asksRate = rx("""\bкурс|почем|сколько\s+(?:сейчас\s+)?стоит|цена\s+(?:на\s+)?(?:доллар|евро|юан|биткоин|биткойн|эфир)|сколько\s+(?:сейчас\s+)?рублей\s+(?:за|в)\s+(?:одном\s+|1\s+)?(?:доллар|евро|юан)""").containsMatchIn(t)
        val conversion = rx("""сколько\s+будет|сколько\s+это|это\s+сколько|переведи|пересчитай|посчитай|конвертируй|в\s+рублях|в\s+рубли|сколько\s+рублей|сколько\s+(?:долларов|евро|юаней|биткоинов)|в\s+долларах|в\s+доллары|в\s+евро|в\s+юанях""").containsMatchIn(t)
        val d = DevicePhrases.digitize(t)
        val rubAmount = rx("""(\d+(?:[.,]\d+)?)\s*(?:руб|₽|р\b)""").find(d)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val anyAmount = rx("""(\d+(?:[.,]\d+)?)""").find(d)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        if (conversion && anyAmount != null) {
            return if (rubAmount != null) RatesQuery(codes, rubAmount, fromRub = true) else RatesQuery(codes, anyAmount, fromRub = false)
        }
        if (asksRate) return RatesQuery(codes)
        return null
    }

    // ------------------------------------------------------------------ Новости

    private val NEWS = rx(
        """^(?:а\s+)?(?:(?:расскажи|прочитай|почитай|покажи|включи|какие|скажи|озвучь|давай)\s+)?(?:мне\s+)?(?:последние\s+|свежие\s+|главные\s+|сегодняшние\s+|самые\s+важные\s+)?новост|^что\s+нового(?:\s+в\s+мире|\s+в\s+стране|\s+в\s+спорте|\s+в\s+науке|\s+в\s+экономике|\s+в\s+технологиях)?$|^что\s+(?:сейчас\s+)?происходит\s+в\s+мире""",
    )

    fun news(t: String): SkillCommand? {
        if (rx("""^(?:подробнее|расскажи подробнее|открой\s+(?:эту\s+|первую\s+|вторую\s+|третью\s+|четвертую\s+|пятую\s+)?новость)$""").containsMatchIn(t)) {
            val idx = when {
                t.contains("втор") -> 1; t.contains("трет") -> 2; t.contains("четв") -> 3; t.contains("пят") -> 4
                else -> 0
            }
            return SkillCommand.NewsDetails(idx)
        }
        if (!NEWS.containsMatchIn(t)) return null
        val topic = when {
            rx("""спорт|футбол|хоккей""").containsMatchIn(t) -> NewsTopic.SPORT
            rx("""технолог|айти|\bit\b|гаджет|компьютер""").containsMatchIn(t) -> NewsTopic.TECH
            rx("""наук|техник|космос""").containsMatchIn(t) -> NewsTopic.SCIENCE
            rx("""эконом|финанс|бизнес|рынк""").containsMatchIn(t) -> NewsTopic.ECONOMY
            else -> NewsTopic.MAIN
        }
        return SkillCommand.News(topic)
    }

    // ------------------------------------------------------------------ Факты (Википедия)

    private val FACT = Regex(
        """^(?:а\s+)?(?:скажи\s+)?(?:кто\s+(?:такой|такая|такие|такое|был|была|были)|что\s+(?:такое|значит|означает)|что\s+ты\s+знаешь\s+(?:про|о|об)|расскажи\s+(?:мне\s+)?(?:про|о|об)|кто\s+(?:написал|изобрел|придумал|открыл|основал|построил)|где\s+находится|сколько\s+лет\s+(?=\S))\s+(.+)$""",
    )
    private val NOT_FACT = rx("""^(?:ты|я|меня|тебя|себя|мы|вы|он|она|они|это|то|такое|мой|моя|мои|твой|твоя)$|задач|расход|заметк|напоминан|\bплан|\bдень\b|список|покупк|погод|курс|новост|сообщени|календар|себе|\bмне\b|себя|тебе""")

    fun fact(t: String, assistantName: String): String? {
        val m = FACT.find(t) ?: return null
        val subject = m.groupValues[1].trim().trim('«', '»', '"', '?')
        if (subject.length < 2 || NOT_FACT.containsMatchIn(subject)) return null
        if (RuTokenizer.normalize(subject) in setOf(RuTokenizer.normalize(assistantName), "лоли", "алиса", "сири")) return null
        // «где находится» — вопрос о месте: спросим статью о самом месте.
        return subject
    }

    // ------------------------------------------------------------------ Сообщения

    private val READ_MSG = rx(
        """^(?:прочитай|прочти|зачитай|почитай|покажи|озвучь|проверь)\s+(?:мне\s+)?(?:мои\s+|все\s+)?(?:новые\s+|последние\s+|непрочитанные\s+|входящие\s+)?(?:сообщени|уведомлени|смс|эсэмэск|месседж|чаты)|^что\s+(?:мне\s+)?(?:пришло|написали|пишут)|^(?:есть\s+(?:ли\s+)?(?:у меня\s+)?|были\s+)?(?:новые\s+)?сообщения$|^есть\s+(?:ли\s+)?(?:у меня\s+)?(?:новые\s+)?сообщения|^кто\s+мне\s+(?:писал|написал)|^кто\s+(?:писал|написал)$|^что\s+(?:мне\s+)?(?:написал|написала|пишет|пишут)\s+(.+)$""",
    )

    fun messages(t: String, original: String): SkillCommand? {
        rx("""^(?:ответь|ответить|отправь\s+ответ|напиши\s+в\s+ответ)\b[,:]?\s*(.*)$""").find(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (rest.isEmpty()) return SkillCommand.ReplyMessage(null, "", rest)
            // «ответь маше: буду через 10 минут», «ответь ему буду…», «ответь буду…».
            val colon = rest.split(Regex("""\s*:\s*"""), limit = 2)
            if (colon.size == 2 && colon[0].split(' ').size <= 3) return SkillCommand.ReplyMessage(colon[0].removePrefix("на сообщение ").trim(), colon[1].trim(), rest)
            val first = rest.substringBefore(' ')
            val tail = rest.substringAfter(' ', "")
            return SkillCommand.ReplyMessage(first.takeIf { tail.isNotEmpty() }, tail.ifEmpty { rest }, rest)
        }
        val m = READ_MSG.find(t) ?: return null
        val from = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: rx("""(?:от|из)\s+(.+)$""").find(t)?.groupValues?.get(1)?.takeIf { !it.startsWith("всех") }
        return SkillCommand.ReadMessages(from?.trim())
    }

    // ------------------------------------------------------------------ Экран

    fun screen(t: String): ScreenMode? = when {
        rx("""^переведи\s+(?:мне\s+)?(?:экран|страниц|то\s+что\s+на\s+экране|что\s+на\s+экране|текст\s+на\s+экране|это)""").containsMatchIn(t) -> ScreenMode.TRANSLATE
        rx("""^(?:перескажи|кратко|о\s+ч[её]м|про\s+что)\s+(?:мне\s+)?(?:экран|страниц|стать|эту\s+(?:статью|страницу|новость)|это|текст|что\s+на\s+экране|эта\s+статья|эта\s+страница|этот\s+текст)|^о\s+ч[её]м\s+(?:эта\s+)?(?:статья|страница|текст)""").containsMatchIn(t) -> ScreenMode.SUMMARY
        rx("""^(?:что|кто)\s+(?:у\s+меня\s+)?(?:сейчас\s+)?на\s+экране|^прочитай\s+(?:мне\s+)?(?:экран|страниц|то\s+что\s+на\s+экране|что\s+на\s+экране|текст\s+на\s+экране|с\s+экрана)""").containsMatchIn(t) -> ScreenMode.READ
        else -> null
    }

    // ------------------------------------------------------------------ Контакты

    fun contact(t: String): String? {
        val m = rx("""^(?:какой|скажи|назови|продиктуй|дай|найди|подскажи)\s+(?:мне\s+)?(?:номер|телефон)(?:\s+телефона)?\s+(?:у\s+)?(.+)$|^(?:номер|телефон)\s+(?:телефона\s+)?(?:у\s+)?(.+)$|^какой\s+у\s+(.+?)\s+(?:номер|телефон)(?:\s+телефона)?$""").find(t) ?: return null
        val name = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (name in setOf("мой", "мне", "меня", "телефона", "этот")) return null
        return name
    }

    // ------------------------------------------------------------------ Календарь

    fun calendar(t: String, today: LocalDate): SkillCommand? {
        if (rx("""^когда\s+(?:у\s+меня\s+)?(?:следующая|ближайшая|моя\s+следующая)\s+(?:встреча|событие|мероприятие)""").containsMatchIn(t)) {
            return SkillCommand.Calendar(today, today.plusDays(30), nextOnly = true)
        }
        val hit = rx("""в\s+календаре|^(?:какие|что\s+за|есть\s+ли)\s+(?:у\s+меня\s+)?(?:встречи|события|мероприятия)|^(?:мои\s+)?(?:встречи|события)\s+(?:на\s+)?(?:сегодня|завтра|неделю|неделе)""").containsMatchIn(t)
        if (!hit || rx("""^(?:добавь|запиши|создай|поставь|внеси)""").containsMatchIn(t)) return null
        val (from, to) = when {
            rx("""неделю|неделе|7 дней|семь дней""").containsMatchIn(t) -> today to today.plusDays(6)
            else -> (dayOf(t, today) ?: today).let { it to it }
        }
        return SkillCommand.Calendar(from, to)
    }

    // ------------------------------------------------------------------ Радио

    fun radio(t: String): SkillCommand? {
        if (rx("""^(?:выключи|останови|отключи|стоп|хватит|убери|заглуши|выруби)\s+(?:это\s+)?радио""").containsMatchIn(t)) return SkillCommand.RadioStop
        if (rx("""^(?:следующ(?:ая|ую)|другую|другая|переключи(?:\s+на)?\s+(?:другую|следующую))\s+(?:радио)?станци|^(?:другое|следующее)\s+радио$|^переключи\s+радио$""").containsMatchIn(t)) return SkillCommand.RadioNext
        rx("""^(?:включи|поставь|запусти|найди|играй|хочу\s+(?:по)?слушать|давай)\s+(?:мне\s+)?(?:какое[- ]нибудь\s+|любое\s+|интернет[- ]?)?радио(?:\s+(.+))?$""").find(t)?.let { return SkillCommand.RadioPlay(it.groupValues[1].takeIf { g -> g.isNotBlank() }) }
        rx("""^радио(?:\s+(.+))?$""").find(t)?.let { return SkillCommand.RadioPlay(it.groupValues[1].takeIf { g -> g.isNotBlank() }) }
        rx("""^(?:включи|поставь|запусти)\s+(.+?)\s+радио$""").find(t)?.let { return SkillCommand.RadioPlay(it.groupValues[1] + " радио") }
        rx("""^(?:включи|поставь|запусти)\s+(.+)$""").find(t)?.let { m ->
            val q = m.groupValues[1]
            val station = RadioCatalog.builtIn(q)
            // Только явные названия станций: «включи Европу Плюс», «включи Ретро ФМ».
            if (station != null && (q.contains("фм") || q.contains("fm") || q.contains("плюс") || q.contains("радио") || q.contains("вести") || q.contains("маяк") || q.contains("шансон"))) return SkillCommand.RadioPlay(q)
        }
        return null
    }

    // ------------------------------------------------------------------ Найти телефон

    fun isFindPhone(t: String) = rx("""^(?:где\s+ты|ты\s+где|где\s+(?:мой\s+)?телефон|найди\s+(?:мой\s+)?(?:телефон|смартфон)|подай\s+голос|отзовись|позвони\s+(?:мне\s+)?громко|я\s+тебя\s+(?:не\s+)?(?:вижу|найду|потерял|потеряла)|где\s+же\s+ты)$""").containsMatchIn(t)

    // ------------------------------------------------------------------ Таймеры

    fun timers(t: String): SkillCommand? {
        if (rx("""сколько\s+(?:еще\s+)?(?:времени\s+)?осталось|когда\s+(?:сработает|зазвонит|закончится|прозвенит)\s+таймер|сколько\s+на\s+таймере|^какие\s+(?:у\s+меня\s+)?таймеры|^мои\s+таймеры|^покажи\s+таймер""").containsMatchIn(t)) return SkillCommand.TimersLeft
        rx("""^(?:отмени|выключи|останови|сбрось|удали|убери|отключи)\s+(?:все\s+)?таймер(?:ы|а)?(?:\s+(?:на\s+)?(.+))?$""").find(t)?.let { m ->
            return SkillCommand.TimersCancel(m.groupValues[1].takeIf { it.isNotBlank() && it != "все" })
        }
        return null
    }

    // ------------------------------------------------------------------ Места

    fun placeName(raw: String): String {
        val n = RuTokenizer.normalize(raw).trim(' ', ',', '.')
            .removePrefix("мой ").removePrefix("моя ").removePrefix("мое ").removePrefix("в ").removePrefix("на ").removePrefix("во ").removePrefix("к ")
        return when {
            rx("""^(?:дом|дома|домой|дому|из дома|из дому)$""").containsMatchIn(n) || n.startsWith("дом ") -> "дом"
            n.startsWith("работ") || n.startsWith("офис") -> "работа"
            n.startsWith("дач") -> "дача"
            n.startsWith("школ") -> "школа"
            n.startsWith("университет") || n.startsWith("универ") -> "университет"
            n.startsWith("спортзал") || n.startsWith("зал") || n.startsWith("фитнес") -> "спортзал"
            n.startsWith("магазин") -> "магазин"
            n.startsWith("садик") || n.startsWith("детск") -> "садик"
            else -> n.split(' ').take(2).joinToString(" ")
        }
    }

    fun places(t: String, original: String): SkillCommand? {
        if (rx("""^(?:какие|покажи|мои)\s+(?:у\s+меня\s+)?(?:напоминания\s+по\s+месту|места)|^какие\s+места\s+(?:ты\s+)?(?:знаешь|запомнила)""").containsMatchIn(t)) return SkillCommand.ListPlaces
        // Сохранить место: «запомни, я дома», «здесь моя работа, запомни», «запомни это место как дача».
        val saveVerb = rx("""\b(?:запомни|сохрани|отметь)\b""").containsMatchIn(t)
        if (saveVerb) {
            rx("""\bя\s+(?:сейчас\s+)?(?:дома|на работе|на даче|в офисе)\b""").find(t)?.let { return SkillCommand.SavePlace(placeName(it.value.removePrefix("я ").removePrefix("сейчас ").trim().removePrefix("сейчас "))) }
            rx("""(?:здесь|тут|это)\s+(?:находится\s+)?(?:мой|моя|мое|наш|наша|наше)\s+(\S+)""").find(t)?.let { return SkillCommand.SavePlace(placeName(it.groupValues[1])) }
            rx("""(?:это|здесь|тут)?\s*место\s+как\s+(.+)$""").find(t)?.let { return SkillCommand.SavePlace(placeName(it.groupValues[1])) }
        }
        // Напоминание по месту.
        // Текст напоминания — с «ё», как сказано.
        val lo = original.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("""\s+"""), " ")
        val m = rx("""^(?:лоли[,]?\s+)?напомни(?:\s+мне)?[,]?\s+(?:(.+?)[,]?\s+)?когда\s+(?:я\s+)?(буду|приду|приеду|вернусь|окажусь|доберусь|зайду|выйду|уйду|уеду|поеду)\s+(.+)$""").find(lo) ?: return null
        val before = m.groupValues[1].trim().trimEnd(',')
        val verb = m.groupValues[2]
        val rest = m.groupValues[3]
        val placeRx = rx("""^(домой|дома|из\s+дома|из\s+дому|с\s+работы|на\s+работу|на\s+работе|на\s+дачу|на\s+даче|в\s+\S+|во\s+\S+|на\s+\S+|к\s+\S+|из\s+\S+|с\s+\S+)(?:[,]?\s+(.*))?$""")
        val pm = placeRx.find(rest) ?: return null
        val place = placeName(pm.groupValues[1].replace(Regex("""^(?:из|с)\s+"""), ""))
        val after = pm.groupValues[2].trim().removePrefix("то ").removePrefix("напомни ").removePrefix("мне ").trim()
        val what = before.ifEmpty { after }.trim().trimEnd(',')
        if (what.isEmpty()) return null
        val onLeave = verb in setOf("выйду", "уйду", "уеду") || rx("""^(?:из|с)\s""").containsMatchIn(pm.groupValues[1])
        return SkillCommand.PlaceRemind(ai.loli.core.assistant.ActionTitle.clean(what).ifBlank { what }, place, onLeave)
    }

    // ------------------------------------------------------------------ Имя и город

    fun profile(t: String, original: String): SkillCommand? {
        if (rx("""^(?:а\s+)?(?:ты\s+знаешь\s+)?как\s+меня\s+зовут$|^кто\s+я$|^ты\s+помнишь\s+как\s+меня\s+зовут$""").containsMatchIn(t)) return SkillCommand.AskName
        val o = original.trim().trimEnd('.', '!', '?')
        Regex("""(?iu)^(?:(?:запомни[,]?\s+(?:что\s+)?)?(?:называй|зови)\s+меня|(?:запомни[,]?\s+(?:что\s+)?)?меня\s+зовут|мо[её]\s+имя)\s+[—-]?\s*(.+)$""").find(o)?.let { m ->
            val name = m.groupValues[1].trim().trim(',', '.', '«', '»', '"').split(Regex("""\s+""")).take(2).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            if (name.length in 2..30 && !rx("""\b(?:не|никак|ничего)\b""").containsMatchIn(RuTokenizer.normalize(name))) return SkillCommand.SetName(name)
        }
        Regex("""(?iu)^(?:запомни[,]?\s+(?:что\s+)?)?(?:я\s+живу|мы\s+живем|мы\s+живём|я\s+сейчас\s+живу)\s+(?:в|во)\s+(.+)$|^мой\s+город\s+[—-]?\s*(.+)$""").find(o)?.let { m ->
            val city = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trim(',', '.') ?: return null
            if (city.length in 2..40) return SkillCommand.SetCity(city.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        }
        return null
    }

    /** Число из фразы: «пять» → 5. */
    fun number(t: String): Int? = RuNumbers.mergeNumberWords(RuTokenizer.tokenize(t)).firstNotNullOfOrNull { it.number }?.toInt()
}
