package ai.loli.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import ai.loli.core.assistant.RuFormat
import ai.loli.desktop.DesktopContainer
import ai.loli.desktop.ReminderAlerts
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.time.Duration
import java.time.ZoneId

private const val CARD_WIDTH = 430
private const val CARD_HEIGHT = 190

/**
 * Окно напоминаний в правом нижнем углу экрана, поверх всех программ — как уведомление на телефоне,
 * но не зависит от настроек уведомлений Windows и «Не беспокоить». Фокус не забирает: можно продолжать печатать.
 */
@Composable
fun ReminderPopups(c: DesktopContainer, dark: Boolean, accent: Accent, onOpen: () -> Unit) {
    val alerts by c.alerts.alerts.collectAsState()
    if (alerts.isEmpty()) return
    val height = alerts.size * (CARD_HEIGHT + 10) + 20
    val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val state = rememberWindowState(
        size = DpSize((CARD_WIDTH + 24).dp, height.dp),
        position = WindowPosition((area.x + area.width - CARD_WIDTH - 32).dp, (area.y + area.height - height - 12).dp),
    )
    LaunchedEffect(alerts.size) {
        state.size = DpSize((CARD_WIDTH + 24).dp, height.dp)
        state.position = WindowPosition((area.x + area.width - CARD_WIDTH - 32).dp, (area.y + area.height - height - 12).dp)
    }
    Window(
        onCloseRequest = { c.alerts.dismissAll() },
        state = state,
        title = "Напоминание",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        LoliTheme(dark, accent) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                alerts.forEach { a -> AlertCard(c, a, onOpen) }
            }
        }
    }
}

@Composable
private fun AlertCard(c: DesktopContainer, a: ReminderAlerts.Alert, onOpen: () -> Unit) {
    val p = palette
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().shadow(10.dp, shape).clip(shape).background(p.surface).border(1.dp, p.accent.copy(alpha = 0.5f), shape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.gradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Alarm, null, tint = p.onAccent, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(a.title, style = MaterialTheme.typography.labelMedium)
                Text(
                    RuFormat.time(a.firedAt.atZone(ZoneId.systemDefault()).toLocalTime()) + (a.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconCircle(Icons.Rounded.Close, "Закрыть", { c.alerts.dismiss(a.key) }, size = 26.dp)
        }
        Text(
            a.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp).weight(1f, fill = false),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("Готово", { c.alerts.dismiss(a.key) })
            if (!a.routine) {
                GhostButton("+10 мин", { scope.launch { c.alerts.snooze(a, Duration.ofMinutes(10)) } })
                GhostButton("Через час", { scope.launch { c.alerts.snooze(a, Duration.ofHours(1)) } })
                GhostButton("Завтра", { scope.launch { c.alerts.tomorrow(a) } })
            } else {
                GhostButton("Открыть", { c.alerts.dismiss(a.key); onOpen() })
            }
        }
    }
}
