package ai.loli.app.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech

/** Куда отправить человека, если в телефоне нет русского голоса. */
object RussianVoiceHelp {
    fun title(status: AndroidTtsProvider.RuStatus): String = when (status) {
        AndroidTtsProvider.RuStatus.MISSING_DATA -> "Скачайте русский голос"
        else -> "Установите русский голос"
    }

    fun hint(status: AndroidTtsProvider.RuStatus): String = when (status) {
        AndroidTtsProvider.RuStatus.MISSING_DATA -> "Русский голос есть, но не скачан. Нажмите и загрузите «Русский», потом вернитесь в Лоли."
        else -> "В синтезаторе речи этого телефона нет русского языка, поэтому Лоли молчит или говорит непонятно. Установите «Синтезатор речи Google» (бесплатно) и вернитесь — Лоли сама переключится на него."
    }

    fun act(context: Context, status: AndroidTtsProvider.RuStatus, engine: String) {
        if (status == AndroidTtsProvider.RuStatus.MISSING_DATA) {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (engine.isNotBlank()) intent.setPackage(engine)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        openGoogleTts(context)
    }

    fun openGoogleTts(context: Context) {
        val pkg = AndroidTtsProvider.GOOGLE_TTS
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isSuccess) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
