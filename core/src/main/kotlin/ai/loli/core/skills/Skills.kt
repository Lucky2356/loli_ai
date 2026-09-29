package ai.loli.core.skills

import ai.loli.core.assistant.AssistantAction
import ai.loli.core.assistant.AssistantPlan
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.LocalCommandParser
import ai.loli.core.assistant.LockPolicy
import ai.loli.core.assistant.SkillAccess
import ai.loli.core.assistant.RuFormat
import ai.loli.core.nlp.RuStemmer
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.util.Logger
import ai.loli.core.util.TimeSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Ответ навыка. */
sealed interface SkillOutcome {
    data class Say(
        val text: String,
        /** Ждём продолжения (игра, «в каком городе?», «ответить?»). */
        val followUp: Boolean = false,
        val permission: Permission? = null,
        val speakLanguage: String? = null,
        /** Личное (сообщения, экран, контакты, календарь, места): не пересказывать AI. */
        val sensitive: Boolean = false,
    ) : SkillOutcome

    /** Выполнить обычные действия (открыть ссылку, поиск в интернете). */
    data class Run(val plan: AssistantPlan) : SkillOutcome
}

/** Дополнения к плану на день: погода и события календаря. */
data class AgendaExtras(val weather: String? = null, val events: List<String> = emptyList(), val birthdays: List<String> = emptyList())

/** Текстовый запрос к облачному AI (если подключён): system, user, maxTokens → ответ. */
typealias SkillAi = suspend (system: String, user: String, maxTokens: Int) -> String?

/**
 * «Живые» навыки Лоли: погода, курсы, новости, справка, сообщения, экран, контакты, календарь,
 * радио, «найди телефон», таймеры, места, имя пользователя, игры и сказки.
 */
