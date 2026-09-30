package ai.loli.app.health

import android.content.Context
import ai.loli.core.skills.IncomingMessage
import ai.loli.core.voice.SpeechText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * «Я за рулём»: новые сообщения мессенджеров Лоли читает вслух сама. Выключается фразой «я приехала»
 * или сам через 2 часа — чтобы не читать личное вслух, когда человек уже не в машине.
 */
class DrivingMode(context: Context, private val scope: CoroutineScope, private val speak: suspend (String) -> Unit) {
    private val prefs = context.getSharedPreferences("loli_driving", Context.MODE_PRIVATE)

    fun set(on: Boolean) {
        prefs.edit().putLong(KEY_UNTIL, if (on) System.currentTimeMillis() + DURATION else 0L).apply()
    }

    fun active(): Boolean = System.currentTimeMillis() < prefs.getLong(KEY_UNTIL, 0L)

    fun onMessage(m: IncomingMessage) {
        if (!active()) return
        // Как и любой ответ Лоли: без эмодзи и разметки, ссылки не читаются по буквам.
        val text = m.text.take(300).replace(Regex("""https?://\S+|www\.\S+"""), "ссылка")
        scope.launch { speak(SpeechText.forSpeech("${m.sender} пишет в ${m.app}: $text", maxLines = 1)) }
    }

    private companion object {
        const val KEY_UNTIL = "until"
        const val DURATION = 2 * 3600_000L
    }
}
