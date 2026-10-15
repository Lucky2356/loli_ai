package ai.loli.desktop

import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DeviceController
import ai.loli.core.assistant.DeviceResult
import ai.loli.core.assistant.GlobalAction
import ai.loli.core.assistant.MediaAction
import ai.loli.core.assistant.RelaxKind
import ai.loli.core.assistant.ReminderScheduler
import ai.loli.core.assistant.RuFormat
import ai.loli.core.assistant.SettingsSection
import ai.loli.core.assistant.TaskReminders
import ai.loli.core.assistant.VolumeChange
import ai.loli.core.data.LocalStore
import ai.loli.core.model.Recurrence
import ai.loli.core.model.Reminder
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.util.Logger
import ai.loli.core.util.TimeSource
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Команды компьютеру: программы и папки, параметры Windows, громкость и музыка, скриншот, блокировка,
 * заряд батареи, яркость, сайты и поиск, будильник и помодоро (напоминаниями), выгрузка таблиц.
 * Звонки, сообщения, фонарик и прочее телефонное здесь честно не поддерживаются.
 */
class DesktopDevice(private val store: LocalStore, private val time: TimeSource) : DeviceController {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val home = File(System.getProperty("user.home"))

    /** На компьютере будильников нет: напоминания проверяет ReminderTicker по базе. */
    private val noScheduler = object : ReminderScheduler {
        override fun schedule(reminder: Reminder) = Unit
        override fun cancel(reminderId: String) = Unit
    }

    private fun browse(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
        Desktop.getDesktop().browse(URI(url)); true
    }.onFailure { Logger.w(TAG, "Не открылось: $url", it) }.getOrDefault(false)

    private fun open(file: File): Boolean = runCatching {
        if (!file.exists() || !Desktop.isDesktopSupported()) return false
        Desktop.getDesktop().open(file); true
    }.onFailure { Logger.w(TAG, "Не открылось: ${file.name}", it) }.getOrDefault(false)

    /** Запуск системной программы по фиксированному имени — пользовательский текст в командную строку не попадает. */
    private fun run(vararg command: String): Boolean = runCatching { ProcessBuilder(*command).start(); true }
        .onFailure { Logger.w(TAG, "Не запустилось: ${command.first()}", it) }.getOrDefault(false)

