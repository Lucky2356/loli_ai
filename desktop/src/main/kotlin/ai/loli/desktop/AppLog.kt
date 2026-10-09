package ai.loli.desktop

import ai.loli.core.util.LogSink
import ai.loli.core.util.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал ошибок на компьютере: %APPDATA%\Loli\logs\loli.log (до 1 МБ, затем loli.1.log).
 * Сообщения уже очищены от ключей ([Logger] прогоняет их через Redactor); фразы пользователя сюда не пишутся.
 * Падение любого потока тоже попадает сюда, а не теряется в невидимой консоли.
 */
class FileLogSink(private val dir: File, private val maxBytes: Long = 1_000_000) : LogSink {
    private val file = File(dir, "loli.log")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Synchronized
    override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
        if (level < Logger.Level.INFO) return
        runCatching {
            dir.mkdirs()
            if (file.length() > maxBytes) {
                val old = File(dir, "loli.1.log")
                old.delete()
                file.renameTo(old)
            }
            val line = buildString {
                append(LocalDateTime.now().format(stamp)).append(' ').append(level.name.padEnd(5)).append(' ').append(tag).append(": ").append(message)
                if (error != null) append('\n').append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
                append('\n')
            }
            file.appendText(line, Charsets.UTF_8)
        }
    }

    val path: File get() = file

    companion object {
        /** Подключает журнал и перехват падений. Вызывается первой строкой программы. */
        fun install(dataDir: File): FileLogSink {
            val sink = FileLogSink(File(dataDir, "logs"))
            Logger.minLevel = Logger.Level.INFO
            Logger.sink = sink
            Thread.setDefaultUncaughtExceptionHandler { t, e -> Logger.e("Crash", "Необработанная ошибка в потоке ${t.name}", e) }
            Logger.i("App", "Запуск: Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            return sink
        }
    }
}
