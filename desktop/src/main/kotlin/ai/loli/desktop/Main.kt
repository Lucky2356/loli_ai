package ai.loli.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import ai.loli.desktop.ui.LoliIcon
import ai.loli.desktop.ui.LoliTheme
import ai.loli.desktop.ui.MainWindow

fun main() = application {
    val c = remember { DesktopContainer() }
    var visible by remember { mutableStateOf(true) }
    val tray = rememberTrayState()
    val icon: Painter = rememberVectorPainter(LoliIcon)
    val settings by c.settings.state.collectAsState()

    LaunchedEffect(Unit) {
        ReminderTicker(c) { title, text -> tray.sendNotification(Notification(title, text, Notification.Type.Info)) }.start()
    }

    // Закрытие окна прячет Лоли в трей: напоминания продолжают приходить. Выход — из меню значка.
    Tray(
        icon = icon, state = tray, tooltip = settings.assistantName,
        onAction = { visible = true },
        menu = {
            Item("Открыть ${settings.assistantName}", onClick = { visible = true })
            Item("Выход", onClick = { c.voice.stopAll(); exitApplication() })
        },
    )
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition.PlatformDefault)
    if (visible) {
        Window(
            onCloseRequest = { visible = false; c.voice.stopAll() },
            title = settings.assistantName,
            icon = icon,
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(960, 640)
            val systemDark = isSystemInDarkTheme()
            LoliTheme(settings.darkTheme ?: systemDark) { MainWindow(c, systemDark) }
        }
    }
}
