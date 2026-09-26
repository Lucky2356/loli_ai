package ai.loli.core.assistant

import ai.loli.core.nlp.RuNumbers
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.Rx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import ai.loli.core.nlp.RuDateTimeParser
import kotlin.random.Random

/** Команды самому телефону. Выполняются платформой ([DeviceController]) без интернета. */
sealed interface DeviceCommand {
    data class Timer(val seconds: Int, val label: String = "") : DeviceCommand
    data class Alarm(val time: LocalTime, val label: String = "", val days: Set<DayOfWeek> = emptySet()) : DeviceCommand
    data class OpenApp(val name: String) : DeviceCommand
    data class Call(val who: String) : DeviceCommand
    data class Message(val who: String, val text: String) : DeviceCommand
    data class Flashlight(val on: Boolean) : DeviceCommand
    data object Battery : DeviceCommand
    data class Media(val action: MediaAction) : DeviceCommand
    data class Volume(val change: VolumeChange, val percent: Int? = null) : DeviceCommand
    data class WebSearch(val query: String) : DeviceCommand
    data class Navigate(val destination: String) : DeviceCommand
    data class OpenSettings(val section: SettingsSection) : DeviceCommand
    data object Stopwatch : DeviceCommand
    data object ShowAlarms : DeviceCommand
    data class Camera(val video: Boolean = false, val selfie: Boolean = false) : DeviceCommand
    data class OpenUrl(val url: String) : DeviceCommand
    /** Музыка/видео по запросу: «включи Queen», «найди на ютубе рецепт пиццы». */
    data class Play(val query: String, val youtube: Boolean = false) : DeviceCommand
    data class CalendarEvent(val title: String, val start: Instant?, val allDay: Boolean = false) : DeviceCommand
    data class AddContact(val name: String, val phone: String?) : DeviceCommand
    data class Share(val app: String, val text: String) : DeviceCommand
    data class DoNotDisturb(val on: Boolean) : DeviceCommand
    data class Brightness(val percent: Int?, val delta: Int = 0) : DeviceCommand
    /** Системные действия через спецвозможности: назад, домой, скриншот, блокировка… */
    data class Global(val action: GlobalAction) : DeviceCommand
}

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT, POWER_MENU, SPLIT_SCREEN }

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }
enum class VolumeChange { UP, DOWN, MUTE, UNMUTE, MAX, SET }
enum class SettingsSection { WIFI, BLUETOOTH, SOUND, DISPLAY, BATTERY, LOCATION, APPS, MAIN, AIRPLANE, NFC, HOTSPOT, MOBILE_DATA, NOTIFICATIONS, SECURITY, ACCESSIBILITY, DATE_TIME, STORAGE }

data class DeviceResult(val text: String, val ok: Boolean = true)

/** Платформенная часть: Android выполняет команды через системные интенты и сервисы. */
interface DeviceController {
    suspend fun perform(command: DeviceCommand): DeviceResult
}

object UnsupportedDevice : DeviceController {
    override suspend fun perform(command: DeviceCommand) = DeviceResult("На этом устройстве я так не умею.", ok = false)
}

/**
 * Разбор команд телефону и «бытовых» вопросов полностью офлайн:
 * таймер, будильник, открыть приложение, позвонить, фонарик, заряд, музыка, громкость,
 * поиск в интернете, маршрут, настройки; сколько дней до даты, день недели, монетка, кубик, случайное число,
 * перевод единиц.
 */
object DevicePhrases {
    private fun re(p: String) = Rx.of(p)

    /** Числительные словами → цифры: «пять минут» → «5 минут», «полчаса» → «30 минут». */
    fun digitize(n: String): String {
        val prepared = n.replace(re("""\bполчаса\b"""), "30 минут").replace(re("""\bполминуты\b"""), "30 секунд")
            .replace(re("""\bполтора часа\b"""), "90 минут").replace(re("""\bчетверть часа\b"""), "15 минут")
        val tokens = RuNumbers.mergeNumberWords(RuTokenizer.tokenize(prepared))
        return tokens.joinToString(" ") { t ->
            t.number?.let { v -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString() } ?: t.norm
        }
    }

