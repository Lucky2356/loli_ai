package ai.loli.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import ai.loli.app.ui.components.UpdateCard
import ai.loli.app.ui.components.SetupCard
import ai.loli.app.ui.components.groupColor
import ai.loli.app.ui.components.pressScale
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

/** Состояние голоса для экранов Лоли: режим сферы, подпись и услышанный текст. */
private data class VoiceUi(val mode: OrbMode, val status: String, val heard: String, val level: Float) {
    val active get() = mode == OrbMode.LISTENING || mode == OrbMode.THINKING || mode == OrbMode.SPEAKING
}

private fun voiceUi(voice: VoiceState, name: String): VoiceUi {
    val (mode, status) = when (voice) {
        VoiceState.Idle -> OrbMode.IDLE to "Нажмите на сферу или скажите «$name»"
        is VoiceState.Listening -> OrbMode.LISTENING to (voice.hint ?: if (voice.followUp) "Слушаю дальше…" else "Слушаю…")
        is VoiceState.Thinking -> OrbMode.THINKING to "Думаю…"
        is VoiceState.Speaking -> OrbMode.SPEAKING to "Отвечаю… Скажите «стоп», чтобы прервать"
        is VoiceState.Error -> OrbMode.ERROR to voice.message
    }
    val heard = when (voice) {
        is VoiceState.Listening -> voice.partial
        is VoiceState.Thinking -> voice.heard
        else -> ""
    }
    return VoiceUi(mode, status, heard, (voice as? VoiceState.Listening)?.level ?: 0f)
}

/**
 * Главная: сфера, закреплённые переходы в разделы и небольшое окно чата внизу.
 * Нажатие на чат открывает его на весь экран.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onMic: () -> Unit,
    openRecords: (Int) -> Unit,
    openPlans: (Int) -> Unit,
    openExpenses: () -> Unit,
    openSettings: (String?) -> Unit,
    openHistory: () -> Unit,
    openChat: (keyboard: Boolean) -> Unit,
) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui = voiceUi(voice, settings.assistantName)
    var setupShown by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
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
        // Середина листается: обновление, шаги настройки, сфера.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val viewport = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            UpdateCard(vm.c)
            SetupCard(vm.c, settings.assistantName, onVisible = { setupShown = it })
            Box(
                Modifier.clip(CircleShape).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { if (ui.active) vm.stop() else onMic() },
            ) { AssistantOrb(ui.mode, ui.level, size = if (setupShown) 150.dp else 210.dp) }
            Text(
                ui.status, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = if (ui.mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp).clickable(enabled = ui.mode == OrbMode.ERROR) { vm.clearError() },
            )
            AnimatedVisibility(ui.heard.isNotBlank()) {
                Text(ui.heard, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
            }
        }
        }
        // Закреплённые переходы в разделы.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(Modifier.weight(1f), "Задачи", "${summary.activeTasks}", if (summary.overdueTasks > 0) "просрочено ${summary.overdueTasks}" else "активных") { openPlans(0) }
            Stat(Modifier.weight(1f), "Сегодня", Money.format(summary.todayExpensesMinor, "RUB"), "потрачено") { openExpenses() }
            Stat(Modifier.weight(1f), "Напомнить", "${summary.activeReminders}", "запланировано") { openPlans(1) }
        }
        ChatPreview(
            history = history, name = settings.assistantName, active = ui.active,
            awaitingConfirmation = reply?.awaitingConfirmation == true,
            onOpen = { openChat(false) }, onType = { openChat(true) },
            onSuggestion = { vm.send(it); openChat(false) },
            onConfirm = { vm.confirm(it) },
            onMic = { if (ui.active) vm.stop() else onMic() },
        )
    }
}

/** Небольшое окно чата на главной: последняя реплика и строка ввода. Нажатие — чат на весь экран. */
@Composable
private fun ChatPreview(
    history: List<ai.loli.core.model.ConversationMessage>,
    name: String,
    active: Boolean,
    awaitingConfirmation: Boolean,
    onOpen: () -> Unit,
    onType: () -> Unit,
    onSuggestion: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onMic: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen, interactionSource = interaction,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = groupColor(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(top = 10.dp).animateContentSize()) {
            // «Ручка» — окно можно открыть.
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)))
            val last = history.takeLast(2)
            if (last.isEmpty()) {
                Text("Попробуйте сказать", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { s -> Suggestion(s) { onSuggestion(s) } }
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    last.forEach { m ->
                        val mine = m.role == MessageRole.USER
                        Text(
                            (if (mine) "Вы: " else "$name: ") + m.content,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text("Открыть чат ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (awaitingConfirmation) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Да, подтверждаю", { onConfirm(true) })
                    SecondaryButton("Отмена", { onConfirm(false) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.background).clickable(onClick = onType).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Написать $name…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(10.dp))
                RoundButton(if (active) Icons.Rounded.Stop else Icons.Rounded.Mic, if (active) "Остановить" else "Говорить", onMic)
            }
        }
    }
}

