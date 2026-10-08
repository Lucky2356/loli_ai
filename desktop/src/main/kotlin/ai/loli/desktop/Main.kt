package ai.loli.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import ai.loli.core.util.Logger
import ai.loli.desktop.ui.LoliIcon
import ai.loli.desktop.ui.LoliTheme
import ai.loli.desktop.ui.MainWindow
import ai.loli.desktop.ui.Section
import ai.loli.desktop.voice.VoiceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val dataDir = DesktopContainer.defaultDataDir()
    val log = FileLogSink.install(dataDir)
    // Повторный запуск (ярлык, автозагрузка) только показывает уже открытую Лоли.
    val showRequests = MutableStateFlow(0)
    val instance = SingleInstance.acquire(dataDir) { showRequests.update { it + 1 } }
    if (instance == null) {
        Logger.i("App", "Лоли уже запущена — показываю её окно")
        return
    }
    val c = try {
        DesktopContainer(dataDir)
    } catch (e: Throwable) {
        Logger.e("App", "Не удалось запуститься", e)
        JOptionPane.showMessageDialog(
            null, "Лоли не смогла открыть свои данные:\n${e.message ?: e::class.simpleName}\n\nПапка данных: ${dataDir.absolutePath}\nЖурнал: ${log.path.absolutePath}",
            "Лоли", JOptionPane.ERROR_MESSAGE,
        )
        instance.release()
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { c.settings.flush() } })
    Autostart.refresh()
    val startHidden = Autostart.TRAY_FLAG in args
    var closed = false
    fun shutdown() {
        if (closed) return
        closed = true
        Logger.i("App", "Выход")
        c.close()
        instance.release()
    }

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        var section by remember { mutableStateOf(Section.CHAT) }
        val tray = rememberTrayState()
        val icon: Painter = rememberVectorPainter(LoliIcon)
        val settings by c.settings.state.collectAsState()
        val initial = remember { c.settings.value }
        val windowState = rememberWindowState(
            placement = if (initial.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = savedPosition(initial),
            size = DpSize(initial.windowWidth.dp, initial.windowHeight.dp),
        )

        LaunchedEffect(Unit) {
            ReminderTicker(c) { title, text -> tray.sendNotification(Notification(title, text, Notification.Type.Info)) }.start()
            delay(3_000)
            c.voice.warmUp()
        }
        LaunchedEffect(Unit) {
            showRequests.drop(1).collect { visible = true; windowState.isMinimized = false }
        }
        // Размер и положение окна запоминаются (запись настроек и так отложенная, диск не дёргается).
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }.drop(1).collect { (size, pos, placement) ->
                c.settings.update { v ->
                    when (placement) {
                        WindowPlacement.Maximized -> v.copy(windowMaximized = true)
                        WindowPlacement.Floating -> v.copy(
                            windowMaximized = false,
                            windowWidth = if (size.isSpecified) size.width.value.toInt() else v.windowWidth,
                            windowHeight = if (size.isSpecified) size.height.value.toInt() else v.windowHeight,
                            windowX = (pos as? WindowPosition.Absolute)?.x?.value?.toInt() ?: v.windowX,
                            windowY = (pos as? WindowPosition.Absolute)?.y?.value?.toInt() ?: v.windowY,
                        )
                        else -> v
                    }
                }
            }
        }

        fun quit() { shutdown(); exitApplication() }

        // Закрытие окна прячет Лоли в трей: напоминания продолжают приходить. Выход — из меню значка.
        Tray(
            icon = icon, state = tray, tooltip = settings.assistantName,
            onAction = { visible = true; windowState.isMinimized = false },
            menu = {
                Item("Открыть ${settings.assistantName}", onClick = { visible = true; windowState.isMinimized = false })
                Item("Новая заметка", onClick = { visible = true; windowState.isMinimized = false; section = Section.RECORDS })
                Item("Планы", onClick = { visible = true; windowState.isMinimized = false; section = Section.PLANS })
                Separator()
                Item("Выход", onClick = { quit() })
            },
        )
        Window(
            onCloseRequest = {
                visible = false
                c.voice.stopAll()
                if (!c.settings.value.trayHintShown) {
                    tray.sendNotification(Notification(settings.assistantName, "Я осталась в трее — напоминания будут приходить. Выйти можно через значок справа внизу.", Notification.Type.Info))
                    c.settings.update { it.copy(trayHintShown = true) }
                }
            },
            visible = visible,
            title = settings.assistantName,
            icon = icon,
            state = windowState,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                when {
                    // Ctrl+Пробел — голос с любой вкладки; Esc — замолчать (только когда голос активен, иначе Esc нужен меню и диалогам).
                    e.isCtrlPressed && e.key == Key.Spacebar -> { c.voice.toggle(); true }
                    e.key == Key.Escape && c.voice.state.value !is VoiceController.State.Idle -> { c.voice.stopAll(); true }
                    // Ctrl+1…5 — вкладки.
                    e.isCtrlPressed && e.key in SECTION_KEYS -> { section = Section.entries[SECTION_KEYS.indexOf(e.key)]; true }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(960, 640)
                if (visible) window.toFront()
            }
            LaunchedEffect(Unit) { showRequests.drop(1).collect { window.toFront(); window.requestFocus() } }
            val systemDark = isSystemInDarkTheme()
            LoliTheme(settings.darkTheme ?: systemDark) { MainWindow(c, systemDark, section) { section = it } }
        }
    }
    shutdown()
}

private val SECTION_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five)

/** Сохранённое положение окна — только если оно попадает на подключённый сейчас монитор. */
private fun savedPosition(v: DesktopSettings.Values): WindowPosition {
    val x = v.windowX ?: return WindowPosition.PlatformDefault
    val y = v.windowY ?: return WindowPosition.PlatformDefault
    val visible = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { d ->
            d.defaultConfiguration.bounds.intersects(Rectangle(x, y, 200, 80))
        }
    }.getOrDefault(false)
    return if (visible) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault
}