    /** Длительность: «5 минут», «1 час 20 минут», «90 секунд», «минуту». */
    fun duration(text: String): Int? {
        val d = digitize(text)
        var total = 0
        var found = false
        re("""(\d+(?:\.\d+)?)\s*(час|часа|часов|ч)\b""").find(d)?.let { total += (it.groupValues[1].toDouble() * 3600).toInt(); found = true }
        re("""(\d+)\s*(минут|минуты|минуту|мин)\b""").find(d)?.let { total += it.groupValues[1].toInt() * 60; found = true }
        re("""(\d+)\s*(секунд|секунды|секунду|сек)\b""").find(d)?.let { total += it.groupValues[1].toInt(); found = true }
        if (!found) {
            when {
                re("""\bминуту\b""").containsMatchIn(d) -> { total = 60; found = true }
                re("""\bчас\b""").containsMatchIn(d) -> { total = 3600; found = true }
            }
        }
        return total.takeIf { found && it > 0 }
    }

    /** «на пасту 8 минут» → «паста»: название таймера — слова, кроме длительности. */
    fun timerLabel(text: String): String {
        val rest = digitize(text)
            .replace(re("""\d+(?:\.\d+)?\s*(?:часов|часа|час|ч|минут|минуты|минуту|мин|секунд|секунды|секунду|сек)\b"""), " ")
            .replace(re("""\b(?:минуту|час|на|для|и|по|через)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (rest.length < 3) return ""
        // «пасту» → «паста», «яйца» — как есть: винительный падеж частых слов.
        return rest.split(' ').take(3).joinToString(" ") { w -> if (w.endsWith("у") && w.length > 3) w.dropLast(1) + "а" else w }
    }

    fun parse(n: String, now: Instant, zone: ZoneId): AssistantAction.Device? {
        fun cmd(c: DeviceCommand) = AssistantAction.Device(c)
        val t = n.trim().trimEnd('.', '!', '?')

        // Таймер: «поставь таймер на 5 минут», «засеки 10 минут», «таймер полчаса».
        re("""^(?:поставь|заведи|запусти|включи|установи)?\s*(?:таймер|засеки|отсчитай)\s*(?:на\s+)?(.+)$""").find(t)?.let { m ->
            duration(m.groupValues[1])?.let { return cmd(DeviceCommand.Timer(it, timerLabel(m.groupValues[1]))) }
        }
        if (re("""^(?:включи|запусти)\s+секундомер""").containsMatchIn(t) || t == "секундомер") return cmd(DeviceCommand.Stopwatch)

        // Будильник: «разбуди меня в 7», «поставь будильник на 6:30», «будильник на 8 утра по будням».
        re("""^(?:разбуди(?:\s+меня)?|поставь будильник|заведи будильник|установи будильник|будильник|подними меня)\s*(?:на|в|к)?\s*(.+)$""").find(t)?.let { m ->
            alarmTime(m.groupValues[1])?.let { (time, days) -> return cmd(DeviceCommand.Alarm(time, "", days)) }
        }
        if (re("""^(?:покажи|какие|мои)\s+будильник|^(?:выключи|отключи|удали|отмени|убери)\s+(?:все\s+)?будильник""").containsMatchIn(t)) return cmd(DeviceCommand.ShowAlarms)

        // Системные действия (через спецвозможности).
        val global = when {
            re("""^(?:назад|вернись назад|шаг назад)$""").containsMatchIn(t) -> GlobalAction.BACK
            re("""^(?:домой|на главный экран|главный экран|сверни всё|сверни все|на рабочий стол)$""").containsMatchIn(t) -> GlobalAction.HOME
            re("""^(?:(?:покажи|открой)\s+)?(?:недавние|последние)\s+приложения$|^переключи приложение$""").containsMatchIn(t) -> GlobalAction.RECENTS
            re("""^(?:открой|покажи|опусти)\s+(?:шторку|уведомления)$""").containsMatchIn(t) -> GlobalAction.NOTIFICATIONS
            re("""^(?:открой|покажи)\s+быстрые настройки$""").containsMatchIn(t) -> GlobalAction.QUICK_SETTINGS
            re("""^(?:заблокируй|выключи)\s+(?:экран|телефон)$|^заблокируй$""").containsMatchIn(t) -> GlobalAction.LOCK
            re("""^(?:сделай|сними)\s+(?:скриншот|снимок экрана)|^скриншот$""").containsMatchIn(t) -> GlobalAction.SCREENSHOT
            re("""^(?:меню|кнопки)\s+питания$|^(?:выключи|перезагрузи)\s+телефон$""").containsMatchIn(t) -> GlobalAction.POWER_MENU
            re("""^(?:раздели экран|разделённый экран|разделенный экран|два окна)$""").containsMatchIn(t) -> GlobalAction.SPLIT_SCREEN
            else -> null
        }
        if (global != null) return cmd(DeviceCommand.Global(global))

        // Камера.
        when {
            re("""^(?:сделай|сними)\s+селфи""").containsMatchIn(t) -> return cmd(DeviceCommand.Camera(selfie = true))
            re("""^(?:сделай|сними)\s+(?:фото|фотографию|снимок)|^(?:открой|включи|запусти)\s+камеру$|^сфотографируй""").containsMatchIn(t) -> return cmd(DeviceCommand.Camera())
            re("""^(?:сними|запиши)\s+видео|^(?:включи|начни)\s+(?:запись видео|видеозапись)""").containsMatchIn(t) -> return cmd(DeviceCommand.Camera(video = true))
        }

        // Не беспокоить, яркость.
        re("""^(?:включи|активируй)\s+(?:режим\s+)?не беспокоить""").find(t)?.let { return cmd(DeviceCommand.DoNotDisturb(true)) }
        re("""^(?:выключи|отключи)\s+(?:режим\s+)?не беспокоить""").find(t)?.let { return cmd(DeviceCommand.DoNotDisturb(false)) }
        re("""^(?:сделай\s+)?яркость\s+(?:на\s+)?(\d+)""").find(digitize(t))?.let { return cmd(DeviceCommand.Brightness(it.groupValues[1].toInt().coerceIn(0, 100))) }
        when {
            re("""^(?:сделай\s+)?(?:экран\s+)?(?:ярче|поярче|прибавь яркость|увеличь яркость)""").containsMatchIn(t) -> return cmd(DeviceCommand.Brightness(null, 20))
            re("""^(?:сделай\s+)?(?:экран\s+)?(?:темнее|потемнее|убавь яркость|уменьши яркость)""").containsMatchIn(t) -> return cmd(DeviceCommand.Brightness(null, -20))
            re("""^(?:максимальная яркость|яркость на максимум)""").containsMatchIn(t) -> return cmd(DeviceCommand.Brightness(100))
        }

        // Сайты: «открой сайт habr.com», «открой youtube.com».
        re("""^(?:открой|зайди на|перейди на)\s+(?:сайт\s+)?([a-z0-9][a-z0-9.-]*\.[a-z]{2,}(?:/\S*)?)$""").find(t)?.let { m ->
            val u = m.groupValues[1]
            return cmd(DeviceCommand.OpenUrl(if (u.startsWith("http")) u else "https://$u"))
        }

        // Музыка и видео по запросу.
        re("""^(?:найди|включи|открой|покажи|поставь)\s+(?:на\s+)?(?:ютубе|youtube)\s+(.+)$|^(?:включи|найди|покажи)\s+(.+?)\s+(?:на\s+)?(?:ютубе|youtube)$""").find(t)?.let { m ->
            val q = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (q.isNotEmpty()) return cmd(DeviceCommand.Play(q, youtube = true))
        }
        re("""^(?:включи|поставь|сыграй|воспроизведи)\s+(?:песню|трек|музыку|альбом|плейлист|группу|исполнителя)\s+(.+)$""").find(t)?.let {
            return cmd(DeviceCommand.Play(it.groupValues[1].trim()))
        }

        // Календарь: «добавь в календарь встречу с Машей завтра в 15:00».
        re("""^(?:добавь|запиши|создай|поставь)\s+(?:в\s+календарь|событие(?:\s+в\s+календарь)?|встречу\s+в\s+календарь)\s+(.+)$""").find(t)?.let { m ->
            val today = now.atZone(zone).toLocalDate()
            val dt = RuDateTimeParser()
            val parsed = dt.parse(m.groupValues[1], today)
            val title = parsed.remainder.ifBlank { m.groupValues[1] }.trim().replaceFirstChar { it.uppercase() }
            val start = if (parsed.spec.isEmpty) null else dt.resolveTrigger(parsed.spec, now, zone)
            return cmd(DeviceCommand.CalendarEvent(title, start, allDay = parsed.spec.time == null && parsed.spec.date != null))
        }

        // Новый контакт: «добавь контакт Саша 8 900 123 45 67».
        re("""^(?:добавь|создай|сохрани)\s+(?:новый\s+)?контакт\s+(.+)$""").find(t)?.let { m ->
            val rest = m.groupValues[1]
            val phone = re("""(\+?[\d][\d\s()-]{5,}\d)""").find(rest)?.value?.filter { it.isDigit() || it == '+' }
            val name = rest.replace(re("""(?:\s+(?:номер|телефон))?\s*\+?[\d][\d\s()-]{5,}\d"""), "").trim().replaceFirstChar { it.uppercase() }
            if (name.isNotEmpty()) return cmd(DeviceCommand.AddContact(name, phone))
        }

        // Поделиться текстом в приложении: «отправь в телеграм привет всем».
        re("""^(?:отправь|перешли|поделись)\s+в\s+(телеграм\w*|ватсап\w*|вотсап\w*|whatsapp|telegram|вк|вконтакте|viber|вайбер|почту|gmail)\s+(.+)$""").find(t)?.let {
            return cmd(DeviceCommand.Share(it.groupValues[1], it.groupValues[2].trim()))
        }

        // Фонарик.
        if (re("""^(?:включи|зажги|вруби)\s+(?:фонарик|фонарь|вспышку|свет на телефоне)""").containsMatchIn(t)) return cmd(DeviceCommand.Flashlight(true))
        if (re("""^(?:выключи|погаси|выруби)\s+(?:фонарик|фонарь|вспышку)""").containsMatchIn(t)) return cmd(DeviceCommand.Flashlight(false))

        // Заряд.
        if (re("""(сколько|какой|какой у меня|проверь)\s+(?:процентов\s+)?(заряд|зарядк|батаре|аккумулятор)|^заряд батареи|^сколько процентов""").containsMatchIn(t)) {
            return cmd(DeviceCommand.Battery)
        }

        // Музыка.
        when {
            re("""^(?:следующ\w*\s+(?:трек|песн\w*|композици\w*)|переключи\s+(?:трек|песню))""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.NEXT))
            re("""^(?:предыдущ\w*\s+(?:трек|песн\w*|композици\w*)|верни\s+(?:трек|песню))""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.PREVIOUS))
            re("""^(?:пауза|поставь на паузу|останови музыку|выключи музыку|стоп музыка)""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.PAUSE))
            re("""^(?:включи музыку|продолжи музыку|играй|воспроизведи|сними с паузы|продолжи воспроизведение)""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.PLAY))
        }

        // Громкость.
        re("""^(?:сделай\s+)?(?:громкость|звук)\s+(?:на\s+)?(\d+)\s*(?:%|процент\w*)?$""").find(digitize(t))?.let {
            return cmd(DeviceCommand.Volume(VolumeChange.SET, it.groupValues[1].toInt().coerceIn(0, 100)))
        }
        when {
            re("""^(?:сделай\s+)?(?:погромче|громче|прибавь\s+(?:звук|громкость)|увеличь\s+(?:звук|громкость))""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.UP))
            re("""^(?:сделай\s+)?(?:потише|тише|убавь\s+(?:звук|громкость)|уменьши\s+(?:звук|громкость))""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.DOWN))
            re("""^(?:выключи звук|без звука|беззвучн\w*\s*режим|включи беззвучн\w*|отключи звук)""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.MUTE))
            re("""^(?:включи звук|верни звук)$""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.UNMUTE))
            re("""^(?:громкость на максимум|максимальн\w* громкость|на полную)""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.MAX))
        }

        // Настройки телефона.
        re("""^(?:открой|включи|выключи|покажи)\s+(?:настройки\s+)?(wi-?fi|вай-?фай|блютуз|bluetooth|звук|экран|яркость|батаре\w*|геолокаци\w*|местоположени\w*|gps|приложения|настройки|режим полета|режим полёта|авиарежим|nfc|нфс|точку доступа|модем|мобильный интернет|мобильные данные|уведомлени\w*|безопасность|спецвозможности|специальные возможности|дату и время|память|хранилище)$""").find(t)?.let { m ->
            val s = m.groupValues[1]
            val section = when {
                s.startsWith("режим пол") || s == "авиарежим" -> SettingsSection.AIRPLANE
                s == "nfc" || s == "нфс" -> SettingsSection.NFC
                s == "точку доступа" || s == "модем" -> SettingsSection.HOTSPOT
                s.startsWith("мобильн") -> SettingsSection.MOBILE_DATA
                s.startsWith("уведомлени") -> SettingsSection.NOTIFICATIONS
                s == "безопасность" -> SettingsSection.SECURITY
                s.contains("возможности") -> SettingsSection.ACCESSIBILITY
                s == "дату и время" -> SettingsSection.DATE_TIME
                s == "память" || s == "хранилище" -> SettingsSection.STORAGE
                s.contains("fi") || s.contains("фай") -> SettingsSection.WIFI
                s.contains("блют") || s.contains("bluetooth") -> SettingsSection.BLUETOOTH
                s == "звук" -> SettingsSection.SOUND
                s == "экран" || s == "яркость" -> SettingsSection.DISPLAY
                s.startsWith("батаре") -> SettingsSection.BATTERY
                s.startsWith("гео") || s.startsWith("мест") || s == "gps" -> SettingsSection.LOCATION
                s == "приложения" -> SettingsSection.APPS
                else -> SettingsSection.MAIN
            }
            // «включи звук» уже разобран выше; здесь — именно экран настроек.
            return cmd(DeviceCommand.OpenSettings(section))
        }

        // Звонок и сообщение: «позвони маме», «набери 8 900 …», «напиши Саше что я задержусь».
        re("""^(?:позвони|набери|вызови|звонок)\s+(?:на\s+номер\s+|номер\s+)?(.+)$""").find(t)?.let { return cmd(DeviceCommand.Call(it.groupValues[1].trim())) }
        re("""^(?:напиши|отправь\s+(?:смс|сообщение))\s+(\S+(?:\s+\S+)?)\s*(?:,|что|:)\s*(.+)$""").find(t)?.let {
            val who = it.groupValues[1].trim()
            // «напиши заметку: …» — это запись, а не сообщение человеку.
            if (!re("""^(?:заметк|иде|задач|список|в\s|себе|мне)""").containsMatchIn(who)) {
                return cmd(DeviceCommand.Message(who, it.groupValues[2].trim()))
            }
        }
        // «напиши Саше привет» — без запятой, как пишет распознавание речи. Кому — слово в дательном падеже.
        re("""^(?:напиши|отправь\s+(?:смс|сообщение))\s+(\S+[еуюиам])\s+(.+)$""").find(t)?.let {
            val who = it.groupValues[1]
            if (who !in NOT_RECIPIENTS && !re("""^(?:заметк|иде|задач|список|списк|себе|мне|письм|текст|сообщени|смс|отзыв|пост|стих|сочинени|код)""").containsMatchIn(who)) {
                return cmd(DeviceCommand.Message(who, it.groupValues[2].trim()))
            }
        }

        // Маршрут.
        re("""^(?:построй\s+маршрут|проложи\s+маршрут|как\s+(?:доехать|добраться|пройти)|маршрут|навигатор|поехали)\s+(?:до|к|в|на)\s+(.+)$""").find(t)?.let {
            return cmd(DeviceCommand.Navigate(it.groupValues[1].trim()))
        }

        // Поиск в интернете: «найди в интернете …», «загугли …».
        re("""^(?:найди|поищи|посмотри)\s+в\s+(?:интернете|гугле|яндексе|сети)\s+(.+)$|^(?:загугли|погугли)\s+(.+)$""").find(t)?.let { m ->
            val q = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (q.isNotEmpty()) return cmd(DeviceCommand.WebSearch(q))
        }

        // Погода, курсы валют, новости, пробки — нужны свежие данные: открываем поиск.
        if (re("""^(?:какая|какой|что с|что по)\s+(?:сегодня\s+|завтра\s+|сейчас\s+)?(?:погод|курс|пробк)|^погода\b|^курс\s+(?:доллар|евро|юан|рубл|биткоин)|^(?:какие|последние)\s+новости|^новости$|^(?:будет ли|пойд[её]т ли)\s+(?:сегодня\s+|завтра\s+)?(?:дождь|снег)""").containsMatchIn(t)) {
            return cmd(DeviceCommand.WebSearch(t))
        }

        // Открыть приложение: «открой телеграм», «запусти камеру». Разделы самого ассистента — не приложения.
        re("""^(?:открой|запусти|включи)\s+(?:приложение\s+)?(.+)$""").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            // «открой телеграм и напиши Саше» — это две команды: имя приложения не может содержать союз.
            if (re("""\s(?:и|а|потом|затем|но)\s|,""").containsMatchIn(name)) return null
            if (name.isNotEmpty() && name !in OWN_SECTIONS && !re("""^(?:заметк|иде|задач|расход|напоминани|список|памят|музык)""").containsMatchIn(name)) {
                return cmd(DeviceCommand.OpenApp(name))
            }
        }
        return null
    }

    private val NOT_RECIPIENTS = setOf("мне", "нам", "все", "всем", "что", "про", "это", "по")

    private val OWN_SECTIONS = setOf("заметки", "идеи", "задачи", "расходы", "напоминания", "историю", "память", "настройки ассистента")

    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** «7», «6:30», «8 утра», «7 вечера», «половину восьмого» → время и дни повтора. */
    fun alarmTime(text: String): Pair<LocalTime, Set<DayOfWeek>>? {
        val d = digitize(text)
        val days = when {
            re("""по будням|в будни|каждый будний""").containsMatchIn(d) -> WEEKDAYS
            re("""каждый день|ежедневно""").containsMatchIn(d) -> DayOfWeek.entries.toSet()
            re("""по выходным""").containsMatchIn(d) -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            else -> emptySet()
        }
        re("""\bполовин\w*\s+(\S+)""").find(d)?.let { m ->
            val next = m.groupValues[1].toIntOrNull() ?: ORDINAL_HOURS.entries.firstOrNull { m.groupValues[1].startsWith(it.key) }?.value ?: return@let
            val h = (next - 1).let { if (it == 0) 12 else it }
            return LocalTime.of(adjust(h, d) % 24, 30) to days
        }
        val m = re("""(\d{1,2})(?:[:. ](\d{2}))?""").find(d) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        return LocalTime.of(adjust(h, d) % 24, min) to days
    }

    /** «половина восьмого» — порядковые числительные часа. */
    private val ORDINAL_HOURS = linkedMapOf(
        "первого" to 1, "второго" to 2, "третьего" to 3, "четвертого" to 4, "пятого" to 5, "шестого" to 6,
        "седьмого" to 7, "восьмого" to 8, "девятого" to 9, "десятого" to 10, "одиннадцатого" to 11, "двенадцатого" to 12,
    )

    private fun adjust(h: Int, d: String): Int = when {
        re("""\b(?:вечера|вечером|дня)\b""").containsMatchIn(d) && h in 1..11 -> h + 12
        re("""\bночи\b""").containsMatchIn(d) && h == 12 -> 0
        else -> h
    }

    // ------------------------------------------------------------------ Бытовые ответы без интернета

    private val holidays = mapOf(
        "новог" to MonthDay.of(1, 1), "нового года" to MonthDay.of(1, 1), "рождеств" to MonthDay.of(1, 7),
        "8 марта" to MonthDay.of(3, 8), "восьмого марта" to MonthDay.of(3, 8), "23 февраля" to MonthDay.of(2, 23),
        "дня победы" to MonthDay.of(5, 9), "9 мая" to MonthDay.of(5, 9), "1 сентября" to MonthDay.of(9, 1),
        "лета" to MonthDay.of(6, 1), "зимы" to MonthDay.of(12, 1), "осени" to MonthDay.of(9, 1), "весны" to MonthDay.of(3, 1),
        "хэллоуина" to MonthDay.of(10, 31), "дня святого валентина" to MonthDay.of(2, 14), "14 февраля" to MonthDay.of(2, 14),
    )

    private val months = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

    /** Ответ на бытовой вопрос или null. [today] — текущая дата пользователя. */
    fun answer(n: String, today: LocalDate, random: Random = Random.Default): String? {
        val t = n.trim().trimEnd('?', '!', '.')
        // Монетка, кубик, случайное число.
        if (re("""(подбрось|брось|кинь)\s+монет|орел или решка|орёл или решка""").containsMatchIn(t)) return if (random.nextBoolean()) "Орёл!" else "Решка!"
        if (re("""(брось|кинь|подбрось)\s+(кубик|кость)""").containsMatchIn(t)) return "Выпало ${random.nextInt(1, 7)}."
        re("""случайное число(?:\s+от\s+(\d+)\s+до\s+(\d+))?""").find(digitize(t))?.let { m ->
            val from = m.groupValues[1].toIntOrNull() ?: 1
            val to = m.groupValues[2].toIntOrNull() ?: 100
            if (to > from) return "Пусть будет ${random.nextInt(from, to + 1)}."
        }
        // Сколько дней до даты/праздника.
        re("""сколько\s+(?:осталось\s+)?(?:дней|день)\s+(?:осталось\s+)?до\s+(.+)$""").find(digitize(t))?.let { m ->
            val target = targetDate(m.groupValues[1], today) ?: return null
            val days = ChronoUnit.DAYS.between(today, target)
            return when (days) {
                0L -> "Это сегодня!"
                1L -> "Завтра!"
                else -> "До ${RuFormat.date(target, today)} — ${plural(days, "день", "дня", "дней")}."
            }
        }
        // День недели для даты: «какой день недели 8 марта».
        re("""какой\s+день\s+недели\s+(?:будет\s+|был\s+)?(.+)$""").find(digitize(t))?.let { m ->
            val target = targetDate(m.groupValues[1], today) ?: return null
            val dow = target.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("ru"))
            return "${RuFormat.date(target, today).replaceFirstChar { it.uppercase() }} — $dow."
        }
        worldTime(t)?.let { return it }
        return convert(digitize(t))
    }

    private val cities = mapOf(
        "москв" to "Europe/Moscow", "питер" to "Europe/Moscow", "петербург" to "Europe/Moscow", "калининград" to "Europe/Kaliningrad",
        "самар" to "Europe/Samara", "екатеринбург" to "Asia/Yekaterinburg", "омск" to "Asia/Omsk", "новосибирск" to "Asia/Novosibirsk",
        "красноярск" to "Asia/Krasnoyarsk", "иркутск" to "Asia/Irkutsk", "якутск" to "Asia/Yakutsk", "владивосток" to "Asia/Vladivostok",
        "магадан" to "Asia/Magadan", "камчатк" to "Asia/Kamchatka", "минск" to "Europe/Minsk", "киев" to "Europe/Kyiv", "астан" to "Asia/Almaty",
        "алмат" to "Asia/Almaty", "ташкент" to "Asia/Tashkent", "тбилиси" to "Asia/Tbilisi", "ереван" to "Asia/Yerevan", "баку" to "Asia/Baku",
        "лондон" to "Europe/London", "париж" to "Europe/Paris", "берлин" to "Europe/Berlin", "рим" to "Europe/Rome", "мадрид" to "Europe/Madrid",
        "стамбул" to "Europe/Istanbul", "дубай" to "Asia/Dubai", "дели" to "Asia/Kolkata", "пекин" to "Asia/Shanghai", "шанха" to "Asia/Shanghai",
        "токио" to "Asia/Tokyo", "сеул" to "Asia/Seoul", "бангкок" to "Asia/Bangkok", "сингапур" to "Asia/Singapore", "сидне" to "Australia/Sydney",
        "нью-йорк" to "America/New_York", "нью йорк" to "America/New_York", "вашингтон" to "America/New_York", "чикаго" to "America/Chicago",
        "лос-анджелес" to "America/Los_Angeles", "лос анджелес" to "America/Los_Angeles", "сан-франциско" to "America/Los_Angeles",
        "торонто" to "America/Toronto", "мехико" to "America/Mexico_City", "рио" to "America/Sao_Paulo", "буэнос" to "America/Argentina/Buenos_Aires",
        "каир" to "Africa/Cairo", "анталь" to "Europe/Istanbul", "пхукет" to "Asia/Bangkok", "бали" to "Asia/Makassar",
    )

    /** «Сколько времени в Токио», «который час в Нью-Йорке». */
    private fun worldTime(t: String): String? {
        val m = re("""(?:сколько\s+(?:сейчас\s+)?времени|который\s+(?:сейчас\s+)?час|какое\s+(?:сейчас\s+)?время|^время)\s+(?:сейчас\s+)?(?:в|во)\s+(.+)$""").find(t) ?: return null
        val place = m.groupValues[1].trim()
        val zone = cities.entries.firstOrNull { place.startsWith(it.key) || place.contains(it.key) }?.value ?: return null
        val time = java.time.ZonedDateTime.now(ZoneId.of(zone))
        val title = place.split(" ").joinToString(" ") { w -> w.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } } }
        return "В $title сейчас ${RuFormat.time(time.toLocalTime())}."
    }

    private fun targetDate(s: String, today: LocalDate): LocalDate? {
        val x = s.trim()
        holidays.entries.firstOrNull { x.contains(it.key) }?.value?.let { md -> return next(md, today) }
        re("""(\d{1,2})\s+([а-я]+)""").find(x)?.let { m ->
            val idx = months.indexOfFirst { m.groupValues[2].startsWith(it) }
            if (idx >= 0) runCatching { return next(MonthDay.of(idx + 1, m.groupValues[1].toInt()), today) }
        }
        re("""(\d{1,2})\.(\d{1,2})(?:\.(\d{4}))?""").find(x)?.let { m ->
            return runCatching {
                val year = m.groupValues[3].toIntOrNull()
                if (year != null) LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
                else next(MonthDay.of(m.groupValues[2].toInt(), m.groupValues[1].toInt()), today)
            }.getOrNull()
        }
        if (x.startsWith("конца год")) return LocalDate.of(today.year, 12, 31)
        if (x.startsWith("конца месяц")) return today.withDayOfMonth(today.lengthOfMonth())
        if (x.startsWith("выходн")) return generateSequence(today) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.SATURDAY }
        return null
    }

    private fun next(md: MonthDay, today: LocalDate): LocalDate {
        val thisYear = md.atYear(today.year)
        return if (thisYear.isBefore(today)) md.atYear(today.year + 1) else thisYear
    }

    private data class Unit(val names: List<String>, val kind: String, val factor: Double, val title: String)

    private val units = listOf(
        Unit(listOf("километр", "км"), "len", 1000.0, "км"), Unit(listOf("метр", "м"), "len", 1.0, "м"),
        Unit(listOf("сантиметр", "см"), "len", 0.01, "см"), Unit(listOf("миллиметр", "мм"), "len", 0.001, "мм"),
        Unit(listOf("мил"), "len", 1609.344, "миль"), Unit(listOf("фут"), "len", 0.3048, "футов"),
        Unit(listOf("дюйм"), "len", 0.0254, "дюймов"), Unit(listOf("ярд"), "len", 0.9144, "ярдов"),
        Unit(listOf("килограмм", "кг", "кило"), "mass", 1.0, "кг"), Unit(listOf("грамм", "г"), "mass", 0.001, "г"),
        Unit(listOf("тонн"), "mass", 1000.0, "т"), Unit(listOf("фунт"), "mass", 0.45359237, "фунтов"),
        Unit(listOf("унци"), "mass", 0.0283495, "унций"),
        Unit(listOf("литр", "л"), "vol", 1.0, "л"), Unit(listOf("миллилитр", "мл"), "vol", 0.001, "мл"),
        Unit(listOf("галлон"), "vol", 3.78541, "галлонов"),
        Unit(listOf("час"), "time", 3600.0, "ч"), Unit(listOf("минут"), "time", 60.0, "мин"), Unit(listOf("секунд"), "time", 1.0, "с"),
        Unit(listOf("сут", "дн", "день", "дней"), "time", 86400.0, "дней"), Unit(listOf("недел"), "time", 604800.0, "недель"),
    )

    private fun unitOf(word: String): Unit? = units.firstOrNull { u -> u.names.any { n -> if (n.length <= 2) word == n else word.startsWith(n) } }

    /** «переведи 5 миль в километры», «сколько 10 фунтов в кг», «100 градусов фаренгейта в цельсии». */
    fun convert(t: String): String? {
        re("""(-?\d+(?:\.\d+)?)\s*градус\w*\s*(фаренгейт\w*|цельси\w*)\s+(?:в|во)\s+(фаренгейт\w*|цельси\w*)""").find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val toC = m.groupValues[3].startsWith("цельс")
            val r = if (toC) (v - 32) * 5 / 9 else v * 9 / 5 + 32
            return "${fmt(v)}° ${if (toC) "F" else "C"} = ${fmt(r)}° ${if (toC) "C" else "F"}."
        }
        val m = re("""(?:переведи|конвертируй|сколько)?\s*(\d+(?:\.\d+)?)\s+([а-я]+)\s+(?:в|во)\s+([а-я]+)""").find(t) ?: return null
        val v = m.groupValues[1].toDouble()
        val from = unitOf(m.groupValues[2]) ?: return null
        val to = unitOf(m.groupValues[3]) ?: return null
        if (from.kind != to.kind || from === to) return null
        val r = v * from.factor / to.factor
        return "${fmt(v)} ${from.title} = ${fmt(r)} ${to.title}."
    }

    private fun fmt(v: Double): String {
        val rounded = if (kotlin.math.abs(v) >= 100) Math.round(v * 10) / 10.0 else Math.round(v * 1000) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString().replace('.', ',')
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }

    /** Человеческое описание длительности: «5 минут», «1 час 30 минут». */
    fun describeDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { plural(it.toLong(), "час", "часа", "часов") },
            m.takeIf { it > 0 }?.let { plural(it.toLong(), "минуту", "минуты", "минут") },
            s.takeIf { it > 0 }?.let { plural(it.toLong(), "секунду", "секунды", "секунд") },
        ).joinToString(" ")
    }
}
