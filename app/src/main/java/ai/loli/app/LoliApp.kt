package ai.loli.app

import android.app.Application
import android.util.Log
import ai.loli.app.reminders.Notifications
import ai.loli.core.util.LogSink
import ai.loli.core.util.Logger

class LoliApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Логи проходят через Redactor (маскировка ключей и токенов); в release — только предупреждения и ошибки.
        Logger.minLevel = if (BuildConfig.DEBUG) Logger.Level.DEBUG else Logger.Level.WARN
        Logger.sink = object : LogSink {
            override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
                val t = "Loli/$tag"
                when (level) {
                    Logger.Level.DEBUG -> Log.d(t, message, error)
                    Logger.Level.INFO -> Log.i(t, message, error)
                    Logger.Level.WARN -> Log.w(t, message, error)
                    Logger.Level.ERROR -> Log.e(t, message, error)
                }
            }
        }
        Notifications.createChannels(this)
        container = AppContainer(this)
    }
}
