package ai.loli.core.util

/**
 * Логирование без утечек секретов. Любое сообщение проходит через [Redactor]:
 * API-ключи, токены, JWT, пароли и Authorization-заголовки маскируются.
 * Пользовательские высказывания в логи не пишутся — только их длина/тип.
 */
interface LogSink {
    fun log(level: Logger.Level, tag: String, message: String, error: Throwable?)
}

object Logger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile var sink: LogSink = object : LogSink {
        override fun log(level: Level, tag: String, message: String, error: Throwable?) {
            if (level >= Level.INFO) println("[$level] $tag: $message" + (error?.let { " (${it::class.simpleName})" } ?: ""))
        }
    }

    @Volatile var minLevel: Level = Level.DEBUG

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    private fun log(level: Level, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        sink.log(level, tag, Redactor.redact(message), error?.let { RedactedThrowable(it) })
    }

    /** Исключение, у которого текст сообщения очищен от секретов. */
    class RedactedThrowable(original: Throwable) :
        Throwable("${original::class.simpleName}: ${Redactor.redact(original.message ?: "")}") {
        init { stackTrace = original.stackTrace }
    }
}

object Redactor {
    private val patterns = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+") to "$1***",
        Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*") to "***jwt***",
        Regex("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{8,}") to "sk-***",
        Regex("\\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{6,}") to "sb_***",
        Regex("\\bAIza[0-9A-Za-z_-]{20,}") to "AIza***",
        Regex("(?i)(\"?(?:api[_-]?key|x-api-key|apikey|access_token|refresh_token|password|secret|authorization)\"?\\s*[:=]\\s*\"?)[^\"\\s,&}]+") to "$1***",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
