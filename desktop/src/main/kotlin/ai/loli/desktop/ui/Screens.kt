package ai.loli.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Wallet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.QuickAdd
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Expense
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.model.Reminder
import ai.loli.core.model.TaskItem
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Общая разметка страницы: отступы и ограничение ширины, чтобы на широком мониторе не растекалось. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) { content() }
    }
}

/**
 * Строка быстрого добавления: фраза → запись, без захода в чат. Enter или кнопка «Добавить».
 * Результат (или подсказка, если не поняла) показывается всплывающим сообщением.
 */
@Composable
private fun QuickAddBar(
    c: DesktopContainer, kinds: List<Pair<QuickAdd.Kind, String>>, placeholders: Map<QuickAdd.Kind, String>,
    onResult: (Pair<Boolean, String>) -> Unit, modifier: Modifier = Modifier,
) {
    var kind by remember { mutableStateOf(kinds.first().first) }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (text.isBlank() || saving) return
        saving = true
        scope.launch {
            val r = c.quickAdd(kind, text)
            if (r.first) text = ""
            saving = false
            onResult(r)
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (kinds.size > 1) Segmented(kinds, kind, { kind = it })
        Field(text, { text = it }, null, placeholders[kind].orEmpty(), modifier = Modifier.weight(1f), onSubmit = ::submit)
        AccentButton(if (saving) "Сохраняю…" else "Добавить", ::submit, enabled = text.isNotBlank() && !saving, icon = Icons.Rounded.Add)
    }
}