/** Чат на весь экран: вся переписка, подтверждения, ввод текстом и голосом. */
@Composable
fun ChatScreen(vm: HomeViewModel, onMic: () -> Unit, onBack: () -> Unit, keyboard: Boolean) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val history by vm.fullHistory.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui = voiceUi(voice, settings.assistantName)
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Разговоры по дням: над каждым днём — «Сегодня», «Вчера» или дата.
    val zone = remember { java.time.ZoneId.systemDefault() }
    val byDay = remember(history) { history.groupBy { it.createdAt.atZone(zone).toLocalDate() } }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.size + byDay.size - 1) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
            AssistantOrb(ui.mode, ui.level, size = 40.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(settings.assistantName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.mode == OrbMode.IDLE) "На связи" else ui.status, style = MaterialTheme.typography.bodySmall,
                    color = if (ui.mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (history.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Здесь будет ваш разговор с ${settings.assistantName}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text("Напишите или скажите, что нужно сделать", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item(key = "suggest") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions) { s -> Suggestion(s) { vm.send(s) } }
                    }
                }
            }
            byDay.forEach { (day, messages) ->
                item(key = "day-$day") { DayHeader(day) }
                items(messages, key = { it.id }) { m ->
                    val time = remember(m.id) { "%02d:%02d".format(m.createdAt.atZone(zone).hour, m.createdAt.atZone(zone).minute) }
                    Box(Modifier.animateItem()) { Message(m.content, m.role == MessageRole.USER, time) }
                }
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
                                r.offline && settings.useAI -> "AI не ответил${r.aiError?.let { " ($it)" } ?: ""} — выполнено на устройстве. Подробности: Настройки → AI"
                                r.provider != null -> "Ответил ${r.provider}"
                                else -> null
                            }
                            meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(ui.heard.isNotBlank()) {
            Text(ui.heard, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp))
        }
        InputBar(
            value = input, onChange = { input = it }, name = settings.assistantName, active = ui.active, autoFocus = keyboard,
            onSend = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } },
            onMic = { if (ui.active) vm.stop() else onMic() },
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
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = groupColor(), interactionSource = interaction, modifier = modifier.pressScale(interaction)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Число меняется «перелистыванием» снизу вверх.
            AnimatedContent(
                targetState = value,
                transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                label = "stat",
            ) { v -> Text(v, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = groupColor(), interactionSource = interaction, modifier = Modifier.pressScale(interaction)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 220.dp))
    }
}

/** Подпись дня над сообщениями: «Сегодня», «Вчера», «Пятница, 12 сентября». */
@Composable
private fun DayHeader(day: java.time.LocalDate) {
    val today = java.time.LocalDate.now()
    val ru = java.util.Locale("ru")
    val text = when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> {
            val pattern = if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"
            java.time.format.DateTimeFormatter.ofPattern(pattern, ru).format(day).replaceFirstChar { it.uppercase() }
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(groupColor()).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Message(text: String, mine: Boolean, time: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
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
        time?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, name: String, active: Boolean, autoFocus: Boolean = false, onSend: () -> Unit, onMic: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            kotlinx.coroutines.delay(250) // окно чата ещё выезжает
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
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
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction, modifier = Modifier.size(52.dp).pressScale(interaction),
    ) {
        // Микрофон ↔ стоп — плавная смена значка.
        Crossfade(targetState = icon, label = "mic", modifier = Modifier.fillMaxSize()) { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(i, contentDescription = description, modifier = Modifier.size(24.dp)) }
        }
    }
}
