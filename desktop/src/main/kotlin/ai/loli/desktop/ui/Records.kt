package ai.loli.desktop.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.RuFormat
import ai.loli.core.health.HabitEntry
import ai.loli.core.health.Vitals
import ai.loli.core.model.MemoryItem
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.search.SearchHit
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Категории памяти — те же, что на телефоне. */
private val MEMORY_CATEGORIES = listOf("other" to "Разное", "preference" to "Предпочтение", "fact" to "Факт", "goal" to "Цель", "person" to "Люди")
private fun categoryRu(c: String) = MEMORY_CATEGORIES.firstOrNull { it.first == c }?.second ?: c.replaceFirstChar { it.uppercase() }

/** Вкладка «Записи» — как на телефоне: заметки, идеи, память о вас, здоровье и поиск по всему. */
@Composable
fun RecordsScreen(c: DesktopContainer) {
    val p = palette
    val notes by remember { c.store.notes.observe(NoteKind.NOTE) }.collectAsState(emptyList())
    val ideas by remember { c.store.notes.observe(NoteKind.IDEA) }.collectAsState(emptyList())
    val memories by remember { c.store.memories.observe() }.collectAsState(emptyList())
    val name = c.settings.state.collectAsState().value.assistantName
    var segment by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    var memory by remember { mutableStateOf<MemoryItem?>(null) }
    var newMemory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        hits = runCatching { c.search.search(query, limit = 40, minScore = 0.2) }.getOrDefault(emptyList())
    }
    val list = if (segment == 1) ideas else notes
    WithToast { toast ->
        Page {
            LazyVerticalGrid(
                GridCells.Adaptive(300.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        PageHeader(
                            "Записи",
                            "${RuFormat.count(notes.size, "заметка", "заметки", "заметок")} · ${RuFormat.count(ideas.size, "идея", "идеи", "идей")} · ${memories.size} в памяти",
                        ) {
                            when (segment) {
                                0, 1 -> AccentButton(if (segment == 1) "Новая идея" else "Новая заметка", { creating = true }, icon = Icons.Rounded.Add)
                                2 -> AccentButton("Запомнить", { newMemory = true }, icon = Icons.Rounded.Add)
                                else -> Unit
                            }
                        }
                        Field(
                            query, { query = it }, null, "Найти во всех записях: заметки, идеи, задачи, память",
                            trailing = { Icon(Icons.Rounded.Search, null, tint = p.faint, modifier = Modifier.size(18.dp)) },
                        )
                        if (query.isBlank()) {
                            Segmented(
                                listOf(0 to "Заметки · ${notes.size}", 1 to "Идеи · ${ideas.size}", 2 to "Память · ${memories.size}", 3 to "Здоровье"),
                                segment, { segment = it }, Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                if (query.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Panel {
                            SectionLabel(if (hits.isEmpty()) "Ничего не нашла" else "Найдено: ${hits.size}")
                            hits.forEachIndexed { i, h ->
                                if (i > 0) Divider(Modifier.padding(vertical = 4.dp))
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(h.doc.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(h.doc.type.titleRu, h.doc.body.take(160)).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                } else when (segment) {
                    0, 1 -> {
                        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                if (segment == 1) Icons.Rounded.Lightbulb else Icons.AutoMirrored.Rounded.StickyNote2,
                                if (segment == 1) "Идей пока нет" else "Заметок пока нет",
                                if (segment == 1) "Нажмите «Новая идея» или скажите: «$name, у меня идея — …»" else "Нажмите «Новая заметка» или скажите: «запиши заметку …»",
                            )
                        }
                        items(list, key = { it.id }) { n -> NoteCard(n, onOpen = { editing = n }, onDelete = { deleting = n }) }
                    }
                    2 -> item(span = { GridItemSpan(maxLineSpan) }) {
                        if (memories.isEmpty()) {
                            EmptyState(Icons.Rounded.Psychology, "Пока ничего не запомнила", "Нажмите «Запомнить» или скажите: «$name, запомни, что я люблю зелёный чай»")
                        } else {
                            Panel {
                                SectionLabel("Что ${name} помнит о вас")
                                memories.forEachIndexed { i, m ->
                                    if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                    ListRow(m.content, categoryRu(m.category), onClick = { memory = m })
                                }
                            }
                        }
                    }
                    else -> item(span = { GridItemSpan(maxLineSpan) }) { HealthPane(c, name) }
                }
            }
        }
        if (creating || editing != null) NoteEditor(
            editing, if (segment == 1) NoteKind.IDEA else NoteKind.NOTE,
            onSave = { kind, title, content ->
                val e = editing
                scope.launch {
                    runCatching {
                        if (e == null) c.store.notes.create(kind, title, content) else c.store.notes.update(e.copy(kind = kind, title = title, content = content))
                    }.onSuccess { toast(true to if (e == null) "Записала." else "Сохранила.") }
                        .onFailure { toast(false to "Не получилось сохранить: ${it.message}") }
                }
            },
            onDelete = { n -> deleting = n },
            onDismiss = { creating = false; editing = null },
        )
        deleting?.let { n ->
            ConfirmDialog(
                "Удалить запись?", "«${n.title.ifBlank { n.content.take(40) }}» исчезнет из записей.", "Удалить",
                onConfirm = { scope.launch { c.store.notes.delete(n.id); toast(true to "Удалила.") }; editing = null },
                onDismiss = { deleting = null },
            )
        }
        if (newMemory || memory != null) MemoryEditor(
            memory,
            onSave = { text, cat ->
                val m = memory
                scope.launch {
                    if (m == null) c.store.memories.create(text, cat) else c.store.memories.update(m.copy(content = text, category = cat))
                    toast(true to "Запомнила.")
                }
            },
            onDelete = { m -> scope.launch { c.store.memories.delete(m.id); toast(true to "Забыла.") } },
            onDismiss = { newMemory = false; memory = null },
        )
    }
}

/** Строка списка: заголовок, подпись, клик — открыть. */
@Composable
internal fun ListRow(title: String, subtitle: String?, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hovered && onClick != null) p.surfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(src).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun NoteEditor(note: Note?, defaultKind: NoteKind, onSave: (NoteKind, String, String) -> Unit, onDelete: (Note) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(note?.kind ?: defaultKind) }
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
    LoliDialog(if (note == null) (if (kind == NoteKind.IDEA) "Новая идея" else "Новая заметка") else "Запись", onDismiss, width = 620.dp) {
        Segmented(listOf(NoteKind.NOTE to "Заметка", NoteKind.IDEA to "Идея"), kind, { kind = it })
        Field(title, { title = it }, "Название", "Например, пароль от Wi-Fi на даче", modifier = Modifier.padding(top = 16.dp), focusRequester = focus)
        Field(content, { content = it }, "Текст", "Что записать", lines = 10, onSubmit = ::save, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (note != null) GhostButton("Удалить", { onDelete(note) }, icon = Icons.Rounded.DeleteOutline, danger = true)
            Text("Ctrl+Enter — сохранить", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
            GhostButton("Отмена", onDismiss)
            Box(Modifier.width(10.dp))
            AccentButton("Сохранить", ::save, enabled = canSave)
        }
    }
}

@Composable
private fun MemoryEditor(item: MemoryItem?, onSave: (String, String) -> Unit, onDelete: (MemoryItem) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(item?.content.orEmpty()) }
    var category by remember { mutableStateOf(item?.category ?: "other") }
    var askDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LoliDialog(if (item == null) "Запомнить" else "Память", onDismiss, width = 560.dp) {
        Field(text, { text = it }, "Что запомнить", "Например: я не ем сладкое", lines = 3, focusRequester = focus)
        Text("Категория", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Segmented(MEMORY_CATEGORIES, if (MEMORY_CATEGORIES.any { it.first == category }) category else "other", { category = it })
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item != null) GhostButton("Забыть", { askDelete = true }, icon = Icons.Rounded.DeleteOutline, danger = true)
            Box(Modifier.weight(1f))
            GhostButton("Отмена", onDismiss)
            Box(Modifier.width(10.dp))
            AccentButton("Сохранить", { onSave(text.trim(), category); onDismiss() }, enabled = text.isNotBlank())
        }
    }
    if (askDelete && item != null) ConfirmDialog("Забыть?", "«${item.content.take(80)}»", "Забыть", onConfirm = { onDelete(item); onDismiss() }, onDismiss = { askDelete = false })
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
            Icon(if (idea) Icons.Rounded.Lightbulb else Icons.AutoMirrored.Rounded.StickyNote2, null, tint = if (idea) p.warning else p.accent, modifier = Modifier.size(18.dp))
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

// ---------------------------------------------------------------- Здоровье

/** Здоровье за 30 дней, как на телефоне: давление, пульс, вес, сон — графики и последние значения. */
@Composable
private fun HealthPane(c: DesktopContainer, name: String) {
    var bp by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    var pulse by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    var weight by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    var sleep by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val from = Instant.now().minusSeconds(30L * 24 * 3600)
        bp = c.store.habits.since(from, Vitals.BP)
        pulse = c.store.habits.since(from, Vitals.PULSE)
        weight = c.store.habits.since(from, Vitals.WEIGHT)
        sleep = c.store.habits.since(from, Vitals.SLEEP)
        loaded = true
    }
    if (!loaded) return
    if (bp.isEmpty() && pulse.isEmpty() && weight.isEmpty() && sleep.isEmpty()) {
        EmptyState(Icons.Rounded.Favorite, "Пока нет замеров", "Скажите или напишите в чате: «давление 120 на 80», «пульс 72», «вес 68», «спала 7 часов».")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (bp.isNotEmpty()) {
                val dia = bp.map { e -> Regex("""^\d+/(\d+)""").find(e.name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
                ChartCard("Давление", bp.last().name.substringBefore(',').replace("/", " на "), listOf(bp.map { it.at to it.amount }, bp.mapIndexed { i, e -> e.at to dia[i] }), Modifier.weight(1f))
            }
            if (pulse.isNotEmpty()) ChartCard("Пульс", "${fmt(pulse.last().amount)} уд/мин", listOf(pulse.map { it.at to it.amount }), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (weight.isNotEmpty()) ChartCard("Вес", "${fmt(weight.last().amount)} кг", listOf(weight.map { it.at to it.amount }), Modifier.weight(1f))
            if (sleep.isNotEmpty()) ChartCard(
                "Сон", "${fmt(sleep.last().amount)} ч · в среднем ${fmt(Math.round(sleep.map { it.amount }.average() * 10) / 10.0)} ч",
                listOf(sleep.map { it.at to it.amount }), Modifier.weight(1f),
            )
        }
        Text(
            "За последние 30 дней. Спросите $name: «какое у меня давление за неделю», «мой вес за месяц».",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun fmt(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(java.util.Locale("ru"), "%.1f", x)

@Composable
private fun ChartCard(title: String, value: String, series: List<List<Pair<Instant, Double>>>, modifier: Modifier) {
    val p = palette
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(color = p.accent), modifier = Modifier.padding(start = 12.dp))
        }
        val points = series.flatten()
        if (points.size < 2) {
            Text("Нужно хотя бы два замера, чтобы нарисовать график.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            return@Panel
        }
        val minX = points.minOf { it.first.toEpochMilli() }.toFloat()
        val maxX = points.maxOf { it.first.toEpochMilli() }.toFloat().let { if (it == minX) minX + 1 else it }
        val minY = points.minOf { it.second }.toFloat().let { it - maxOf(1f, it * 0.02f) }
        val maxY = points.maxOf { it.second }.toFloat().let { it + maxOf(1f, it * 0.02f) }
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 14.dp)) {
            fun at(pt: Pair<Instant, Double>) = Offset(
                (pt.first.toEpochMilli() - minX) / (maxX - minX) * size.width,
                size.height - (pt.second.toFloat() - minY) / (maxY - minY) * size.height,
            )
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(p.outline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            series.forEachIndexed { k, line ->
                val color = if (k == 0) p.accent else p.accent2
                val path = Path()
                line.forEachIndexed { i, pt -> val o = at(pt); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, color, style = Stroke(width = 3f))
                line.forEach { drawCircle(color, radius = 4f, center = at(it)) }
            }
        }
    }
}
