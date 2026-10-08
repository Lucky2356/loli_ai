package ai.loli.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import ai.loli.desktop.ui.LoliIcon
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
            Item("Открыть", onClick = { visible = true })
            Item("Выход", onClick = ::exitApplication)
        },
    )
    if (visible) {
        Window(
            onCloseRequest = { visible = false },
            title = settings.assistantName,
            icon = icon,
            state = rememberWindowState(size = DpSize(980.dp, 720.dp)),
        ) {
            LoliTheme(settings.darkTheme ?: isSystemInDarkTheme()) { MainWindow(c) }
        }
    }
}

private val Indigo = Color(0xFF5B5BD6)

@Composable
fun LoliTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(primary = Color(0xFFB4B5FF), secondary = Color(0xFFC5C4DD), tertiary = Color(0xFFE7B7CB))
    else lightColorScheme(primary = Indigo, secondary = Color(0xFF5D5C72), tertiary = Color(0xFF7A5263))
    MaterialTheme(colorScheme = scheme, content = content)
}
