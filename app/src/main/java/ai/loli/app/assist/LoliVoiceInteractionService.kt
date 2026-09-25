package ai.loli.app.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import ai.loli.core.util.Logger

/**
 * Лоли как «Цифровой ассистент» Android (Настройки → Приложения по умолчанию → Цифровой ассистент).
 * Системе для этого нужны три сервиса: VoiceInteractionService, сессия и RecognitionService.
 * Долгое нажатие «Домой»/кнопки питания или жест из угла открывает окно ассистента поверх любого приложения.
 */
class LoliVoiceInteractionService : VoiceInteractionService()

class LoliSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = LoliSession(this)
}

class LoliSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Собственный интерфейс — окно AssistActivity; окно сессии не показываем.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistActivity::class.java).setAction(AssistActivity.ACTION_ASSIST_SESSION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startAssistantActivity(intent)
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Logger.w("Assist", "Не удалось открыть окно ассистента", e)
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        hide()
    }
}