/** Экран с всплывающим сообщением внизу. */
@Composable
private fun WithToast(content: @Composable (show: (Pair<Boolean, String>) -> Unit) -> Unit) {
    var toast by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Box(Modifier.fillMaxSize()) {
        content { toast = it }
        Toast(toast, { toast = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

// ---------------------------------------------------------------- Записи

@Composable
fun RecordsScreen(c: DesktopContainer) {
    val p = palette
    val notes by remember { c.store.notes.observe(NoteKind.NOTE) }.collectAsState(emptyList())
    val ideas by remember { c.store.notes.observe(NoteKind.IDEA) }.collectAsState(emptyList())
    var filter by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    val scope = rememberCoroutineScope()
    val shown = remember(notes, ideas, filter, query) {
        val all = when (filter) { 1 -> notes; 2 -> ideas; else -> (notes + ideas).sortedByDescending { it.updatedAt } }
        if (query.isBlank()) all else all.filter { it.title.contains(query, true) || it.content.contains(query, true) }
    }
    val grid = rememberLazyGridState()
    WithToast { toast ->
        Page {
            LazyVerticalGrid(
                GridCells.Adaptive(300.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        PageHeader("Записи", "${RuFormat.count(notes.size, "заметка", "заметки", "заметок")} · ${RuFormat.count(ideas.size, "идея", "идеи", "идей")}") {
                            AccentButton("Новая запись", { creating = true }, icon = Icons.Rounded.Add)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Field(query, { query = it }, null, "Найти в заметках и идеях", modifier = Modifier.weight(1f), trailing = { Icon(Icons.Rounded.Search, null, tint = p.faint, modifier = Modifier.size(18.dp)) })
                            Box(Modifier.padding(start = 14.dp)) {
                                Segmented(listOf(0 to "Все · ${notes.size + ideas.size}", 1 to "Заметки · ${notes.size}", 2 to "Идеи · ${ideas.size}"), filter, { filter = it })
                            }
                        }
                    }
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        Icons.Rounded.StickyNote2, if (query.isBlank()) "Пока пусто" else "Ничего не нашла",
                        if (query.isBlank()) "Нажмите «Новая запись» или скажите в чате: «запиши заметку …»" else "Попробуйте другое слово.",
                    )
                }
                items(shown, key = { it.id }) { n -> NoteCard(n, onOpen = { editing = n }, onDelete = { deleting = n }) }
            }
        }
        if (creating || editing != null) NoteEditor(
            editing,
            onSave = { kind, title, content ->
                val e = editing
                scope.launch {
                    runCatching {
                        if (e == null) c.store.notes.create(kind, title, content) else c.store.notes.update(e.copy(kind = kind, title = title, content = content))
                    }.onSuccess { toast(true to if (e == null) "Записала." else "Сохранила.") }
                        .onFailure { toast(false to "Не получилось сохранить: ${it.message}") }
                }
            },
            onDismiss = { creating = false; editing = null },
        )
        deleting?.let { n ->
            ConfirmDialog(
                "Удалить запись?", "«${n.title.ifBlank { n.content.take(40) }}» исчезнет из записей.", "Удалить",
                onConfirm = { scope.launch { c.store.notes.delete(n.id); toast(true to "Удалила.") } }, onDismiss = { deleting = null },
            )
        }
    }
}

@Composable
private fun NoteEditor(note: Note?, onSave: (NoteKind, String, String) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(note?.kind ?: NoteKind.NOTE) }
    var title by remember { mutableStateOf(note?.title.orEmpty()) }
    var content by remember { mutableStateOf(note?.content.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canSave = title.isNotBlank() || content.isNotBlank()
    fun save() {
        if (!canSave) return
        val t = title.trim().ifBlank { content.trim().lineSequence().first().take(60) }
        onSave(kind, t, content.trim())
        onDismiss()
    }
    LoliDialog(if (note == null) "Новая запись" else "Запись", onDismiss, width = 600.dp) {
        Segmented(listOf(NoteKind.NOTE to "Заметка", NoteKind.IDEA to "Идея"), kind, { kind = it })
        Field(title, { title = it }, "Название", "Например, пароль от Wi-Fi на даче", modifier = Modifier.padding(top = 16.dp), focusRequester = focus)
        Field(content, { content = it }, "Текст", "Что записать", lines = 8, onSubmit = ::save, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ctrl+Enter — сохранить", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            GhostButton("Отмена", onDismiss)
            Box(Modifier.width(10.dp))
            AccentButton("Сохранить", ::save, enabled = canSave)
        }
    }
}

@Composable
private fun NoteCard(n: Note, onOpen: () -> Unit, onDelete: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val idea = n.kind == NoteKind.IDEA
    Column(
        Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(16.dp)).background(if (hovered) p.surfaceHigh else p.surface)
            .border(1.dp, if (hovered) p.accent.copy(alpha = 0.4f) else p.outline, RoundedCornerShape(16.dp)).hoverable(src).clickable(onClick = onOpen).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
            Icon(if (idea) Icons.Rounded.Lightbulb else Icons.Rounded.StickyNote2, null, tint = if (idea) p.warning else p.accent, modifier = Modifier.size(18.dp))
            Text(
                n.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (hovered) IconCircle(Icons.Rounded.DeleteOutline, "Удалить", onDelete, size = 28.dp)
        }
        Text(
            n.content, style = MaterialTheme.typography.bodyMedium.copy(color = p.muted), maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(top = 10.dp),
        )
        Text(RuFormat.date(n.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()).removePrefix("в "), style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------- Планы

@Composable
fun PlansScreen(c: DesktopContainer) {
    val p = palette
    val tasks by remember { c.store.tasks.observe() }.collectAsState(emptyList())
    val reminders by remember { c.store.reminders.observe() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var deletingTask by remember { mutableStateOf<TaskItem?>(null) }
    var deletingReminder by remember { mutableStateOf<Reminder?>(null) }
    val today = LocalDate.now()
    val now = LocalTime.now()
    val zone = ZoneId.systemDefault()
    // Сначала со сроком (ближайшие выше), задачи без срока — в конце.
    val open = remember(tasks) {
        tasks.filter { !it.done }.sortedWith(compareBy<TaskItem> { it.dueDate == null }.thenBy { it.dueDate?.toEpochDay() ?: 0L }.thenBy { it.dueTime })
    }
    val done = remember(tasks) { tasks.filter { it.done }.sortedByDescending { it.completedAt }.take(8) }
    val active = remember(reminders) { reminders.filter { it.active }.sortedBy { it.triggerAt } }
    WithToast { toast ->
        Page {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item { PageHeader("Планы", "${RuFormat.count(open.size, "задача", "задачи", "задач")} · ${RuFormat.count(active.size, "напоминание", "напоминания", "напоминаний")}") }
                item {
                    QuickAddBar(
                        c, listOf(QuickAdd.Kind.TASK to "Задача", QuickAdd.Kind.REMINDER to "Напоминание"),
                        mapOf(
                            QuickAdd.Kind.TASK to "Купить хлеб завтра · созвон в пятницу в 15:00",
                            QuickAdd.Kind.REMINDER to "Завтра в 9 позвонить маме · через 20 минут выключить духовку",
                        ),
                        toast, Modifier.padding(bottom = 18.dp),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Panel(Modifier.weight(1.2f)) {
                            SectionLabel("Задачи")
                            if (open.isEmpty()) Text("Задач нет. Добавьте выше или скажите: «добавь задачу купить хлеб на завтра».", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                            open.forEachIndexed { i, t ->
                                if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                TaskRow(t, today, now, onToggle = { scope.launch { c.store.tasks.setDone(t.id, true) } }, onDelete = { deletingTask = t })
                            }
                            if (done.isNotEmpty()) {
                                SectionLabel("Сделано", Modifier.padding(top = 18.dp))
                                done.forEach { t ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CheckCircle(true) { scope.launch { c.store.tasks.setDone(t.id, false) } }
                                        Text(t.title, style = MaterialTheme.typography.bodyMedium.copy(color = p.faint), modifier = Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                        }
                        Panel(Modifier.weight(1f)) {
                            SectionLabel("Напоминания")
                            if (active.isEmpty()) Text("Напоминаний нет. Добавьте выше или скажите: «напомни через час позвонить маме».", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                            active.forEachIndexed { i, r ->
                                if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                        Icon(if (r.recurrence != null) Icons.Rounded.Repeat else Icons.Rounded.NotificationsNone, null, tint = p.accent, modifier = Modifier.size(17.dp))
                                    }
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            RuFormat.dateTime(r.triggerAt, zone, java.time.Instant.now()) + (r.recurrence?.let { " · ${it.describeRu()}" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconCircle(Icons.Rounded.DeleteOutline, "Удалить", { deletingReminder = r }, size = 28.dp)
                                }
                            }
                            Text(
                                "Напоминания приходят уведомлением, пока Лоли запущена — закрытое окно остаётся в трее. " +
                                    "Чтобы ничего не пропустить, включите «Запускать вместе с Windows» в настройках.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                }
            }
        }
        deletingTask?.let { t ->
            ConfirmDialog("Удалить задачу?", "«${t.title}»", "Удалить", onConfirm = { scope.launch { c.store.tasks.delete(t.id); toast(true to "Удалила задачу.") } }, onDismiss = { deletingTask = null })
        }
        deletingReminder?.let { r ->
            ConfirmDialog(
                "Удалить напоминание?", "«${r.text}»" + (if (r.recurrence != null) " — повторяющееся, удалятся все следующие." else ""), "Удалить",
                onConfirm = { scope.launch { c.store.reminders.delete(r.id); toast(true to "Удалила напоминание.") } }, onDismiss = { deletingReminder = null },
            )
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, today: LocalDate, now: LocalTime, onToggle: () -> Unit, onDelete: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val overdue = t.isOverdue(today, now)
    Row(Modifier.fillMaxWidth().hoverable(src).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckCircle(false, onToggle)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyMedium)
            if (t.details.isNotBlank()) Text(t.details, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        t.dueDate?.let { d ->
            Tag(
                RuFormat.date(d, today).removePrefix("в ") + (t.dueTime?.let { " ${RuFormat.time(it)}" } ?: ""),
                if (overdue) p.danger else if (d == today) p.accent else null,
            )
        }
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) {
            if (hovered) IconCircle(Icons.Rounded.DeleteOutline, "Удалить", onDelete, size = 28.dp)
        }
    }
}

@Composable
private fun CheckCircle(checked: Boolean, onClick: () -> Unit) {
    val p = palette
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(if (checked) p.gradient else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
            .border(1.5.dp, if (checked) Color.Transparent else p.faint, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
}

// ---------------------------------------------------------------- Финансы

@Composable
fun MoneyScreen(c: DesktopContainer) {
    val p = palette
    val expenses by remember { c.store.expenses.observe() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Expense?>(null) }
    val today = LocalDate.now()
    val stats = remember(expenses, today) { MoneyStats.of(expenses, today) }
    val monthSum = stats.monthSum
    val prevSum = stats.prevSum
    val todaySum = stats.todaySum
    val byCat = stats.byCategory
    val monthName = today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() }
    WithToast { toast ->
        Page {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item { PageHeader("Финансы", "$monthName ${today.year} · суммы в рублях") }
                item {
                    QuickAddBar(
                        c, listOf(QuickAdd.Kind.EXPENSE to "Трата"), mapOf(QuickAdd.Kind.EXPENSE to "Кофе 250 · такси 640 вчера · зарплата 80000"),
                        toast, Modifier.padding(bottom = 18.dp),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                        StatCard("За месяц", Money.format(monthSum, "RUB"), Modifier.weight(1f),
                            when {
                                prevSum == 0L -> null
                                monthSum > prevSum -> "больше, чем в прошлом месяце к этому дню (${Money.format(prevSum, "RUB")})" to p.warning
                                else -> "меньше, чем в прошлом месяце к этому дню (${Money.format(prevSum, "RUB")})" to p.success
                            })
                        StatCard("Сегодня", Money.format(todaySum, "RUB"), Modifier.weight(1f), null)
                        StatCard("Больше всего", byCat.firstOrNull()?.key ?: "—", Modifier.weight(1f), byCat.firstOrNull()?.let { Money.format(it.value, "RUB") to p.muted })
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Panel(Modifier.weight(1f)) {
                            SectionLabel("По категориям")
                            if (byCat.isEmpty()) Text("Трат в этом месяце нет. Скажите: «потратила 500 на кафе».", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                            val max = byCat.maxOfOrNull { it.value }?.toFloat() ?: 1f
                            byCat.forEach { (cat, sum) ->
                                Column(Modifier.padding(vertical = 7.dp)) {
                                    Row {
                                        Text(cat, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Text(Money.format(sum, "RUB"), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.surfaceHigh)) {
                                        Box(Modifier.fillMaxWidth(sum / max).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.gradient))
                                    }
                                }
                            }
                        }
                        Panel(Modifier.weight(1f)) {
                            SectionLabel("Последние траты")
                            val recent = stats.recent
                            if (recent.isEmpty()) Text("Здесь появятся траты.", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                            recent.forEachIndexed { i, e ->
                                if (i > 0) Divider()
                                val src = remember(e.id) { MutableInteractionSource() }
                                val hovered by src.collectIsHoveredAsState()
                                Row(Modifier.fillMaxWidth().hoverable(src).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.surfaceHigh), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Wallet, null, tint = p.muted, modifier = Modifier.size(16.dp))
                                    }
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(e.category, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            RuFormat.date(e.occurredOn, today).removePrefix("в ") + (if (e.description.isNotBlank()) " · ${e.description}" else ""),
                                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        (if (e.category == ExpenseCategories.INCOME) "+" else "−") + Money.format(e.amountMinor, e.currency),
                                        style = MaterialTheme.typography.titleSmall.copy(color = if (e.category == ExpenseCategories.INCOME) p.success else p.text),
                                    )
                                    Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) {
                                        if (hovered) IconCircle(Icons.Rounded.DeleteOutline, "Удалить", { deleting = e }, size = 28.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        deleting?.let { e ->
            ConfirmDialog(
                "Удалить трату?", "${e.category} · ${Money.format(e.amountMinor, e.currency)}", "Удалить",
                onConfirm = { scope.launch { c.store.expenses.delete(e.id); toast(true to "Удалила.") } }, onDismiss = { deleting = null },
            )
        }
    }
}

/** Суммы для экрана финансов — считаются один раз на изменение списка трат. */
private class MoneyStats(
    val monthSum: Long, val prevSum: Long, val todaySum: Long,
    val byCategory: List<Map.Entry<String, Long>>, val recent: List<Expense>,
) {
    companion object {
        fun of(expenses: List<Expense>, today: LocalDate): MoneyStats {
            val rub = expenses.filter { it.category != ExpenseCategories.INCOME && it.currency == "RUB" }
            val month = rub.filter { it.occurredOn.year == today.year && it.occurredOn.month == today.month }
            val prevMonthDate = today.minusMonths(1)
            val prev = rub.filter { it.occurredOn.year == prevMonthDate.year && it.occurredOn.month == prevMonthDate.month && it.occurredOn.dayOfMonth <= today.dayOfMonth }
            return MoneyStats(
                monthSum = month.sumOf { it.amountMinor },
                prevSum = prev.sumOf { it.amountMinor },
                todaySum = rub.filter { it.occurredOn == today }.sumOf { it.amountMinor },
                byCategory = month.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }.entries.sortedByDescending { it.value },
                recent = expenses.sortedByDescending { it.createdAt }.take(12),
            )
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier, note: Pair<String, Color>?) {
    Panel(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (note != null) Text(note.first, style = MaterialTheme.typography.bodySmall.copy(color = note.second), modifier = Modifier.padding(top = 4.dp), maxLines = 2)
    }
}
