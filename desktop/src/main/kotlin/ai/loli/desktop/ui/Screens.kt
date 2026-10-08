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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
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

// ---------------------------------------------------------------- Записи

@Composable
fun RecordsScreen(c: DesktopContainer) {
    val p = palette
    val notes by remember { c.store.notes.observe(NoteKind.NOTE) }.collectAsState(emptyList())
    val ideas by remember { c.store.notes.observe(NoteKind.IDEA) }.collectAsState(emptyList())
    var filter by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val all = when (filter) { 1 -> notes; 2 -> ideas; else -> (notes + ideas).sortedByDescending { it.updatedAt } }
    val shown = if (query.isBlank()) all else all.filter { it.title.contains(query, true) || it.content.contains(query, true) }
    Page {
        LazyVerticalGrid(GridCells.Adaptive(300.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    PageHeader("Записи", "${RuFormat.count(notes.size, "заметка", "заметки", "заметок")} · ${RuFormat.count(ideas.size, "идея", "идеи", "идей")}")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        Field(query, { query = it }, "Поиск", "Найти в заметках и идеях", modifier = Modifier.weight(1f), trailing = { Icon(Icons.Rounded.Search, null, tint = p.faint, modifier = Modifier.size(18.dp)) })
                        Box(Modifier.padding(start = 14.dp, top = 22.dp)) { Segmented(listOf(0 to "Все", 1 to "Заметки", 2 to "Идеи"), filter, { filter = it }) }
                    }
                }
            }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Rounded.StickyNote2, if (query.isBlank()) "Пока пусто" else "Ничего не нашла", "Скажите или напишите в чате: «запиши заметку …» или «у меня идея …»")
            }
            items(shown, key = { it.id }) { n -> NoteCard(n) { scope.launch { c.store.notes.delete(n.id) } } }
        }
    }
}

@Composable
private fun NoteCard(n: Note, onDelete: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val idea = n.kind == NoteKind.IDEA
    Column(
        Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(16.dp)).background(if (hovered) p.surfaceHigh else p.surface)
            .border(1.dp, p.outline, RoundedCornerShape(16.dp)).hoverable(src).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
    val today = LocalDate.now()
    val now = LocalTime.now()
    val zone = ZoneId.systemDefault()
    // Сначала со сроком (ближайшие выше), задачи без срока — в конце.
    val open = tasks.filter { !it.done }.sortedWith(compareBy<ai.loli.core.model.TaskItem> { it.dueDate == null }.thenBy { it.dueDate?.toEpochDay() ?: 0L })
    val done = tasks.filter { it.done }.sortedByDescending { it.completedAt }.take(8)
    val active = reminders.filter { it.active }.sortedBy { it.triggerAt }
    Page {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PageHeader("Планы", "${RuFormat.count(open.size, "задача", "задачи", "задач")} · ${RuFormat.count(active.size, "напоминание", "напоминания", "напоминаний")}") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Panel(Modifier.weight(1.2f)) {
                        SectionLabel("Задачи")
                        if (open.isEmpty()) Text("Задач нет. Скажите: «добавь задачу купить хлеб на завтра».", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                        open.forEachIndexed { i, t ->
                            if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                            val overdue = t.isOverdue(today, now)
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckCircle(false) { scope.launch { c.store.tasks.setDone(t.id, true) } }
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
                            }
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
                        if (active.isEmpty()) Text("Напоминаний нет. Скажите: «напомни через час позвонить маме».", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                        active.forEachIndexed { i, r ->
                            if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.NotificationsNone, null, tint = p.accent, modifier = Modifier.size(17.dp))
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(r.text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        RuFormat.dateTime(r.triggerAt, zone, java.time.Instant.now()) + (r.recurrence?.let { " · ${it.describeRu()}" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                IconCircle(Icons.Rounded.DeleteOutline, "Удалить", { scope.launch { c.store.reminders.delete(r.id) } }, size = 28.dp)
                            }
                        }
                        Text(
                            "Напоминания приходят уведомлением, пока Лоли запущена — закрытое окно остаётся в трее.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }
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
    val today = LocalDate.now()
    val rub = expenses.filter { it.category != ExpenseCategories.INCOME && it.currency == "RUB" }
    val month = rub.filter { it.occurredOn.year == today.year && it.occurredOn.month == today.month }
    val prevMonthDate = today.minusMonths(1)
    val prev = rub.filter { it.occurredOn.year == prevMonthDate.year && it.occurredOn.month == prevMonthDate.month && it.occurredOn.dayOfMonth <= today.dayOfMonth }
    val todaySum = rub.filter { it.occurredOn == today }.sumOf { it.amountMinor }
    val monthSum = month.sumOf { it.amountMinor }
    val prevSum = prev.sumOf { it.amountMinor }
    val byCat = month.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }.entries.sortedByDescending { it.value }
    val monthName = today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() }
    Page {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PageHeader("Финансы", "$monthName ${today.year}") }
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
                        val recent = expenses.sortedByDescending { it.createdAt }.take(12)
                        if (recent.isEmpty()) Text("Здесь появятся траты.", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                        recent.forEachIndexed { i, e ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            }
                        }
                    }
                }
            }
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
