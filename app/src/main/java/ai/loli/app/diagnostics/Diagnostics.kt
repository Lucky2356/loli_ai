package ai.loli.app.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import ai.loli.app.BuildConfig
import ai.loli.core.util.Redactor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Отчёты о сбоях и непонятых фразах — только на телефоне. Никуда не отправляются сами:
 * при следующем запуске Лоли спросит, отправить ли отчёт (через GitHub или любое приложение).
 * В отчёте нет записей, ключей и текста команд — только тип ошибки и место в коде.
 */
object Diagnostics {
    private const val CRASH_FILE = "last_crash.txt"
    private const val PHRASES = "unknown_phrases"
    private const val REPO = "Lucky2356/loli_ai"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, CRASH_FILE).writeText(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { w -> PrintWriter(w).use { p -> sanitized(error).printStackTrace(p) } }.toString()
        return buildString {
            appendLine("Лоли ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Время: ${Instant.now()} · поток: ${thread.name}")
            appendLine()
            append(trace.lines().take(60).joinToString("\n"))
        }
    }

    /** Текст исключений очищается от секретов и укорачивается: там могут оказаться данные. */
    private fun sanitized(e: Throwable, depth: Int = 0): Throwable {
        val msg = Redactor.redact(e.message.orEmpty()).take(160)
        val copy = Throwable("${e::class.java.name}: $msg")
        copy.stackTrace = e.stackTrace
        if (depth < 3) e.cause?.let { copy.initCause(sanitized(it, depth + 1)) }
        return copy
    }

    fun pendingCrash(context: Context): String? = runCatching { File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clearCrash(context: Context) { runCatching { File(context.filesDir, CRASH_FILE).delete() } }

    /** Непонятая фраза — для улучшения разбора. Хранятся последние 50, только на телефоне. */
    fun rememberUnknown(context: Context, phrase: String) {
        val p = context.getSharedPreferences(PHRASES, Context.MODE_PRIVATE)
        val list = (p.getString("list", "").orEmpty().split('\n').filter { it.isNotBlank() } + phrase.replace('\n', ' ').take(200)).takeLast(50)
        p.edit().putString("list", list.joinToString("\n")).apply()
    }

    fun unknownPhrases(context: Context): List<String> =
        context.getSharedPreferences(PHRASES, Context.MODE_PRIVATE).getString("list", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun clearUnknown(context: Context) = context.getSharedPreferences(PHRASES, Context.MODE_PRIVATE).edit().clear().apply()

    /** Новое обращение на GitHub с готовым текстом (человек видит его и сам решает, отправлять ли). */
    fun issueIntent(title: String, body: String): Intent {
        val url = "https://github.com/$REPO/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body.take(5500))
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Отправить текстом через любое приложение (почта, Telegram). */
    fun shareIntent(title: String, body: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, body),
            title,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
