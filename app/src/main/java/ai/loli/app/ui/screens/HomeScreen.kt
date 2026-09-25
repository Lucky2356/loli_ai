package ai.loli.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.ui.components.AssistantOrb
import ai.loli.app.ui.components.OrbMode
import ai.loli.app.voice.VoiceState
import ai.loli.core.auth.AuthState
import ai.loli.core.model.MessageRole
import ai.loli.core.nlp.Money
import ai.loli.core.sync.SyncStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onOpen: (String) -> Unit, onMicClick: () -> Unit) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    val (mode, statusText) = when (val v = voice) {
        VoiceState.Idle -> OrbMode.IDLE to "Нажмите на микрофон или скажите «${settings.assistantName}»"
        is VoiceState.Listening -> OrbMode.LISTENING to if (v.followUp) "Слушаю продолжение…" else "Я слушаю…"
        is VoiceState.Thinking -> OrbMode.THINKING to "Думаю…"
        is VoiceState.Speaking -> OrbMode.SPEAKING to "Отвечаю…"
        is VoiceState.Error -> OrbMode.ERROR to v.message
    }
    val level = (voice as? VoiceState.Listening)?.level ?: 0f

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusChip(if (online) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, if (online) "Онлайн" else "Офлайн") {}
                    val aiText = when {
                        !settings.useAI -> "Локальный режим"
                        vm.aiConfigured() -> "AI: ${settings.effectiveModel}"
                        else -> "AI: нет ключа"
                    }
                    StatusChip(Icons.Outlined.Psychology, aiText) { onOpen("settings") }
                    val syncText = when {
                        auth !is AuthState.SignedIn -> "Только на устройстве"
                        sync is SyncStatus.Running -> "Синхронизация…"
                        sync is SyncStatus.Done && !(sync as SyncStatus.Done).report.ok -> "Ждёт сети"
                        else -> "Синхронизировано"
                    }
                    StatusChip(Icons.Outlined.Sync, syncText) { onOpen("settings") }
                }
            }
            item {
                Text(
                    settings.assistantName.uppercase(),
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp, fontWeight = FontWeight.Light),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Box(Modifier.padding(vertical = 8.dp).clickable { if (mode == OrbMode.IDLE || mode == OrbMode.ERROR) onMicClick() else vm.stop() }) {
                    AssistantOrb(mode, level, size = 210.dp)
                }
                Text(
                    statusText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    color = if (mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 24.dp).clickable(enabled = mode == OrbMode.ERROR) { vm.clearError() },
                )
                val heard = when (val v = voice) {
                    is VoiceState.Listening -> v.partial
                    is VoiceState.Thinking -> v.heard
                    else -> ""
                }
                if (heard.isNotBlank()) {
                    Text("«$heard»", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                }
            }
            reply?.let { r ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(r.text, style = MaterialTheme.typography.bodyLarge)
                            if (r.offline && settings.useAI) {
                                Text("AI недоступен — выполнено локально", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            if (r.awaitingConfirmation) {
                                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { vm.confirm(true) }) { Text("Да, подтверждаю") }
                                    OutlinedButton(onClick = { vm.confirm(false) }) { Text("Отмена") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickTile(Icons.Outlined.Payments, "Расходы", "сегодня ${Money.format(summary.todayExpensesMinor, "RUB")}") { onOpen("expenses") }
                    QuickTile(Icons.Outlined.TaskAlt, "Задачи", if (summary.overdueTasks > 0) "${summary.activeTasks} · просрочено ${summary.overdueTasks}" else "${summary.activeTasks} активных") { onOpen("tasks") }
                    QuickTile(Icons.Outlined.Description, "Заметки", "${summary.notes}") { onOpen("notes/note") }
                    QuickTile(Icons.Outlined.Lightbulb, "Идеи", "${summary.ideas}") { onOpen("notes/idea") }
                    QuickTile(Icons.Outlined.Alarm, "Напоминания", "${summary.activeReminders}") { onOpen("reminders") }
                    QuickTile(Icons.Outlined.Psychology, "Память", "${summary.memories}") { onOpen("memories") }
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Последние действия", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text("Вся история", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clickable { onOpen("history") })
                    }
                }
                items(history, key = { it.id }) { m ->
                    val mine = m.role == MessageRole.USER
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(0.85f),
                        ) { Text(m.content, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Напишите ${settings.assistantName}…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } }),
                trailingIcon = {
                    if (input.isNotBlank()) IconButton(onClick = { vm.send(input); input = "" }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            val listening = mode == OrbMode.LISTENING || mode == OrbMode.THINKING || mode == OrbMode.SPEAKING
            FloatingActionButton(onClick = { if (listening) vm.stop() else onMicClick() }) {
                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = if (listening) "Остановить" else "Говорить", modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick, label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(160.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
