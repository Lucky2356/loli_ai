package ai.loli.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.Persona
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.MessageRole
import ai.loli.core.model.NoteKind
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Значок Лоли: круг с «Л» — для окна и трея. */
val LoliIcon: ImageVector = ImageVector.Builder("loli", 32.dp, 32.dp, 32f, 32f).apply {
    path(fill = SolidColor(Color(0xFF5B5BD6))) {
        moveTo(16f, 1f); arcTo(15f, 15f, 0f, true, true, 15.99f, 1f); close()
    }
    path(fill = SolidColor(Color.White)) {
        moveTo(9f, 23f); lineTo(14.5f, 8f); lineTo(17.5f, 8f); lineTo(23f, 23f); lineTo(20f, 23f); lineTo(16f, 11.5f); lineTo(12f, 23f); close()
    }
}.build()

private enum class Section(val title: String, val icon: ImageVector) {
    CHAT("Лоли", Icons.Rounded.Chat), RECORDS("Записи", Icons.Rounded.Description), PLANS("Планы", Icons.Rounded.EventNote),
    MONEY("Расходы", Icons.Rounded.Payments), SETTINGS("Настройки", Icons.Rounded.Settings),
}

@Composable
fun MainWindow(c: DesktopContainer) {
    var section by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRail {
            Section.entries.forEachIndexed { i, s ->
                NavigationRailItem(selected = section == i, onClick = { section = i }, icon = { Icon(s.icon, null) }, label = { Text(s.title) })
            }
        }
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            when (Section.entries[section]) {
                Section.CHAT -> ChatPane(c)
                Section.RECORDS -> RecordsPane(c)
                Section.PLANS -> PlansPane(c)
                Section.MONEY -> MoneyPane(c)
                Section.SETTINGS -> SettingsPane(c)
            }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale("ru"))

@Composable
private fun ChatPane(c: DesktopContainer) {
    val messages by remember { c.store.conversations.observeRecent(300) }.collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val ordered = messages.sortedBy { it.createdAt }
    LaunchedEffect(ordered.size) { if (ordered.isNotEmpty()) list.animateScrollToItem(ordered.lastIndex) }
    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""; busy = true
        scope.launch { runCatching { c.engine.handle(text) }; busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ordered.isEmpty()) item {
                Text(
                    "Напишите, что сделать: «потратила 500 на кафе», «добавь задачу купить хлеб на завтра», «напомни через час позвонить маме», «итоги недели».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                )
            }
            items(ordered, key = { it.id }) { m ->
                val mine = m.role == MessageRole.USER
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 620.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(m.content)
                            Text(
                                timeFmt.format(m.createdAt.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter — отправить, Shift+Enter — новая строка.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) { send(); true } else false
                },
                placeholder = { Text(if (busy) "Думаю…" else "Напишите Лоли…") }, maxLines = 5,
            )
            IconButton(onClick = ::send, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.Send, "Отправить") }
        }
    }
}

@Composable
private fun Header(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))

@Composable
private fun RecordsPane(c: DesktopContainer) {
    val notes by remember { c.store.notes.observe(NoteKind.NOTE) }.collectAsState(emptyList())
    val ideas by remember { c.store.notes.observe(NoteKind.IDEA) }.collectAsState(emptyList())
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Заметки и идеи") }
        if (notes.isEmpty() && ideas.isEmpty()) item { Text("Пока пусто. Напишите в чате: «запиши заметку …» или «у меня идея …».") }
        items(ideas + notes, key = { it.id }) { n ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text((if (n.kind == NoteKind.IDEA) "💡 " else "") + n.title, style = MaterialTheme.typography.titleMedium)
                    if (n.content.isNotBlank()) Text(n.content.take(400), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PlansPane(c: DesktopContainer) {
    val tasks by remember { c.store.tasks.observe() }.collectAsState(emptyList())
    val reminders by remember { c.store.reminders.observe() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Header("Задачи") }
        val open = tasks.filter { !it.done }
        if (open.isEmpty()) item { Text("Задач нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(open, key = { it.id }) { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(false, { scope.launch { c.store.tasks.setDone(t.id, true) } })
                Text(t.title + (t.dueDate?.let { " — $it" + (t.dueTime?.let { tm -> " ${RuFormat.time(tm)}" } ?: "") } ?: ""))
            }
        }
        item { Spacer(Modifier.padding(8.dp)); Header("Напоминания") }
        val active = reminders.filter { it.active }.sortedBy { it.triggerAt }
        if (active.isEmpty()) item { Text("Напоминаний нет.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(active, key = { it.id }) { r ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(r.text)
                Text(
                    timeFmt.format(r.triggerAt.atZone(zone)) + (r.recurrence?.let { " · ${it.describeRu()}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MoneyPane(c: DesktopContainer) {
    val expenses by remember { c.store.expenses.observe() }.collectAsState(emptyList())
    val today = java.time.LocalDate.now()
    val month = expenses.filter { it.occurredOn.year == today.year && it.occurredOn.month == today.month && it.category != ExpenseCategories.INCOME && it.currency == "RUB" }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Header("Расходы за ${today.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale("ru"))}") }
        item { Text("Всего: " + Money.format(month.sumOf { it.amountMinor }, "RUB"), style = MaterialTheme.typography.titleMedium) }
        items(month.groupBy { it.category }.entries.sortedByDescending { e -> e.value.sumOf { it.amountMinor } }.toList(), key = { it.key }) { (cat, list) ->
            Row(Modifier.fillMaxWidth()) {
                Text(cat, Modifier.weight(1f))
                Text(Money.format(list.sumOf { it.amountMinor }, "RUB"))
            }
        }
        item { Spacer(Modifier.padding(8.dp)); Header("Последние") }
        items(expenses.sortedByDescending { it.createdAt }.take(30), key = { "e" + it.id }) { e ->
            Row(Modifier.fillMaxWidth()) {
                Text("${e.occurredOn} · ${e.category}" + (if (e.description.isNotBlank()) " · ${e.description}" else ""), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(e.amountMinor, e.currency))
            }
        }
    }
}

@Composable
private fun SettingsPane(c: DesktopContainer) {
    val s by c.settings.state.collectAsState()
    Column(Modifier.fillMaxHeight().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Настройки")
        OutlinedTextField(s.assistantName, { v -> c.settings.update { it.copy(assistantName = v.take(30)) } }, label = { Text("Имя ассистента") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.userName, { v -> c.settings.update { it.copy(userName = v.take(40)) } }, label = { Text("Как вас называть") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.city, { v -> c.settings.update { it.copy(city = v.take(60)) } }, label = { Text("Город (для погоды)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Характер", style = MaterialTheme.typography.titleMedium)
        Persona.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.persona == p.wire, { c.settings.update { it.copy(persona = p.wire) } })
                Text("${p.title} — ${p.hint}")
            }
        }
        Text("Тема", style = MaterialTheme.typography.titleMedium)
        listOf<Pair<Boolean?, String>>(null to "Как в системе", false to "Светлая", true to "Тёмная").forEach { (v, title) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.darkTheme == v, { c.settings.update { it.copy(darkTheme = v) } })
                Text(title)
            }
        }
        Text(
            "Данные хранятся на этом компьютере: ${c.dataDir.absolutePath}. Вход в аккаунт и синхронизация с телефоном появятся в следующих версиях.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
