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
    private const val PHRASES_KEY = "unknown_phrases_v2"
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

    /** Непонятая фраза — для улучшения разбора. Хранятся последние 50, только на телефоне и зашифрованными (ключ Android Keystore). */
    fun rememberUnknown(context: Context, phrase: String) {
        val list = (unknownPhrases(context) + scrub(phrase)).takeLast(50)
        secrets(context).put(PHRASES_KEY, list.joinToString("\n"))
    }

    /**
     * Фразы могут уйти в публичную заявку — убираем то, что похоже на личное:
     * длинные числа (телефоны, карты, коды), почту, ссылки и ключи. Короткие числа («через 5 минут») оставляем.
     */
    fun scrub(phrase: String): String = Redactor.redact(phrase.replace('\n', ' '))
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[почта]")
        .replace(Regex("""https?://\S+"""), "[ссылка]")
        .replace(Regex("""\+?\d[\d\s()-]{4,}\d"""), "[число]")
        .take(200)

    fun unknownPhrases(context: Context): List<String> {
        migrateOldPhrases(context)
        return secrets(context).get(PHRASES_KEY).orEmpty().split('\n').filter { it.isNotBlank() }
    }

    fun clearUnknown(context: Context) {
        secrets(context).put(PHRASES_KEY, null)
        context.getSharedPreferences(PHRASES, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun secrets(context: Context) = ai.loli.app.security.KeystoreSecretStore(context.applicationContext)

    /** До 2.3 фразы лежали в обычных настройках открытым текстом — переносим в зашифрованное хранилище и стираем. */
    private fun migrateOldPhrases(context: Context) {
        val old = context.getSharedPreferences(PHRASES, Context.MODE_PRIVATE)
        val text = old.getString("list", null) ?: return
        val store = secrets(context)
        if (store.get(PHRASES_KEY).isNullOrEmpty()) store.put(PHRASES_KEY, text)
        old.edit().clear().apply()
    }

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
