package ai.loli.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.loli.desktop.DesktopContainer

enum class Section(val title: String, val icon: ImageVector) {
    CHAT("Чат", Icons.Rounded.AutoAwesome),
    RECORDS("Записи", Icons.Rounded.StickyNote2),
    PLANS("Планы", Icons.Rounded.CalendarMonth),
    MONEY("Финансы", Icons.Rounded.AccountBalanceWallet),
    SETTINGS("Настройки", Icons.Rounded.Tune),
}

@Composable
fun MainWindow(c: DesktopContainer, systemDark: Boolean) {
    var section by remember { mutableStateOf(Section.CHAT) }
    val p = palette
    Row(Modifier.fillMaxSize().background(p.background)) {
        Sidebar(c, section, { section = it }, systemDark)
        Box(Modifier.width(1.dp).fillMaxHeight().background(p.outline))
        AnimatedContent(section, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.fillMaxSize()) { s ->
            when (s) {
                Section.CHAT -> ChatScreen(c, openSettings = { section = Section.SETTINGS })
                Section.RECORDS -> RecordsScreen(c)
                Section.PLANS -> PlansScreen(c)
                Section.MONEY -> MoneyScreen(c)
                Section.SETTINGS -> SettingsScreen(c)
            }
        }
    }
}

@Composable
private fun Sidebar(c: DesktopContainer, current: Section, onSelect: (Section) -> Unit, systemDark: Boolean) {
    val p = palette
    val s by c.settings.state.collectAsState()
    Column(Modifier.width(232.dp).fillMaxHeight().background(p.sidebar).padding(horizontal = 14.dp, vertical = 18.dp)) {
        // Логотип
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 26.dp)) {
            Orb(34.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(s.assistantName, style = MaterialTheme.typography.titleMedium)
                Text("персональный ассистент", style = MaterialTheme.typography.labelSmall)
            }
        }
        Section.entries.forEach { item ->
            NavItem(item.title, item.icon, item == current) { onSelect(item) }
        }
        Spacer(Modifier.weight(1f))
        // Состояние: AI и голос — видно сразу, без захода в настройки.
        val ai = s.aiEnabled && c.aiConfig(s).isComplete
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusLine(if (ai) p.success else p.faint, if (ai) "AI: ${c.aiConfig(s).type.title}" else "AI выключен — работаю на компьютере")
            StatusLine(if (c.voice.output.available) p.success else p.faint, if (s.voiceReplies) "Отвечаю голосом" else "Голос выключен")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тема", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                val dark = s.darkTheme ?: systemDark
                IconCircle(
                    if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Сменить тему",
                    { c.settings.update { it.copy(darkTheme = !dark) } }, size = 30.dp,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

@Composable
private fun NavItem(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).height(42.dp).clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected -> p.surfaceHover
                    hovered -> p.surfaceHigh
                    else -> Color.Transparent
                },
            )
            .hoverable(src).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) p.gradient else androidx.compose.ui.graphics.SolidColor(Color.Transparent)))
        Icon(icon, null, tint = if (selected) p.accent else p.muted, modifier = Modifier.padding(start = 9.dp).size(20.dp))
        Text(
            title, style = MaterialTheme.typography.labelLarge.copy(color = if (selected) p.text else p.muted),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
