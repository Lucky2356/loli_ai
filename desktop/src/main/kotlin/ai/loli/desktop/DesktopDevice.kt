package ai.loli.desktop

import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DeviceController
import ai.loli.core.assistant.DeviceResult
import ai.loli.core.assistant.RuFormat
import ai.loli.core.data.LocalStore
import ai.loli.core.util.TimeSource
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder

/**
 * Команды компьютеру: сайты, поиск, маршрут, таймер (напоминанием), выгрузка таблицы в «Загрузки».
 * Звонки, фонарик, камера и прочее телефонное здесь честно не поддерживаются.
 */
class DesktopDevice(private val store: LocalStore, private val time: TimeSource) : DeviceController {
    private fun browse(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
        Desktop.getDesktop().browse(URI(url)); true
    }.getOrDefault(false)

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
            // Своих таймеров в Windows нет — ставим напоминание Лоли, оно всплывёт в трее.
            val at = time.now().plusSeconds(command.seconds.toLong())
            store.reminders.create("Таймер" + (if (command.label.isNotBlank()) ": ${command.label}" else "") + " — время вышло", at, null, time.zone().id)
            DeviceResult("Таймер на ${describe(command.seconds)}. Напомню уведомлением.")
        }
        is DeviceCommand.ShareFile -> {
            val dir = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
            val file = File(dir, command.name.replace(Regex("""[^\w.\-]"""), "_"))
            file.writeText(command.content, Charsets.UTF_8)
            runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir) }
            DeviceResult("Сохранила в папку «${dir.name}»: ${file.name}.")
        }
        else -> DeviceResult("На компьютере так пока не умею — это работает на телефоне.", ok = false)
    }

    private fun describe(s: Int): String = when {
        s % 3600 == 0 -> RuFormat.count(s / 3600, "час", "часа", "часов")
        s % 60 == 0 -> RuFormat.count(s / 60, "минуту", "минуты", "минут")
        else -> RuFormat.count(s, "секунду", "секунды", "секунд")
    }
}