class Skills(
    private val host: SkillHost = NoSkillHost,
    http: HttpClient? = null,
    private val time: TimeSource,
) {
    private val weatherService = http?.let(::WeatherService)
    private val ratesService = http?.let(::RatesService)
    private val newsService = http?.let(::NewsService)
    private val wikiService = http?.let(::WikiService)
    private val radioCatalog = RadioCatalog(http)

    /** Идёт игра — реплики уходят в неё. */
    var game: Game? = null
        private set
    private var awaitGameChoice = false
    private var awaitCity: WeatherQuery? = null
    private var lastNews: List<NewsService.Item> = emptyList()
    private var newsShown = 0
    private var replyTarget: IncomingMessage? = null
    /** Спросили «Что ответить Маше?» — следующая фраза и есть ответ. */
    private var awaitReply = false
    private var stations: List<RadioStation> = emptyList()
    private var stationIndex = 0
    private var lastTale: String? = null
    private var lastFact: String? = null
    /** Последний «живой» ответ (погода, курс, новости, сказка) — для «а завтра?», «а евро?», «ещё». */
    private var lastSkill: SkillCommand? = null
    private var lastSkillAt = time.now()
    private var lastTurn = time.now()

    /** Навык ждёт ответа — движок передаёт следующую фразу сюда. Ожидание не вечное: через 3 минуты молчания забываем. */
    val busy: Boolean get() {
        val waiting = game != null || awaitGameChoice || awaitCity != null || awaitReply
        if (waiting && Duration.between(lastTurn, time.now()) > PENDING_TTL) reset()
        return game != null || awaitGameChoice || awaitCity != null || awaitReply
    }

    /** Экран погас: забываем, кому отвечали, — игра и прочее остаются. */
    fun forgetPrivate() { replyTarget = null; awaitReply = false }

    fun reset() {
        game = null; awaitGameChoice = false; awaitCity = null; replyTarget = null; awaitReply = false
    }

    /**
     * Фраза, пока навык ждёт ответа, — это новая команда, а не ход в игре или название города?
     * «запиши расход 300» во время «Угадай число» — расход, а не число 300.
     */
    fun interruptedBy(text: String, cfg: AssistantSettings, isCommand: (String) -> Boolean): Boolean {
        if (!busy) return false
        val t = SkillPhrases.norm(text)
        if (game == null && (LocalCommandParser.isDialogEnd(t) || LocalCommandParser.isNo(t) || t in setOf("спасибо", "не надо", "отмена", "отмени"))) return true
        if (t.split(' ').size < 2) return false
        // Текст ответа («буду через 10 минут») похож на команду, но это ответ: прерывает только явный навык.
        if (awaitReply) return SkillPhrases.parse(text, time.today(), cfg.assistantName).let { it != null && it !is SkillCommand.StartGame }
        return when (val cmd = SkillPhrases.parse(text, time.today(), cfg.assistantName)) {
            null -> isCommand(text)
            is SkillCommand.StartGame -> false
            else -> cmd !is SkillCommand.Weather || awaitCity == null
        }
    }

    /** Понимает ли навык фразу (для выбора варианта распознавания). */
    fun recognizes(text: String, name: String): Boolean = SkillPhrases.parse(text, time.today(), name) != null

    suspend fun handle(text: String, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? = handle(text, cfg, ai, parsed = null)

    private suspend fun handle(text: String, cfg: AssistantSettings, ai: SkillAi?, parsed: SkillCommand?): SkillOutcome? {
        try {
            lastTurn = time.now()
            if (parsed == null) continuation(text, cfg, ai)?.let { return it }
            // Играет радио: «дальше», «переключи» — следующая станция из того же списка, что и «следующая станция».
            if (host.radioPlaying() && RADIO_NEXT.containsMatchIn(SkillPhrases.norm(text))) return radioNext()
            val cmd = parsed ?: SkillPhrases.parse(text, time.today(), cfg.assistantName) ?: return null
            val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
            if (cfg.locked && private(cmd)) {
                return SkillOutcome.Say("Разблокируйте телефон — ${privateWhat(cmd)} без разблокировки не показываю.")
            }
            if (policy != null && !policy.allowsSkill(access(cmd))) {
                return SkillOutcome.Say("Разблокируйте телефон — это без разблокировки не разрешено (меняется в настройках Лоли).")
            }
            val out = execute(cmd, cfg, ai)
            if (cmd is SkillCommand.Weather || cmd is SkillCommand.Rates || cmd is SkillCommand.News || cmd is SkillCommand.Tale) {
                lastSkill = cmd; lastSkillAt = time.now()
            }
            return if (out is SkillOutcome.Say && private(cmd)) out.copy(sensitive = true) else out
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsPermission) {
            return SkillOutcome.Say(permissionText(e.permission, cfg.assistantName), permission = e.permission)
        } catch (e: InfoUnavailable) {
            return SkillOutcome.Say("Не получилось узнать: ${e.message}. Проверьте интернет и спросите ещё раз.")
        } catch (e: Exception) {
            Logger.w(TAG, "Навык не сработал", e)
            return SkillOutcome.Say("Не получилось: ${e.message ?: "ошибка"}. Попробуйте ещё раз.")
        }
    }

    // ------------------------------------------------------------------ Живой диалог: уточнения и «ещё»

    private fun recentSkill(): SkillCommand? = lastSkill?.takeIf { Duration.between(lastSkillAt, time.now()) <= PENDING_TTL }

    /**
     * Уточнение к последнему ответу: «а завтра?», «а в Казани?», «а на выходных?» после погоды,
     * «а евро?» после курса, «а спорт?» после новостей. null — это не уточнение.
     */
    suspend fun followUp(text: String, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? {
        val last = recentSkill() ?: return null
        val t = SkillPhrases.norm(text)
        if (!FOLLOW_UP.containsMatchIn(t) || t.split(' ').size > 6) return null
        val body = t.replace(FOLLOW_UP, "").trim()
        if (body.isEmpty()) return null
        val cmd: SkillCommand = when (last) {
            is SkillCommand.Weather -> {
                val q = last.query
                val date = SkillPhrases.dayOf(body, time.today())
                val place = SkillPhrases.weatherPlace(body, text).takeIf { Regex("""(?:^|\s)(?:в|во)\s""").containsMatchIn(body) }
                val week = Regex("""на\s+(?:неделю|неделе|выходн|7 дней|семь дней)""").containsMatchIn(body)
                val now = Regex("""^(?:сегодня|сейчас)$""").containsMatchIn(body)
                if (date == null && place == null && !week && !now) return null
                SkillCommand.Weather(q.copy(
                    place = place ?: q.place,
                    date = if (now || week) null else date ?: q.date,
                    aspect = if (week) WeatherQuery.Aspect.WEEK else if (q.aspect == WeatherQuery.Aspect.WEEK) WeatherQuery.Aspect.GENERAL else q.aspect,
                ))
            }
            is SkillCommand.Rates -> {
                val codes = RatesService.codesIn(body).ifEmpty { return null }
                SkillCommand.Rates(last.query.copy(currencies = codes))
            }
            is SkillCommand.News -> {
                val topic = when {
                    Regex("""спорт|футбол|хоккей""").containsMatchIn(body) -> NewsTopic.SPORT
                    Regex("""технолог|айти|гаджет""").containsMatchIn(body) -> NewsTopic.TECH
                    Regex("""наук|космос""").containsMatchIn(body) -> NewsTopic.SCIENCE
                    Regex("""эконом|финанс|бизнес""").containsMatchIn(body) -> NewsTopic.ECONOMY
                    Regex("""главн|в мире|в стране""").containsMatchIn(body) -> NewsTopic.MAIN
                    else -> return null
                }
                SkillCommand.News(topic)
            }
            else -> return null
        }
        return handle(text, cfg, ai, parsed = cmd)
    }

    /** «Ещё», «давай ещё», «дальше» — продолжение последнего ответа: следующие новости, другая сказка. */
    suspend fun more(text: String, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? {
        val t = SkillPhrases.norm(text)
        if (host.radioPlaying() && RADIO_NEXT.containsMatchIn(t)) return radioNext()
        if (!MORE.containsMatchIn(t)) return null
        return when (val last = recentSkill()) {
            is SkillCommand.News -> {
                val next = lastNews.drop(newsShown).take(5)
                val from = newsShown
                newsShown += next.size
                lastSkillAt = time.now()
                SkillOutcome.Say(NewsService.answer(last.topic, next, from = from))
            }
            is SkillCommand.Tale -> { lastSkillAt = time.now(); tale(Tales.Request(null, false), ai) }
            else -> null
        }
    }

    // ------------------------------------------------------------------ Продолжение разговора

    private suspend fun continuation(text: String, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? {
        if (awaitReply) {
            awaitReply = false
            val t = SkillPhrases.norm(text)
            if (LocalCommandParser.isNo(t) || t in setOf("отмена", "отмени", "не надо", "ничего", "не отвечай")) {
                replyTarget = null
                return SkillOutcome.Say("Хорошо, не отвечаю.")
            }
            if (cfg.locked) return SkillOutcome.Say("Разблокируйте телефон — сообщения без разблокировки не отправляю.")
            val body = text.trim().trimEnd('.').removePrefix("что ").trim()
            return replyMessage(SkillCommand.ReplyMessage(null, body, body))
        }
        game?.let { g ->
            val t = SkillPhrases.norm(text)
            // Явный выход и новая игра — не ход в игре.
            Game.parseStart(t)?.takeIf { it::class != g::class }?.let { return startGame(it) }
            val turn = g.play(text, if (g is Game.Cities) weatherService?.let { w -> { name: String -> w.findPlace(name) != null } } else null)
            if (turn.over) game = null
            return SkillOutcome.Say(turn.text, followUp = !turn.over)
        }
        if (awaitGameChoice) {
            awaitGameChoice = false
            val t = SkillPhrases.norm(text)
            val g = Game.parseStart(t) ?: when {
                t.contains("город") -> Game.Cities()
                t.contains("числ") -> Game.GuessNumber()
                t.contains("загад") -> Game.Riddles()
                t.contains("виктор") || t.contains("вопрос") -> Game.Quiz()
                t.contains("слов") -> Game.GuessWord()
                LocalCommandParser.isYes(t) || t.contains("люб") || t.contains("сама") || t.contains("выбери") -> randomGame()
                else -> null
            }
            if (g != null) return startGame(g)
        }
        awaitCity?.let { q ->
            awaitCity = null
            val t = SkillPhrases.norm(text)
            if (t.split(' ').size <= 4 && LocalCommandParser.isNo(t).not()) {
                val city = text.trim().trimEnd('.', '!', '?').removePrefix("в ").removePrefix("В ").removePrefix("во ").removePrefix("Во ")
                return weather(q.copy(place = city), cfg)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Выполнение

    private fun private(cmd: SkillCommand) = cmd is SkillCommand.ReadMessages || cmd is SkillCommand.ReplyMessage || cmd is SkillCommand.Screen ||
        cmd is SkillCommand.ContactNumber || cmd is SkillCommand.Calendar || cmd is SkillCommand.ListPlaces || cmd is SkillCommand.SavePlace

    /** Что можно на экране блокировки: см. [LockPolicy.allowsSkill]. */
    private fun access(cmd: SkillCommand): SkillAccess = when (cmd) {
        is SkillCommand.Weather -> if (cmd.query.place == "дом") SkillAccess.VIEW else SkillAccess.PUBLIC
        is SkillCommand.Rates, is SkillCommand.News, is SkillCommand.Fact, is SkillCommand.StartGame, is SkillCommand.Tale,
        is SkillCommand.Almanac, is SkillCommand.FactOfDay -> SkillAccess.PUBLIC
        // Готовит сообщение человеку — как «напиши Маше».
        is SkillCommand.Greet -> SkillAccess.PRIVATE
        // «Подробнее» открывает браузер — как открытие сайта.
        is SkillCommand.NewsDetails -> SkillAccess.PRIVATE
        is SkillCommand.SetName, is SkillCommand.SetCity, is SkillCommand.PlaceRemind -> SkillAccess.CREATE
        is SkillCommand.RadioPlay, SkillCommand.RadioStop, SkillCommand.RadioNext, SkillCommand.FindPhone,
        SkillCommand.TimersLeft, is SkillCommand.TimersCancel -> SkillAccess.DEVICE
        SkillCommand.AskName -> SkillAccess.VIEW
        is SkillCommand.ReadMessages, is SkillCommand.ReplyMessage, is SkillCommand.Screen, is SkillCommand.ContactNumber,
        is SkillCommand.Calendar, SkillCommand.ListPlaces, is SkillCommand.SavePlace -> SkillAccess.PRIVATE
    }

    private fun privateWhat(cmd: SkillCommand) = when (cmd) {
        is SkillCommand.ReadMessages, is SkillCommand.ReplyMessage -> "сообщения"
        is SkillCommand.Screen -> "содержимое экрана"
        is SkillCommand.ContactNumber -> "контакты"
        is SkillCommand.Calendar -> "календарь"
        else -> "места"
    }

    private suspend fun execute(cmd: SkillCommand, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? {
        return when (cmd) {
        is SkillCommand.Weather -> weather(cmd.query, cfg)
        is SkillCommand.Rates -> SkillOutcome.Say((ratesService ?: return offline("курс")).answer(cmd.query))
        is SkillCommand.News -> {
            val feed = (newsService ?: return offline("новости")).feed(cmd.topic)
            lastNews = feed.items
            newsShown = minOf(5, feed.items.size)
            SkillOutcome.Say(NewsService.answer(feed.topic, feed.items.take(5), asked = cmd.topic))
        }
        is SkillCommand.NewsDetails -> {
            val item = lastNews.getOrNull(cmd.index)
            val link = item?.link
            if (link == null) null else SkillOutcome.Run(AssistantPlan("", listOf(AssistantAction.Device(DeviceCommand.OpenUrl(link))), preface = "Открываю: ${item.title}"))
        }
        is SkillCommand.Fact -> fact(cmd.query, cfg)
        is SkillCommand.ReadMessages -> readMessages(cmd.from)
        is SkillCommand.ReplyMessage -> replyMessage(cmd)
        is SkillCommand.Screen -> screen(cmd.mode, cfg, ai)
        is SkillCommand.ContactNumber -> contact(cmd.name)
        is SkillCommand.Calendar -> calendar(cmd)
        is SkillCommand.RadioPlay -> radioPlay(cmd.query)
        SkillCommand.RadioStop -> SkillOutcome.Say(if (host.stopRadio()) "Выключила радио." else "Радио и так не играет.")
        SkillCommand.RadioNext -> radioNext()
        SkillCommand.FindPhone -> SkillOutcome.Say(if (host.ringPhone()) "Я здесь! Звоню погромче — коснитесь уведомления, чтобы остановить." else "Я здесь!")
        SkillCommand.TimersLeft -> timersLeft()
        is SkillCommand.TimersCancel -> {
            // «Отмени таймер на 5 минут» — ищем таймер по длительности, а не по названию.
            val seconds = cmd.label?.let { DevicePhrases.duration(it) }
            val n = if (seconds != null) {
                val active = host.timers().filter { it.endsAt.isAfter(time.now()) }
                val byLength = active.filter { it.seconds == seconds }.ifEmpty { active.takeIf { it.size == 1 && it.single().seconds == 0 }.orEmpty() }
                byLength.count { host.cancelTimer(it.id) }
            } else host.cancelTimers(cmd.label)
            SkillOutcome.Say(if (n > 0) "Отменила ${if (n == 1) "таймер" else RuFormat.count(n, "таймер", "таймера", "таймеров")}." else "Активных таймеров нет.")
        }
        is SkillCommand.SavePlace -> {
            val p = host.savePlace(cmd.name) ?: return SkillOutcome.Say("Не удалось определить, где вы. Включите геолокацию и повторите.", permission = Permission.LOCATION)
            SkillOutcome.Say("Запомнила: здесь — ${placeTitle(p.name)}. Теперь можно говорить «напомни, когда буду ${placeWhen(p.name)}…».")
        }
        is SkillCommand.PlaceRemind -> placeRemind(cmd)
        SkillCommand.ListPlaces -> listPlaces()
        is SkillCommand.SetName -> {
            host.setUserName(cmd.name)
            SkillOutcome.Say("Приятно познакомиться, ${cmd.name}! Буду так вас называть.")
        }
        SkillCommand.AskName -> SkillOutcome.Say(cfg.userName?.let { "Вас зовут $it." } ?: "Вы ещё не сказали, как вас зовут. Скажите: «называй меня …».")
        is SkillCommand.SetCity -> {
            val resolved = orNull { weatherService?.findPlace(cmd.city)?.name } ?: cmd.city
            host.setCity(resolved)
            SkillOutcome.Say("Запомнила: ваш город — $resolved. Буду говорить погоду для него, если геолокация выключена.")
        }
        is SkillCommand.StartGame -> cmd.game?.let { startGame(it) } ?: askGame()
        is SkillCommand.Tale -> tale(cmd.request, ai)
        is SkillCommand.Almanac -> SkillOutcome.Say(almanac(cmd))
        is SkillCommand.Greet -> greet(cmd, ai)
        is SkillCommand.FactOfDay -> SkillOutcome.Say(
            if (cmd.ofDay) "Факт дня: " + Trivia.FACTS[(time.today().toEpochDay() % Trivia.FACTS.size).toInt()]
            else Trivia.FACTS.filter { it != lastFact }.random().also { lastFact = it },
        )
        }
    }

    /** Как runCatching, но отмену корутины не глотает. */
    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun offline(what: String) = SkillOutcome.Say("Чтобы узнать $what, нужен интернет.")

    private fun askGame(): SkillOutcome {
        awaitGameChoice = true
        return SkillOutcome.Say("Давайте! Могу сыграть в «Города», «Угадай число», викторину, «Угадай слово» или загадать загадку. Во что играем?", followUp = true)
    }

    private fun startGame(g: Game): SkillOutcome {
        game = g
        awaitGameChoice = false
        return SkillOutcome.Say(g.start(), followUp = true)
    }

    // ------------------------------------------------------------------ Погода

    private suspend fun weather(q: WeatherQuery, cfg: AssistantSettings): SkillOutcome {
        val w = weatherService ?: return offline("погоду")
        val today = time.today()
        var name: String? = null
        val point: GeoPoint? = when {
            q.place == "дом" -> host.places().firstOrNull { it.name == "дом" }?.let { GeoPoint(it.lat, it.lon, "дома") }
                ?: return SkillOutcome.Say("Я пока не знаю, где ваш дом. Скажите дома: «запомни, я дома».")
            q.place != null -> {
                val p = w.findPlace(q.place) ?: return SkillOutcome.Say("Не нашла город «${q.place}». Скажите иначе, например «погода в Казани».")
                name = p.name
                GeoPoint(p.lat, p.lon, p.name)
            }
            else -> orNull { host.location() }
                ?: cfg.city?.let { c -> w.findPlace(c)?.let { p -> name = p.name; GeoPoint(p.lat, p.lon, p.name) } }
        }
        if (point == null) {
            awaitCity = q
            return SkillOutcome.Say("В каком городе? Можно сказать один раз «я живу в …» — запомню.", followUp = true)
        }
        if (q.place == "дом") name = "дома"
        val f = w.forecast(point.lat, point.lon)
        return SkillOutcome.Say(WeatherService.answer(q, name, f, today))
    }

    /** Строка погоды для утренней сводки. */
    suspend fun weatherLine(cfg: AssistantSettings): String? = orNull {
        val w = weatherService ?: return null
        var name: String? = null
        val point = host.location() ?: cfg.city?.let { c -> w.findPlace(c)?.let { name = it.name; GeoPoint(it.lat, it.lon) } } ?: return null
        val f = w.forecast(point.lat, point.lon)
        val d = f.days.firstOrNull { it.date == time.today() } ?: return null
        buildString {
            append("Погода${name?.let { " ($it)" } ?: ""}: ")
            f.temp?.let { append("сейчас ${WeatherService.deg(it)}, ") }
            append("днём до ${WeatherService.deg(d.max)}, ${WeatherService.describe(d.code)}")
            if (WeatherService.isRain(d.code) || (d.rainChance ?: 0) >= 60) append(" — возьмите зонт")
            append(".")
        }
    }

    suspend fun agendaExtras(date: LocalDate, cfg: AssistantSettings): AgendaExtras {
        val zone = time.zone()
        val events = if (cfg.locked) emptyList() else (orNull {
            host.calendar(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        } ?: emptyList()).map { formatEvent(it, zone, withDate = false) }
        val weather = if (date == time.today()) weatherLine(cfg) else null
        val md = java.time.MonthDay.from(date)
        val birthdays = if (cfg.locked) emptyList() else (orNull { host.contactBirthdays() } ?: emptyList()).filter { it.second == md }.map { it.first }
        return AgendaExtras(weather, events, birthdays)
    }

    // ------------------------------------------------------------------ Справка

    private suspend fun fact(query: String, cfg: AssistantSettings): SkillOutcome? {
        val wiki = wikiService
        val article = if (wiki != null) orNull { wiki.lookup(query) } else null
        if (article != null) return SkillOutcome.Say(article.extract)
        // С AI — пусть ответит он; без AI — откроем поиск.
        if (cfg.useAI) return null
        return SkillOutcome.Run(AssistantPlan("", listOf(AssistantAction.Device(DeviceCommand.WebSearch(query))), preface = "В Википедии не нашла — ищу в интернете."))
    }

    // ------------------------------------------------------------------ Сообщения

    private fun matches(sender: String, who: String): Boolean {
        val s = RuTokenizer.normalize(sender)
        val w = RuTokenizer.normalize(who).trim()
        if (w.isEmpty()) return false
        if (s.contains(w)) return true
        val ws = RuStemmer.stem(w).take(maxOf(3, w.length - 2))
        return s.split(Regex("""[\s,.:]+""")).any { part -> part.isNotEmpty() && (RuStemmer.stem(part).startsWith(ws) || part.startsWith(ws)) }
    }

    private suspend fun readMessages(from: String?): SkillOutcome {
        val all = host.messages()
        val filtered = if (from == null) all else all.filter { matches(it.sender, from) || matches(it.app, from) }
        if (filtered.isEmpty()) return SkillOutcome.Say(if (from == null) "Новых сообщений нет." else "От «$from» новых сообщений нет.")
        val list = filtered.take(6)
        val bySender = list.groupBy { it.sender }
        val lines = bySender.entries.map { (sender, msgs) ->
            "$sender (${msgs.first().app}): " + msgs.take(3).reversed().joinToString(" · ") { it.text.take(200) }
        }
        replyTarget = list.first().takeIf { it.canReply }
        val ask = replyTarget?.let { "\nЧтобы ответить ${it.sender}, скажите «ответь: …»." } ?: ""
        val head = if (filtered.size == 1) "Сообщение" else "Сообщений: ${filtered.size}"
        return SkillOutcome.Say("$head.\n" + lines.joinToString("\n") + ask, followUp = replyTarget != null)
    }

    private suspend fun replyMessage(cmd: SkillCommand.ReplyMessage): SkillOutcome {
        val all = host.messages()
        val pronouns = setOf("ему", "ей", "им", "ней", "нему", "им всем")
        var target: IncomingMessage? = null
        var text = cmd.text
        val to = cmd.to?.let { RuTokenizer.normalize(it).trim(' ', ',', ':', '.', '!') }?.takeIf { it.isNotEmpty() && it !in pronouns }
        if (to != null) {
            target = all.firstOrNull { it.canReply && matches(it.sender, to) }
            if (target == null) {
                // «ответь маме: скоро буду» — адресат назван явно, но его сообщения нет: не отправляем кому попало.
                if (cmd.explicitTo || all.any { matches(it.sender, to) }) {
                    return SkillOutcome.Say("Не нашла сообщения от «${cmd.to.trim(' ', ',', ':')}», на которое можно ответить. Скажите «прочитай сообщения».")
                }
                // «ответь буду через 10 минут» — первое слово не имя: весь текст — ответ.
                text = cmd.raw
            }
        }
        target = target ?: replyTarget?.let { rt -> all.firstOrNull { it.key == rt.key } ?: rt } ?: all.firstOrNull { it.canReply }
        if (target == null) return SkillOutcome.Say("Не нашла сообщение, на которое можно ответить. Скажите «прочитай сообщения».")
        if (text.isBlank()) {
            replyTarget = target
            awaitReply = true
            return SkillOutcome.Say("Что ответить ${target.sender}?", followUp = true)
        }
        val ok = host.reply(target, text.replaceFirstChar { it.uppercase() })
        replyTarget = null
        return SkillOutcome.Say(if (ok) "Ответила ${target.sender} в ${target.app}: «${text.replaceFirstChar { it.uppercase() }}»." else "Не получилось ответить в ${target.app} — это приложение не даёт отвечать из уведомления.")
    }

    // ------------------------------------------------------------------ Экран

    private suspend fun screen(mode: ScreenMode, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome {
        val text = host.screenText()?.trim().orEmpty()
        if (text.length < 3) return SkillOutcome.Say("На экране нет текста, который я могу прочитать.")
        val clipped = text.take(6000)
        when (mode) {
            ScreenMode.READ -> return SkillOutcome.Say(clipped.take(900))
            ScreenMode.SUMMARY -> {
                val out = ai?.invoke("Кратко перескажи по-русски текст с экрана телефона: 2–4 предложения, без вступлений.", clipped, 400)
                return SkillOutcome.Say(out?.takeIf { it.isNotBlank() } ?: ("Без AI могу только прочитать начало: " + clipped.take(500)))
            }
            ScreenMode.TRANSLATE -> {
                val out = ai?.invoke("Переведи текст с экрана телефона на русский язык. Ответь только переводом.", clipped, 900)
                return SkillOutcome.Say(out?.takeIf { it.isNotBlank() } ?: "Чтобы переводить экран, подключите облачный AI в настройках.")
            }
        }
    }

    // ------------------------------------------------------------------ Контакты

    private suspend fun contact(name: String): SkillOutcome {
        val c = host.contact(name) ?: return SkillOutcome.Say("Не нашла «$name» в контактах.")
        if (c.phones.isEmpty()) return SkillOutcome.Say("У контакта ${c.name} нет номера.")
        val list = c.phones.distinct().take(3).joinToString(", ") { spacedPhone(it) }
        return SkillOutcome.Say("${c.name}: $list.")
    }

    // ------------------------------------------------------------------ Календарь

    private suspend fun calendar(cmd: SkillCommand.Calendar): SkillOutcome {
        val zone = time.zone()
        val from = if (cmd.from == time.today()) time.now() else cmd.from.atStartOfDay(zone).toInstant()
        val items = host.calendar(if (cmd.nextOnly) time.now() else cmd.from.atStartOfDay(zone).toInstant(), cmd.to.plusDays(1).atStartOfDay(zone).toInstant())
            .sortedBy { it.start }
        if (cmd.nextOnly) {
            val next = items.firstOrNull { !it.start.isBefore(from) || it.allDay } ?: return SkillOutcome.Say("В календаре на ближайший месяц ничего нет.")
            return SkillOutcome.Say("Ближайшее: ${formatEvent(next, zone, withDate = true)}.")
        }
        val today = time.today()
        val when_ = if (cmd.from == cmd.to) RuFormat.date(cmd.from, today) else "неделю"
        if (items.isEmpty()) return SkillOutcome.Say("В календаре на $when_ ничего нет.")
        return SkillOutcome.Say("Календарь на $when_:\n" + items.take(10).joinToString("\n") { "• " + formatEvent(it, zone, withDate = cmd.from != cmd.to) })
    }

    private fun formatEvent(e: CalendarItem, zone: ZoneId, withDate: Boolean): String {
        val z = e.start.atZone(zone)
        val day = if (withDate) RuFormat.date(z.toLocalDate(), time.today()) + (if (e.allDay) "" else " ") else ""
        val t = if (e.allDay) (if (withDate) "" else "весь день") else "в ${RuFormat.time(z.toLocalTime())}"
        val where = e.location?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "${e.title}${where} — ${(day + t).trim()}"
    }

    // ------------------------------------------------------------------ Радио

    private suspend fun radioPlay(query: String?): SkillOutcome {
        val found = runCatching { radioCatalog.find(query) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        if (found.isEmpty()) return SkillOutcome.Say("Не нашла такую радиостанцию. Попробуйте, например, «включи радио Европа Плюс».")
        stations = found
        stationIndex = if (query == null) found.indices.random() else 0
        return playStation()
    }

    private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val FOLLOW_UP = Regex("""^(?:а|и|ну а|а если|а как)\s+(?:насчет\s+|на счет\s+|что\s+)?""")
    private val MORE = Regex("""^(?:а\s+)?(?:давай\s+)?(?:ещ[её]|еще|дальше|следующие|другую|другие)(?:\s+(?:одну|один|новости|сказку|разок|пожалуйста|давай))?$""")
    private val RADIO_NEXT = Regex("""^(?:дальше|следующ\S*|переключи|другую|другая|давай другую|не то)(?:\s+(?:станци\S*|волн\S*|радио))?$""")

    private suspend fun radioNext(): SkillOutcome {
        if (stations.isEmpty()) stations = radioCatalog.find(null)
        stationIndex = (stationIndex + 1) % stations.size
        return playStation()
    }

    private suspend fun playStation(): SkillOutcome {
        repeat(minOf(3, stations.size)) {
            val s = stations[stationIndex]
            if (host.playRadio(s)) return SkillOutcome.Say("Включаю ${s.name}. Скажите «выключи радио» или «следующая станция».")
            stationIndex = (stationIndex + 1) % stations.size
        }
        return SkillOutcome.Say("Радио сейчас не отвечает. Проверьте интернет и попробуйте ещё раз.")
    }

    // ------------------------------------------------------------------ Поздравления

    private suspend fun greet(cmd: SkillCommand.Greet, ai: SkillAi?): SkillOutcome {
        val occasion = cmd.occasion
        val fromAi = ai?.let { a ->
            orNull { a("Напиши короткое тёплое поздравление на русском (2–3 предложения, без хэштегов и кавычек) — ${occasion.ifBlank { "просто хорошего дня" }}. Обращение на «ты».", "Кому: ${cmd.person}", 200) }
        }?.trim()?.trim('"', '«', '»')?.takeIf { it.length in 10..600 }
        val text = fromAi ?: Greetings.pick(occasion)
        val plan = AssistantPlan("", listOf(AssistantAction.Device(DeviceCommand.Message(cmd.person, text))), preface = "Поздравление: «$text»")
        return SkillOutcome.Run(plan)
    }

    // ------------------------------------------------------------------ Календарь: праздники, именины, приметы

    private fun almanac(cmd: SkillCommand.Almanac): String {
        val today = time.today()
        val d = cmd.date
        val whenWord = when (d) { today -> "Сегодня"; today.plusDays(1) -> "Завтра"; today.plusDays(2) -> "Послезавтра"; else -> ai.loli.core.assistant.RuFormat.date(d, today).replaceFirstChar { it.uppercase() } }
        return when (cmd.kind) {
            AlmanacKind.HOLIDAY -> {
                val h = Almanac.holidays(d)
                if (h.isNotEmpty()) "$whenWord: ${h.joinToString(", ")}."
                else Almanac.nextHoliday(d).let { (nd, names) -> "$whenWord праздника в моём календаре нет. Ближайший — ${names.first()}, ${ai.loli.core.assistant.RuFormat.date(nd, today)}." }
            }
            AlmanacKind.NEXT_HOLIDAY -> Almanac.nextHoliday(today).let { (nd, names) ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, nd)
                "Ближайший праздник — ${names.joinToString(", ")}, ${ai.loli.core.assistant.RuFormat.date(nd, today)}${if (days > 1) " (через ${ai.loli.core.assistant.RuFormat.count(days.toInt(), "день", "дня", "дней")})" else ""}."
            }
            AlmanacKind.WHEN_HOLIDAY -> {
                val name = cmd.name.orEmpty()
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
                "${name.replaceFirstChar { it.uppercase() }} — ${ai.loli.core.assistant.RuFormat.date(d, today)}" +
                    (if (days > 1) ", через ${ai.loli.core.assistant.RuFormat.count(days.toInt(), "день", "дня", "дней")}." else ".")
            }
            AlmanacKind.NAME_DAY -> {
                val names = Almanac.NAME_DAYS[java.time.MonthDay.from(d)]
                if (names != null) "$whenWord именины: ${names.joinToString(", ")}."
                else {
                    val next = generateSequence(d.plusDays(1)) { it.plusDays(1) }.take(366).first { Almanac.NAME_DAYS.containsKey(java.time.MonthDay.from(it)) }
                    "$whenWord главных именин в моём календаре нет. Ближайшие — ${ai.loli.core.assistant.RuFormat.date(next, today)}: ${Almanac.NAME_DAYS.getValue(java.time.MonthDay.from(next)).joinToString(", ")}."
                }
            }
            AlmanacKind.NAME_DAY_OF -> {
                val name = cmd.name.orEmpty().replaceFirstChar { it.uppercase() }
                val dates = Almanac.nameDays(name)
                if (dates.isEmpty()) "Не знаю, когда именины у имени «$name». В моём календаре только главные даты."
                else "Именины ${name}: " + dates.joinToString(", ") { md -> "${md.dayOfMonth} ${MONTHS_GEN[md.monthValue - 1]}" } + "."
            }
            AlmanacKind.OMEN -> "Примета: ${Almanac.omen(d)}"
        }
    }

    // ------------------------------------------------------------------ Таймеры

    private fun timersLeft(): SkillOutcome {
        val now = time.now()
        val list = host.timers().filter { it.endsAt.isAfter(now) }.sortedBy { it.endsAt }
        if (list.isEmpty()) return SkillOutcome.Say("Активных таймеров нет.")
        return SkillOutcome.Say(list.joinToString("\n") { t ->
            val left = Duration.between(now, t.endsAt)
            val label = t.label.takeIf { it.isNotBlank() }?.let { "«$it»: " } ?: if (list.size > 1) "" else "Осталось "
            "$label${durationText(left)}"
        })
    }

    // ------------------------------------------------------------------ Места

    private suspend fun placeRemind(cmd: SkillCommand.PlaceRemind): SkillOutcome {
        val known = host.places()
        val place = known.firstOrNull { it.name == cmd.place } ?: known.firstOrNull { RuStemmer.stem(it.name).take(4) == RuStemmer.stem(cmd.place).take(4) }
        if (place == null) {
            return SkillOutcome.Say("Я пока не знаю, где ${placeTitle(cmd.place)}. Когда будете там, скажите: «запомни, здесь ${placeSave(cmd.place)}» — и тогда поставлю напоминание.")
        }
        host.addPlaceReminder(cmd.text, place.name, cmd.onLeave) ?: return SkillOutcome.Say("Не получилось поставить напоминание по месту.")
        val whenText = if (cmd.onLeave) "когда уйдёте ${placeFrom(place.name)}" else "когда будете ${placeWhen(place.name)}"
        return SkillOutcome.Say("Напомню «${cmd.text}», $whenText.")
    }

    private fun listPlaces(): SkillOutcome {
        val places = host.places()
        val reminders = host.placeReminders()
        if (places.isEmpty()) return SkillOutcome.Say("Я пока не знаю ваших мест. Скажите дома: «запомни, я дома».")
        val sb = StringBuilder("Места: " + places.joinToString(", ") { it.name } + ".")
        if (reminders.isNotEmpty()) sb.append("\nНапоминания по месту:\n" + reminders.joinToString("\n") { "• ${it.text} — ${if (it.onLeave) "уходя ${placeFrom(it.place)}" else placeWhen(it.place)}" })
        return SkillOutcome.Say(sb.toString())
    }

    // ------------------------------------------------------------------ Сказки

    private suspend fun tale(r: Tales.Request, ai: SkillAi?): SkillOutcome {
        if (r.invent || (r.about != null && Tales.find(r.about) == null)) {
            val about = r.about ?: "доброго котёнка"
            val out = ai?.invoke(
                "Ты добрая рассказчица сказок для детей. Сочини короткую добрую сказку на русском (150–250 слов) без страшных сцен, с хорошим концом. Только текст сказки, начни с названия в кавычках.",
                "Сказка про $about.", 900,
            )
            if (!out.isNullOrBlank()) return SkillOutcome.Say(out.trim())
            if (r.invent) {
                val t = Tales.random(lastTale)
                lastTale = t.title
                return SkillOutcome.Say("Придумывать сказки я умею с облачным AI. А пока — «${t.title}». ${t.text}")
            }
        }
        val t = Tales.find(r.about) ?: Tales.random(lastTale)
        lastTale = t.title
        return SkillOutcome.Say("«${t.title}». ${t.text}")
    }

    companion object {
        private const val TAG = "Skills"
        private val PENDING_TTL: Duration = Duration.ofMinutes(3)

        fun permissionText(p: Permission, name: String): String = when (p) {
            Permission.LOCATION -> "Чтобы знать, где вы, разрешите $name доступ к геолокации."
            Permission.BACKGROUND_LOCATION -> "Для напоминаний по месту разрешите геолокацию «Всегда» — иначе $name не узнает, что вы пришли."
            Permission.NOTIFICATIONS_ACCESS -> "Чтобы читать и отвечать на сообщения, разрешите $name доступ к уведомлениям — открываю настройки."
            Permission.CONTACTS -> "Разрешите $name доступ к контактам."
            Permission.CALENDAR -> "Разрешите $name доступ к календарю."
            Permission.ACCESSIBILITY -> "Чтобы видеть текст на экране, включите $name в «Спецвозможностях» — открываю настройки."
        }

        fun placeTitle(p: String) = when (p) { "дом" -> "ваш дом"; "работа" -> "ваша работа"; else -> p }
        fun placeWhen(p: String) = when (p) { "дом" -> "дома"; "работа" -> "на работе"; "дача" -> "на даче"; "спортзал" -> "в спортзале"; "школа" -> "в школе"; "магазин" -> "в магазине"; else -> "в «$p»" }
        fun placeFrom(p: String) = when (p) { "дом" -> "из дома"; "работа" -> "с работы"; "дача" -> "с дачи"; "школа" -> "из школы"; else -> "из «$p»" }
        fun placeSave(p: String) = when (p) { "дом" -> "мой дом"; "работа" -> "моя работа"; "дача" -> "моя дача"; else -> "мой $p" }

        fun durationText(d: Duration): String {
            val s = d.seconds.coerceAtLeast(0)
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            val parts = ArrayList<String>()
            if (h > 0) parts += "$h ${RuFormat.plural(h, "час", "часа", "часов")}"
            if (m > 0) parts += "$m ${RuFormat.plural(m, "минута", "минуты", "минут")}"
            if (h == 0L && (sec > 0 || parts.isEmpty())) parts += "$sec ${RuFormat.plural(sec, "секунда", "секунды", "секунд")}"
            return parts.joinToString(" ")
        }

        /** «+79161234567» → «+7 916 123-45-67» — удобно и прочитать, и продиктовать. */
        fun spacedPhone(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.length == 11 && (digits[0] == '7' || digits[0] == '8') -> "${if (raw.trim().startsWith("+")) "+" else ""}${digits[0]} ${digits.substring(1, 4)} ${digits.substring(4, 7)}-${digits.substring(7, 9)}-${digits.substring(9)}"
                else -> raw.trim()
            }
        }
    }
}
