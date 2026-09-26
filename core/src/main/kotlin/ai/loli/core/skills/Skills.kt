package ai.loli.core.skills

import ai.loli.core.assistant.AssistantAction
import ai.loli.core.assistant.AssistantPlan
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.LocalCommandParser
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
    ) : SkillOutcome

    /** Выполнить обычные действия (открыть ссылку, поиск в интернете). */
    data class Run(val plan: AssistantPlan) : SkillOutcome
}

/** Дополнения к плану на день: погода и события календаря. */
data class AgendaExtras(val weather: String? = null, val events: List<String> = emptyList())

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
    private var replyTarget: IncomingMessage? = null
    private var stations: List<RadioStation> = emptyList()
    private var stationIndex = 0
    private var lastTale: String? = null

    /** Навык ждёт ответа — движок передаёт следующую фразу сюда. */
    val busy: Boolean get() = game != null || awaitGameChoice || awaitCity != null

    fun reset() {
        game = null; awaitGameChoice = false; awaitCity = null; replyTarget = null
    }

    /** Понимает ли навык фразу (для выбора варианта распознавания). */
    fun recognizes(text: String, name: String): Boolean = SkillPhrases.parse(text, time.today(), name) != null

    suspend fun handle(text: String, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? {
        try {
            continuation(text, cfg, ai)?.let { return it }
            val cmd = SkillPhrases.parse(text, time.today(), cfg.assistantName) ?: return null
            if (cfg.locked && private(cmd)) {
                return SkillOutcome.Say("Разблокируйте телефон — ${privateWhat(cmd)} без разблокировки не показываю.")
            }
            return execute(cmd, cfg, ai)
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

    // ------------------------------------------------------------------ Продолжение разговора

    private suspend fun continuation(text: String, cfg: AssistantSettings, ai: SkillAi?): SkillOutcome? {
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
            val items = (newsService ?: return offline("новости")).headlines(cmd.topic)
            lastNews = items
            SkillOutcome.Say(NewsService.answer(cmd.topic, items) + if (items.any { it.link != null }) "\nСкажите «подробнее» — открою первую новость." else "")
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
            val n = host.cancelTimers(cmd.label)
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
            val resolved = runCatching { weatherService?.findPlace(cmd.city)?.name }.getOrNull() ?: cmd.city
            host.setCity(resolved)
            SkillOutcome.Say("Запомнила: ваш город — $resolved. Буду говорить погоду для него, если геолокация выключена.")
        }
        is SkillCommand.StartGame -> cmd.game?.let { startGame(it) } ?: askGame()
        is SkillCommand.Tale -> tale(cmd.request, ai)
        }
    }

    private fun offline(what: String) = SkillOutcome.Say("Чтобы узнать $what, нужен интернет.")

    private fun askGame(): SkillOutcome {
        awaitGameChoice = true
        return SkillOutcome.Say("Давайте! Могу сыграть в «Города», «Угадай число» или загадать загадку. Во что играем?", followUp = true)
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
            else -> runCatching { host.location() }.getOrNull()
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
    suspend fun weatherLine(cfg: AssistantSettings): String? = runCatching {
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
    }.getOrNull()

    suspend fun agendaExtras(date: LocalDate, cfg: AssistantSettings): AgendaExtras {
        val zone = time.zone()
        val events = if (cfg.locked) emptyList() else runCatching {
            host.calendar(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        }.getOrDefault(emptyList()).map { formatEvent(it, zone, withDate = false) }
        val weather = if (date == time.today()) weatherLine(cfg) else null
        return AgendaExtras(weather, events)
    }

    // ------------------------------------------------------------------ Справка

    private suspend fun fact(query: String, cfg: AssistantSettings): SkillOutcome? {
        val wiki = wikiService
        val article = if (wiki != null) runCatching { wiki.lookup(query) }.getOrNull() else null
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
        val pronouns = setOf("ему", "ей", "им", "ему:", "ей:")
        var target: IncomingMessage? = null
        var text = cmd.text
        if (cmd.to != null && RuTokenizer.normalize(cmd.to) !in pronouns) {
            target = all.firstOrNull { it.canReply && matches(it.sender, cmd.to) }
            // «ответь буду через 10 минут» — первое слово не имя: весь текст — ответ.
            if (target == null) text = cmd.raw
        }
        target = target ?: replyTarget?.let { rt -> all.firstOrNull { it.key == rt.key } ?: rt } ?: all.firstOrNull { it.canReply }
        if (target == null) return SkillOutcome.Say("Не нашла сообщение, на которое можно ответить. Скажите «прочитай сообщения».")
        if (text.isBlank()) {
            replyTarget = target
            return SkillOutcome.Say("Что ответить ${target.sender}?", followUp = true)
        }
        val ok = host.reply(target, text.replaceFirstChar { it.uppercase() })
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
