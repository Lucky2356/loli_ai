package ai.loli.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.ConversationMessage
import ai.loli.core.model.MessageRole
import ai.loli.desktop.DesktopContainer
import ai.loli.desktop.voice.VoiceController
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private data class Suggestion(val icon: ImageVector, val title: String, val phrase: String)

private val SUGGESTIONS = listOf(
    Suggestion(Icons.Rounded.Today, "План на сегодня", "Что у меня на сегодня"),
    Suggestion(Icons.Rounded.AccountBalanceWallet, "Записать расход", "Потратила 500 на кафе"),
    Suggestion(Icons.Rounded.Alarm, "Напоминание", "Напомни через час позвонить маме"),
    Suggestion(Icons.Rounded.TaskAlt, "Новая задача", "Добавь задачу купить хлеб на завтра"),
    Suggestion(Icons.Rounded.WbSunny, "Погода", "Какая погода завтра"),
    Suggestion(Icons.Rounded.Insights, "Итоги недели", "Итоги недели"),
)

@Composable
fun ChatScreen(c: DesktopContainer, openSettings: () -> Unit) {
    val p = palette
    val messages by remember { c.store.conversations.observeRecent(400) }.collectAsState(emptyList())
    val busy by c.busy.collectAsState()
    val error by c.error.collectAsState()
    val settings by c.settings.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // Подписи дней считаются один раз на новый список, а не при каждой перерисовке.
    val rows = remember(messages) {
        val zone = ZoneId.systemDefault()
        var lastDay: LocalDate? = null
        messages.sortedBy { it.createdAt }.map { m ->
            val day = m.createdAt.atZone(zone).toLocalDate()
            val header = if (day != lastDay) day else null
            lastDay = day
            header to m
        }
    }
    // Новое сообщение или «печатает…» — прокрутка вниз.
    LaunchedEffect(rows.lastOrNull()?.second?.id, busy) {
        val last = rows.size - 1 + (if (busy) 1 else 0)
        if (last >= 0) list.animateScrollToItem(last)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun send(text: String = input) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        scope.launch { c.send(t) }
    }

    Column(Modifier.fillMaxSize()) {
        // Шапка
        val cfg = remember(settings) { c.aiConfig(settings) }
        val ai = settings.aiEnabled && cfg.isComplete
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Чат", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (ai) "Облачный AI · ${cfg.model}" else "Работаю без интернета · подключите AI в настройках",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!ai) GhostButton("Подключить AI", openSettings)
            if (rows.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                IconCircle(Icons.Rounded.DeleteSweep, "Очистить чат", { confirmClear = true }, size = 38.dp)
            }
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Welcome(settings.userName, settings.assistantName, c.voice.state) { send(it) }
            } else {
                LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
                ) {
                    items(rows, key = { it.second.id }) { (header, m) ->
                        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 32.dp)) {
                            if (header != null) DayChip(header)
                            MessageRow(m)
                        }
                    }
                    if (busy) item(key = "typing") {
                        Box(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 32.dp)) { Typing() }
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp),
                    style = scrollbarStyle(),
                )
            }
        }
        error?.let { e ->
            Row(
                Modifier.padding(horizontal = 32.dp).widthIn(max = 860.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(12.dp)).background(p.danger.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e, style = MaterialTheme.typography.bodySmall.copy(color = p.danger), modifier = Modifier.weight(1f))
                IconCircle(Icons.Rounded.Close, "Скрыть", { c.dismissError() }, size = 26.dp)
            }
        }
        Composer(input, { input = it }, ::send, c.voice.state, busy, focus, onMic = { c.voice.toggle() })
    }
    if (confirmClear) ConfirmDialog(
        "Очистить чат?", "История разговора на этом компьютере удалится. Заметки, задачи, напоминания и траты останутся.", "Очистить",
        onConfirm = { scope.launch { c.clearChat() } }, onDismiss = { confirmClear = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(userName: String, assistant: String, voiceState: StateFlow<VoiceController.State>, onPick: (String) -> Unit) {
    val voice = voiceState.collectAsState().value
    val hour = LocalTime.now().hour
    val greeting = when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" }
    val listening = voice is VoiceController.State.Listening || voice is VoiceController.State.Speaking
    val level = (voice as? VoiceController.State.Listening)?.level ?: 0f
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Orb(120.dp, active = listening, level = level)
        Spacer(Modifier.height(20.dp))
        Text(greeting + (if (userName.isNotBlank()) ", $userName" else ""), style = MaterialTheme.typography.displaySmall)
        Text(
            "Я $assistant. Напишите или скажите — запишу, напомню, посчитаю.",
            style = MaterialTheme.typography.bodyLarge.copy(color = palette.muted), modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(32.dp))
        FlowRow(
            Modifier.widthIn(max = 760.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 3,
        ) {
            SUGGESTIONS.forEach { s -> SuggestionCard(s) { onPick(s.phrase) } }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, onClick: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Column(
        Modifier.width(236.dp).clip(RoundedCornerShape(16.dp))
            .background(if (hovered) p.surfaceHigh else p.surface)
            .border(1.dp, if (hovered) p.accent.copy(alpha = 0.5f) else p.outline, RoundedCornerShape(16.dp))
            .hoverable(src).clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(s.icon, null, tint = p.accent, modifier = Modifier.size(18.dp))
        }
        Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text("«${s.phrase}»", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun DayChip(day: LocalDate) {
    val today = LocalDate.now()
    val text = when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> RuFormat.date(day, today).removePrefix("в ").replaceFirstChar { it.uppercase() }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) { Tag(text) }
}

@Composable
private fun MessageRow(m: ConversationMessage) {
    val p = palette
    val mine = m.role == MessageRole.USER
    val time = RuFormat.time(m.createdAt.atZone(ZoneId.systemDefault()).toLocalTime())
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Row(Modifier.fillMaxWidth().hoverable(src).padding(vertical = 6.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) { Orb(30.dp, modifier = Modifier.padding(top = 2.dp)); Spacer(Modifier.width(12.dp)) }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 640.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 6.dp, bottomEnd = if (mine) 6.dp else 18.dp))
                    .background(if (mine) p.userBubble else p.surface)
                    .then(if (mine) Modifier else Modifier.border(1.dp, p.outline, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                SelectionContainer { Text(m.content, style = MaterialTheme.typography.bodyLarge) }
            }
            Row(Modifier.height(24.dp).padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelSmall)
                if (hovered || copied) Text(
                    if (copied) "Скопировано" else "Копировать",
                    style = MaterialTheme.typography.labelSmall.copy(color = if (copied) p.success else p.accent),
                    modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(4.dp)).clickable {
                        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(m.content), null) }
                        copied = true
                    },
                )
            }
        }
    }
}

