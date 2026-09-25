package ai.loli.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.ui.components.AssistantOrb
import ai.loli.app.ui.components.OrbMode
import ai.loli.app.ui.components.Pill
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.app.ui.components.groupColor
import ai.loli.app.voice.VoiceState
import ai.loli.core.model.MessageRole
import ai.loli.core.nlp.Money
import java.time.LocalTime

private val suggestions = listOf(
    "Что у меня на сегодня?",
    "Потратила 500 ₽ на продукты",
    "Напомни через час выпить воды",
    "Добавь задачу купить подарок",
    "Что ты умеешь?",
)

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onMic: () -> Unit,
    openRecords: (Int) -> Unit,
    openPlans: (Int) -> Unit,
    openExpenses: () -> Unit,
    openSettings: (String?) -> Unit,
    openHistory: () -> Unit,
) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    val (mode, status) = when (val v = voice) {
        VoiceState.Idle -> OrbMode.IDLE to "Нажмите на сферу или скажите «${settings.assistantName}»"
        is VoiceState.Listening -> OrbMode.LISTENING to (v.hint ?: if (v.followUp) "Слушаю дальше…" else "Слушаю…")
        is VoiceState.Thinking -> OrbMode.THINKING to "Думаю…"
        is VoiceState.Speaking -> OrbMode.SPEAKING to "Отвечаю…"
        is VoiceState.Error -> OrbMode.ERROR to v.message
    }
    val active = mode == OrbMode.LISTENING || mode == OrbMode.THINKING || mode == OrbMode.SPEAKING
    val level = (voice as? VoiceState.Listening)?.level ?: 0f
    val heard = when (val v = voice) {
        is VoiceState.Listening -> v.partial
        is VoiceState.Thinking -> v.heard
        else -> ""
    }
    val listState = rememberLazyListState()
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(settings.assistantName, style = MaterialTheme.typography.headlineMedium)
                    }
                    val (icon, label) = when {
                        !online && settings.useAI -> Icons.Rounded.CloudOff to "Офлайн"
                        !settings.useAI -> Icons.Rounded.Bolt to "На устройстве"
                        vm.aiConfigured() -> Icons.Rounded.AutoAwesome to (settings.primaryProvider?.type?.title ?: "AI")
                        else -> Icons.Rounded.AutoAwesome to "AI: нет ключа"
                    }
                    Pill(label, icon, onClick = { openSettings("settings/ai") })
                    IconButton(onClick = openHistory) { Icon(Icons.Rounded.History, contentDescription = "История", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item(key = "orb") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.clip(CircleShape).clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { if (active) vm.stop() else onMic() },
                    ) { AssistantOrb(mode, level, size = if (history.isEmpty()) 220.dp else 160.dp) }
                    Text(
                        status, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = if (mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp).clickable(enabled = mode == OrbMode.ERROR) { vm.clearError() },
                    )
                    AnimatedVisibility(heard.isNotBlank()) {
                        Text(heard, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                    }
                }
            }
            item(key = "today") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat(Modifier.weight(1f), "Задачи", "${summary.activeTasks}", if (summary.overdueTasks > 0) "просрочено ${summary.overdueTasks}" else "активных") { openPlans(0) }
                    Stat(Modifier.weight(1f), "Сегодня", Money.format(summary.todayExpensesMinor, "RUB"), "потрачено") { openExpenses() }
                    Stat(Modifier.weight(1f), "Напомнить", "${summary.activeReminders}", "запланировано") { openPlans(1) }
                }
            }
            if (history.isEmpty()) {
                item(key = "suggest") {
                    Column {
                        Text("Попробуйте сказать", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggestions) { s -> Suggestion(s) { vm.send(s) } }
                        }
                    }
                }
            } else {
                items(history, key = { it.id }) { m -> Message(m.content, m.role == MessageRole.USER) }
            }
            reply?.let { r ->
                if (r.awaitingConfirmation || r.provider != null || (r.offline && settings.useAI)) {
                    item(key = "reply-meta") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            if (r.awaitingConfirmation) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PrimaryButton("Да, подтверждаю", { vm.confirm(true) })
                                    SecondaryButton("Отмена", { vm.confirm(false) })
                                }
                            }
                            val meta = when {
                                r.offline && settings.useAI -> "AI недоступен — выполнено на устройстве"
                                r.provider != null -> "Ответил ${r.provider}"
                                else -> null
                            }
                            meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
        }
        InputBar(
            value = input, onChange = { input = it }, name = settings.assistantName, active = active,
            onSend = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } },
            onMic = { if (active) vm.stop() else onMic() },
        )
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..16 -> "Добрый день"
    in 17..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

@Composable
private fun Stat(modifier: Modifier, title: String, value: String, caption: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = groupColor(), modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = groupColor()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 220.dp))
    }
}

@Composable
private fun Message(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (mine) {
            Text(
                text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.primary).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            Text(
                text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .background(groupColor()).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, name: String, active: Boolean, onSend: () -> Unit, onMic: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(groupColor()).padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty()) Text("Написать $name…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = value, onValueChange = onChange, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotBlank()) IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(10.dp))
        RoundButton(if (active) Icons.Rounded.Stop else Icons.Rounded.Mic, if (active) "Остановить" else "Говорить", onMic)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp)) }
    }
}