    /** PowerShell с заранее написанным скриптом (в скрипт подставляются только числа). */
    private fun powershell(script: String): String? = runCatching {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val p = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(15, TimeUnit.SECONDS)
        out
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    override suspend fun perform(command: DeviceCommand): DeviceResult = when (command) {
        is DeviceCommand.OpenUrl -> {
            val url = command.url.let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            if (browse(url)) DeviceResult("Открываю ${command.url}.") else DeviceResult("Не получилось открыть браузер.", ok = false)
        }
        is DeviceCommand.WebSearch ->
            if (browse("https://yandex.ru/search/?text=" + enc(command.query))) DeviceResult("Ищу «${command.query}».") else DeviceResult("Не получилось открыть браузер.", ok = false)
        is DeviceCommand.Navigate ->
            if (browse("https://yandex.ru/maps/?text=" + enc(command.destination))) DeviceResult("Открываю карту: ${command.destination}.") else DeviceResult("Не получилось открыть карту.", ok = false)
        is DeviceCommand.Play ->
            if (browse("https://www.youtube.com/results?search_query=" + enc(command.query))) DeviceResult("Ищу «${command.query}» на YouTube.") else DeviceResult("Не получилось открыть браузер.", ok = false)
        is DeviceCommand.Timer -> {
            // Своих таймеров в Windows нет — ставим напоминание Лоли, оно всплывёт окном со звуком.
            val at = time.now().plusSeconds(command.seconds.toLong())
            store.reminders.create("Таймер" + (if (command.label.isNotBlank()) ": ${command.label}" else "") + " — время вышло", at, null, time.zone().id)
            DeviceResult("Таймер на ${describe(command.seconds)}. Сообщу окном со звуком.")
        }
        is DeviceCommand.Alarm -> alarm(command)
        DeviceCommand.ShowAlarms -> {
            val alarms = store.reminders.active().filter { it.text.startsWith("Будильник") }.sortedBy { it.triggerAt }
            if (alarms.isEmpty()) DeviceResult("Будильников нет.")
            else DeviceResult("Будильники: " + alarms.joinToString("; ") { RuFormat.dateTime(it.triggerAt, time.zone(), time.now()) + (it.recurrence?.let { r -> ", ${r.describeRu()}" } ?: "") } + ".")
        }
        is DeviceCommand.ShareFile -> {
            val dir = File(home, "Downloads").takeIf { it.isDirectory } ?: home
            val file = File(dir, command.name.replace(Regex("""[^\w.\-]"""), "_"))
            file.writeText(command.content, Charsets.UTF_8)
            runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir) }
            DeviceResult("Сохранила в папку «${dir.name}»: ${file.name}.")
        }
        is DeviceCommand.OpenApp -> openApp(command.name)
        is DeviceCommand.OpenSettings -> settings(command.section)
        is DeviceCommand.Media -> if (!windows) unsupported() else {
            val key = when (command.action) { MediaAction.PLAY, MediaAction.PAUSE -> 179; MediaAction.NEXT -> 176; MediaAction.PREVIOUS -> 177 }
            keys(key, 1)
            DeviceResult(when (command.action) { MediaAction.PLAY -> "Включаю."; MediaAction.PAUSE -> "Пауза."; MediaAction.NEXT -> "Следующий трек."; MediaAction.PREVIOUS -> "Предыдущий трек." })
        }
        is DeviceCommand.Volume -> volume(command)
        is DeviceCommand.Global -> when (command.action) {
            GlobalAction.LOCK -> if (windows && run("rundll32.exe", "user32.dll,LockWorkStation")) DeviceResult("Блокирую компьютер.") else unsupported()
            GlobalAction.SCREENSHOT -> screenshot()
            GlobalAction.NOTIFICATIONS -> if (browse("ms-actioncenter:")) DeviceResult("Открываю центр уведомлений.") else unsupported()
            else -> unsupported()
        }
        DeviceCommand.Battery -> battery()
        is DeviceCommand.Brightness -> brightness(command)
        is DeviceCommand.DoNotDisturb ->
            if (browse("ms-settings:quiethours")) DeviceResult("Открыла «Не беспокоить» в параметрах Windows — переключите его там.") else unsupported()
        is DeviceCommand.Focus -> focus(command)
        is DeviceCommand.Relax -> relax(command)
        DeviceCommand.Stopwatch -> if (browse("ms-clock:")) DeviceResult("Открываю часы Windows — там секундомер.") else unsupported()
        is DeviceCommand.Camera -> if (browse("microsoft.windows.camera:")) DeviceResult("Открываю камеру.") else unsupported()
        is DeviceCommand.CalendarEvent -> calendar(command)
        DeviceCommand.OpenBackup -> DeviceResult("Резервная копия — в «Настройки → Резервная копия». Там же можно восстановить копию с телефона.")
        else -> unsupported()
    }

    private fun unsupported() = DeviceResult("На компьютере так пока не умею — это работает на телефоне.", ok = false)

    // ------------------------------------------------------------------ Программы и папки

    private fun openApp(raw: String): DeviceResult {
        val name = RuTokenizer.normalize(raw).trim()
        if (name.isEmpty()) return DeviceResult("Какую программу открыть?", ok = false)
        BUILT_IN.entries.firstOrNull { (keys, _) -> keys.any { name.contains(it) } }?.let { (_, target) ->
            val ok = when {
                target.startsWith("dir:") -> open(folder(target.removePrefix("dir:")))
                target.contains(':') -> browse(target)
                else -> windows && run(target)
            }
            if (ok) return DeviceResult("Открываю ${raw.trim()}.")
        }
        // Любая программа из меню «Пуск»: ищем ярлык по названию (в том числе русское → латиница: «телеграм» → telegram).
        val wanted = (listOf(name) + TRANSLIT.filterKeys { name.contains(it) }.values).distinct()
        startMenuShortcuts().firstOrNull { f ->
            val title = f.nameWithoutExtension.lowercase()
            wanted.any { title.contains(it) }
        }?.let { lnk -> if (open(lnk)) return DeviceResult("Открываю ${lnk.nameWithoutExtension}.") }
        return DeviceResult("Не нашла программу «${raw.trim()}» в меню «Пуск». Назовите её так, как она там подписана.", ok = false)
    }

    private fun folder(key: String): File = when (key) {
        "downloads" -> File(home, "Downloads")
        "documents" -> File(home, "Documents")
        "desktop" -> File(home, "Desktop")
        "pictures" -> File(home, "Pictures")
        "music" -> File(home, "Music")
        "videos" -> File(home, "Videos")
        else -> home
    }

    private fun startMenuShortcuts(): List<File> {
        val roots = listOfNotNull(System.getenv("ProgramData"), System.getenv("APPDATA"))
            .map { File(it, "Microsoft/Windows/Start Menu/Programs") }.filter { it.isDirectory }
        return roots.flatMap { root ->
            root.walkTopDown().maxDepth(4).filter { it.isFile && (it.extension.equals("lnk", true) || it.extension.equals("url", true)) }
                .filterNot { it.nameWithoutExtension.lowercase().let { n -> n.contains("uninstall") || n.contains("удал") } }.toList()
        }.sortedBy { it.nameWithoutExtension.length }
    }

    private fun settings(section: SettingsSection): DeviceResult {
        val uri = when (section) {
            SettingsSection.WIFI -> "ms-settings:network-wifi"
            SettingsSection.BLUETOOTH -> "ms-settings:bluetooth"
            SettingsSection.SOUND -> "ms-settings:sound"
            SettingsSection.DISPLAY -> "ms-settings:display"
            SettingsSection.BATTERY -> "ms-settings:batterysaver"
            SettingsSection.LOCATION -> "ms-settings:privacy-location"
            SettingsSection.APPS -> "ms-settings:appsfeatures"
            SettingsSection.AIRPLANE -> "ms-settings:network-airplanemode"
            SettingsSection.HOTSPOT -> "ms-settings:network-mobilehotspot"
            SettingsSection.MOBILE_DATA -> "ms-settings:network-cellular"
            SettingsSection.NOTIFICATIONS -> "ms-settings:notifications"
            SettingsSection.SECURITY -> "windowsdefender:"
            SettingsSection.ACCESSIBILITY -> "ms-settings:easeofaccess"
            SettingsSection.DATE_TIME -> "ms-settings:dateandtime"
            SettingsSection.STORAGE -> "ms-settings:storagesense"
            SettingsSection.NFC, SettingsSection.MAIN -> "ms-settings:"
        }
        return if (browse(uri)) DeviceResult("Открываю параметры Windows.") else unsupported()
    }

    // ------------------------------------------------------------------ Звук, экран, питание

    /** Нажатие мультимедийных клавиш (громкость, пауза) через Windows — как кнопки на клавиатуре. */
    private fun keys(code: Int, times: Int) {
        powershell("\$w = New-Object -ComObject WScript.Shell; 1..${times.coerceIn(1, 60)} | ForEach-Object { \$w.SendKeys([char]$code) }")
    }

    private fun volume(c: DeviceCommand.Volume): DeviceResult {
        if (!windows) return unsupported()
        // Одно нажатие — 2%.
        return when (c.change) {
            VolumeChange.UP -> { keys(175, (c.percent ?: 10) / 2); DeviceResult("Сделала громче.") }
            VolumeChange.DOWN -> { keys(174, (c.percent ?: 10) / 2); DeviceResult("Сделала тише.") }
            VolumeChange.MUTE, VolumeChange.UNMUTE -> { keys(173, 1); DeviceResult(if (c.change == VolumeChange.MUTE) "Выключила звук." else "Включила звук.") }
            VolumeChange.MAX -> { keys(175, 50); DeviceResult("Громкость на максимум.") }
            VolumeChange.SET -> {
                val p = (c.percent ?: 50).coerceIn(0, 100)
                keys(174, 50); if (p > 0) keys(175, p / 2)
                DeviceResult("Громкость $p%.")
            }
        }
    }

    private fun battery(): DeviceResult {
        if (!windows) return unsupported()
        return runCatching {
            val s = PowerStatus()
            check(PowerLib.INSTANCE.GetSystemPowerStatus(s))
            val percent = s.BatteryLifePercent.toInt() and 0xff
            val plugged = s.ACLineStatus.toInt() == 1
            if (percent == 255 || (s.BatteryFlag.toInt() and 0x80) != 0) DeviceResult("У этого компьютера нет батареи — он работает от сети.")
            else DeviceResult("Заряд $percent%" + if (plugged) ", заряжается." else ".")
        }.getOrElse { DeviceResult("Не получилось узнать заряд.", ok = false) }
    }

    private fun brightness(c: DeviceCommand.Brightness): DeviceResult {
        if (!windows) return unsupported()
        val current = powershell("(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness -ErrorAction SilentlyContinue | Select-Object -First 1).CurrentBrightness")
            ?.trim()?.toIntOrNull() ?: return DeviceResult("Яркость этого монитора меняется кнопками на самом мониторе — из Windows не получится.", ok = false)
        val target = (c.percent ?: (current + c.delta)).coerceIn(5, 100)
        powershell("(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods | Select-Object -First 1) | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{Timeout=1; Brightness=$target}")
        return DeviceResult("Яркость $target%.")
    }

    private fun screenshot(): DeviceResult = runCatching {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }.reduce { a, b -> a.union(b) as Rectangle }
        val multi = Robot().createMultiResolutionScreenCapture(bounds)
        val image = multi.resolutionVariants.maxByOrNull { it.getWidth(null) } as java.awt.image.BufferedImage
        val dir = File(File(home, "Pictures"), "Screenshots").apply { mkdirs() }
        val file = File(dir, "loli-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".png")
        ImageIO.write(image, "png", file)
        DeviceResult("Скриншот сохранён: Изображения → Screenshots → ${file.name}.")
    }.getOrElse { Logger.w(TAG, "Скриншот не удался", it); DeviceResult("Не получилось сделать скриншот.", ok = false) }

    // ------------------------------------------------------------------ Будильник, помодоро, отдых, календарь

    private suspend fun alarm(c: DeviceCommand.Alarm): DeviceResult {
        val zone = time.zone()
        val now = ZonedDateTime.ofInstant(time.now(), zone)
        var at = now.with(c.time).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val text = "Будильник" + (if (c.label.isNotBlank()) ": ${c.label}" else "")
        val rule = if (c.days.isNotEmpty()) Recurrence(Recurrence.Frequency.WEEKLY, daysOfWeek = c.days, time = c.time) else null
        val first = rule?.nextAfter(time.now().minusMillis(1), zone, at.toInstant()) ?: at.toInstant()
        store.reminders.create(text, first, rule, zone.id)
        return DeviceResult(
            "Будильник на ${RuFormat.time(c.time)}" + (rule?.let { ", ${it.describeRu()}" } ?: " — " + RuFormat.dateTime(first, zone, time.now()).substringBefore(" в ")) +
                ". Компьютер должен быть включён, а Лоли — запущена.",
        )
    }

    private suspend fun focus(c: DeviceCommand.Focus): DeviceResult {
        val existing = store.reminders.active().filter { it.text.startsWith(POMODORO) }
        existing.forEach { store.reminders.cancel(it.id) }
        if (!c.on) return DeviceResult(if (existing.isEmpty()) "Помодоро не запущено." else "Остановила помодоро.")
        val zone = time.zone().id
        var t = time.now()
        repeat(4) { round ->
            t = t.plusSeconds(c.workMinutes * 60L)
            val last = round == 3
            store.reminders.create("$POMODORO перерыв ${if (last) 15 else c.restMinutes} минут", t, null, zone)
            t = t.plusSeconds((if (last) 15 else c.restMinutes) * 60L)
            if (!last) store.reminders.create("$POMODORO снова за работу (${round + 2} из 4)", t, null, zone)
        }
        store.reminders.create("$POMODORO цикл закончен — отличная работа!", t, null, zone)
        return DeviceResult("Помодоро: ${c.workMinutes} минут работы, ${c.restMinutes} — отдых, четыре круга. Скажу, когда перерыв. «Хватит работать» — остановить.")
    }

    private suspend fun relax(c: DeviceCommand.Relax): DeviceResult = when (c.kind) {
        RelaxKind.BREATHING -> DeviceResult("Дышим вместе: вдох на четыре счёта, задержка на четыре, выдох на шесть. Повторите пять раз — станет спокойнее.")
        RelaxKind.MEDITATION -> {
            store.reminders.create("Медитация окончена — потянитесь и улыбнитесь", time.now().plusSeconds(c.minutes * 60L), null, time.zone().id)
            DeviceResult("Медитация на ${RuFormat.count(c.minutes, "минуту", "минуты", "минут")}: закройте глаза и следите за дыханием. Скажу, когда время выйдет.")
        }
        RelaxKind.STOP -> DeviceResult("Хорошо, заканчиваем.")
    }

    private suspend fun calendar(c: DeviceCommand.CalendarEvent): DeviceResult {
        val zone = time.zone()
        val start = c.start?.atZone(zone)
        val task = store.tasks.create(c.title, "", start?.toLocalDate(), if (c.allDay) null else start?.toLocalTime())
        val alarm = TaskReminders.schedule(task, store.reminders, noScheduler, zone, time.now())
        return DeviceResult(
            "Записала в планы: ${RuFormat.quote(task.title)}" +
                (start?.let { " — " + RuFormat.dateTime(it.toInstant(), zone, time.now()) } ?: "") + "." + (if (alarm != null) " Напомню в назначенное время." else ""),
        )
    }

    private fun describe(s: Int): String = when {
        s % 3600 == 0 -> RuFormat.count(s / 3600, "час", "часа", "часов")
        s % 60 == 0 -> RuFormat.count(s / 60, "минуту", "минуты", "минут")
        else -> RuFormat.count(s, "секунду", "секунды", "секунд")
    }

    private companion object {
        const val TAG = "DesktopDevice"
        const val POMODORO = "Помодоро:"

        /** Встроенные программы и места Windows: слова в названии → что открыть. */
        val BUILT_IN: Map<List<String>, String> = linkedMapOf(
            listOf("калькулятор") to "calc.exe",
            listOf("блокнот") to "notepad.exe",
            listOf("paint", "пейнт", "пэйнт", "рисовал") to "mspaint.exe",
            listOf("проводник", "мой компьютер", "этот компьютер") to "explorer.exe",
            listOf("диспетчер задач") to "taskmgr.exe",
            listOf("панель управления") to "control.exe",
            listOf("параметры", "настройки windows", "настройки виндовс", "настройки компьютера") to "ms-settings:",
            listOf("загрузки") to "dir:downloads",
            listOf("документы") to "dir:documents",
            listOf("рабочий стол") to "dir:desktop",
            listOf("картинки", "изображения", "фотографии", "фото") to "dir:pictures",
            listOf("музыка", "музыку") to "dir:music",
            listOf("видео") to "dir:videos",
            listOf("браузер", "интернет") to "https://ya.ru",
            listOf("почту", "почта") to "mailto:",
            listOf("камеру", "камера") to "microsoft.windows.camera:",
            listOf("часы", "секундомер") to "ms-clock:",
            listOf("магазин", "microsoft store") to "ms-windows-store:",
            listOf("карты", "карту") to "https://yandex.ru/maps/",
        )

        /** Русские названия программ → как они подписаны в меню «Пуск». */
        val TRANSLIT = mapOf(
            "телеграм" to "telegram", "хром" to "chrome", "гугл" to "google", "ворд" to "word", "эксель" to "excel",
            "повер поинт" to "powerpoint", "поверпоинт" to "powerpoint", "аутлук" to "outlook", "стим" to "steam",
            "дискорд" to "discord", "спотифай" to "spotify", "зум" to "zoom", "вотсап" to "whatsapp", "ватсап" to "whatsapp",
            "яндекс" to "yandex", "фаерфокс" to "firefox", "файрфокс" to "firefox", "опера" to "opera", "эдж" to "edge",
            "скайп" to "skype", "вайбер" to "viber", "обс" to "obs", "вскод" to "code", "вс код" to "code", "фотошоп" to "photoshop",
            "винрар" to "winrar", "тимс" to "teams", "нотпад" to "notepad", "вк" to "vk", "одноклассники" to "ok",
        )
    }
}

/** SYSTEM_POWER_STATUS из kernel32 (в jna-platform её нет). */
@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
class PowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

interface PowerLib : Library {
    fun GetSystemPowerStatus(status: PowerStatus): Boolean

    companion object {
        val INSTANCE: PowerLib by lazy { Native.load("kernel32", PowerLib::class.java) }
    }
}