@Composable
private fun Typing() {
    val p = palette
    val t = rememberInfiniteTransition()
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Orb(30.dp, active = true)
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(p.surface).border(1.dp, p.outline, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(3) { i ->
                val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Reverse))
                Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(p.muted))
            }
        }
    }
}

@Composable
private fun Composer(
    value: String, onChange: (String) -> Unit, onSend: (String) -> Unit, voiceState: StateFlow<VoiceController.State>, busy: Boolean,
    focus: FocusRequester, onMic: () -> Unit,
) {
    val p = palette
    // Состояние голоса меняется 10 раз в секунду, пока слушаем, — перерисовывается только поле ввода, не весь чат.
    val voice = voiceState.collectAsState().value
    val listening = voice is VoiceController.State.Listening
    val speaking = voice is VoiceController.State.Speaking
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Строка состояния голоса над полем ввода.
        AnimatedVisibility(voice !is VoiceController.State.Idle, enter = fadeIn(), exit = fadeOut()) {
            val text = when (voice) {
                is VoiceController.State.Listening -> voice.partial.ifBlank { "Слушаю… говорите" }
                is VoiceController.State.Thinking -> "«${voice.heard}»"
                is VoiceController.State.Speaking -> "Говорю… Esc — замолчать"
                is VoiceController.State.Preparing -> voice.percent?.let { "Скачиваю распознавание речи… $it% · нажмите ещё раз, чтобы отменить" } ?: "Загружаю распознавание речи…"
                is VoiceController.State.Error -> voice.message
                VoiceController.State.Idle -> ""
            }
            Row(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (listening) Orb(22.dp, active = true, level = (voice as VoiceController.State.Listening).level)
                Text(
                    text, style = MaterialTheme.typography.bodyMedium.copy(color = if (voice is VoiceController.State.Error) p.danger else p.muted),
                    modifier = Modifier.padding(start = 10.dp), maxLines = 2,
                )
            }
        }
        Row(
            Modifier.widthIn(max = 860.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)
                .border(1.dp, if (listening) p.accent else p.outline, RoundedCornerShape(20.dp)).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(if (busy) "Лоли думает…" else "Напишите Лоли или нажмите на микрофон", style = MaterialTheme.typography.bodyLarge.copy(color = p.faint))
                BasicTextField(
                    value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text), cursorBrush = SolidColor(p.accent), maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        // Enter — отправить, Shift+Enter — новая строка.
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) { onSend(value); true } else false
                    },
                )
            }
            val preparing = voice is VoiceController.State.Preparing
            IconCircle(
                if (listening || speaking || preparing) Icons.Rounded.Stop else Icons.Rounded.Mic, if (listening || speaking || preparing) "Остановить" else "Сказать голосом", onMic, size = 40.dp,
                tint = if (listening) Color.White else p.muted, background = if (listening) p.danger else null,
            )
            Spacer(Modifier.width(6.dp))
            val canSend = value.isNotBlank() && !busy
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (canSend) p.gradient else SolidColor(p.surfaceHover)).clickable(enabled = canSend) { onSend(value) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Отправить", tint = if (canSend) Color.White else p.faint, modifier = Modifier.size(18.dp)) }
        }
        Text(
            "Enter — отправить · Shift+Enter — новая строка · Ctrl+Пробел — голос · Ctrl+1…5 — разделы",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
