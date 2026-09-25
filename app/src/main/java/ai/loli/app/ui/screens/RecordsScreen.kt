package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.ConfirmDialog
import ai.loli.app.ui.components.EditorSheet
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.LoliFab
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.Pills
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SearchBox
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.ui.components.Segmented
import ai.loli.app.ui.components.groupColor
import ai.loli.app.ui.components.pressScale
import ai.loli.core.model.MemoryItem
import ai.loli.core.model.MessageRole
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.search.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault())

private val memoryCategories = listOf("other" to "Разное", "preference" to "Предпочтение", "fact" to "Факт", "goal" to "Цель", "person" to "Люди")
private fun categoryRu(c: String) = memoryCategories.firstOrNull { it.first == c }?.second ?: "Разное"

/** Вкладка «Записи»: заметки, идеи и то, что ассистент помнит о пользователе, плюс поиск по смыслу. */
@Composable
fun RecordsScreen(c: AppContainer, segment: Int, onSegment: (Int) -> Unit, openHistory: () -> Unit) {
    val notes by remember { c.store.notes.observe(NoteKind.NOTE) }.collectAsStateWithLifecycle(emptyList())
    val ideas by remember { c.store.notes.observe(NoteKind.IDEA) }.collectAsStateWithLifecycle(emptyList())
    val memories by remember { c.store.memories.observe() }.collectAsStateWithLifecycle(emptyList())
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var editNote by remember { mutableStateOf<Note?>(null) }
    var editMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        hits = runCatching { c.search.search(query, limit = 30, minScore = 0.25) }.getOrDefault(emptyList())
    }

    LoliScreen(
        title = "Записи",
        subtitle = "${notes.size} заметок · ${ideas.size} идей · ${memories.size} в памяти",
        actions = { IconButton(onClick = openHistory) { Icon(Icons.Rounded.History, contentDescription = "История разговоров") } },
        fab = { if (query.isBlank()) LoliFab(Icons.Rounded.Add, "Добавить") { creating = true } },
    ) {
        item(key = "search") { SearchBox(query, { query = it }, "Найти по смыслу во всех записях") }
        if (query.isNotBlank()) {
            item(key = "hits-label") { SectionLabel(if (hits.isEmpty()) "Ничего не найдено" else "Найдено: ${hits.size}") }
            if (hits.isNotEmpty()) item(key = "hits") {
                Group {
                    hits.forEachIndexed { i, h ->
                        if (i > 0) GroupDivider()
                        RowItem(title = h.doc.title, subtitle = listOf(h.doc.type.titleRu, h.doc.body.take(120)).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
            }
            return@LoliScreen
        }
        item(key = "segments") {
            Segmented(listOf("Заметки", "Идеи", "Память"), segment, onSegment, Modifier.padding(top = 16.dp, bottom = 8.dp))
        }
        when (segment) {
            0, 1 -> {
                val list = if (segment == 0) notes else ideas
                if (list.isEmpty()) item(key = "empty") {
                    EmptyState(
                        if (segment == 0) Icons.Rounded.Description else Icons.Rounded.Lightbulb,
                        if (segment == 0) "Заметок пока нет" else "Идей пока нет",
                        if (segment == 0) "Скажите: «$name, создай заметку список покупок: молоко, хлеб»" else "Скажите: «$name, у меня идея — …»",
                    )
                }
                items(list, key = { it.id }) { n -> Box(Modifier.animateItem()) { NoteCard(n) { editNote = n } } }
            }
            else -> {
                if (memories.isEmpty()) item(key = "empty") {
                    EmptyState(Icons.Rounded.Psychology, "Пока ничего не запомнила", "Скажите: «$name, запомни, что я люблю зелёный чай»")
                } else item(key = "memories") {
                    Group(Modifier.padding(top = 4.dp)) {
                        memories.forEachIndexed { i, m ->
                            if (i > 0) GroupDivider()
                            RowItem(title = m.content, subtitle = categoryRu(m.category), onClick = { editMemory = m })
                        }
                    }
                }
            }
        }
    }

    if (creating || editNote != null) {
        val kind = editNote?.kind ?: if (segment == 1) NoteKind.IDEA else NoteKind.NOTE
        if (segment == 2 && editNote == null) {
            MemoryEditor(null, onDismiss = { creating = false }, onSave = { text, cat -> scope.launch { c.store.memories.create(text, cat) }; creating = false }, onDelete = {})
        } else {
            NoteEditor(
                initial = editNote, kind = kind,
                onDismiss = { creating = false; editNote = null },
                onSave = { title, content ->
                    val e = editNote
                    scope.launch { if (e == null) c.store.notes.create(kind, title, content) else c.store.notes.update(e.copy(title = title, content = content)) }
                    creating = false; editNote = null
                },
                onDelete = { id -> scope.launch { c.store.notes.delete(id) }; editNote = null },
            )
        }
    }
    editMemory?.let { m ->
        MemoryEditor(
            m, onDismiss = { editMemory = null },
            onSave = { text, cat -> scope.launch { c.store.memories.update(m.copy(content = text, category = cat)) }; editMemory = null },
            onDelete = { scope.launch { c.store.memories.delete(m.id) }; editMemory = null },
        )
    }
}

@Composable
private fun NoteCard(n: Note, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.large, color = groupColor(), interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).pressScale(interaction),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(n.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (n.content.isNotBlank()) {
                Text(n.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            Text(dateFmt.format(n.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun NoteEditor(initial: Note?, kind: NoteKind, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var content by rememberSaveable { mutableStateOf(initial?.content.orEmpty()) }
    var askDelete by remember { mutableStateOf(false) }
    val idea = kind == NoteKind.IDEA
    EditorSheet(if (initial == null) (if (idea) "Новая идея" else "Новая заметка") else (if (idea) "Идея" else "Заметка"), onDismiss) {
        LoliField(title, { title = it }, "Название")
        LoliField(content, { content = it }, "Текст", singleLine = false, minLines = 5)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (initial != null) SecondaryButton("Удалить", { askDelete = true }, danger = true)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Сохранить", { onSave(title.ifBlank { content.lineSequence().firstOrNull().orEmpty().take(60) }, content) }, enabled = title.isNotBlank() || content.isNotBlank())
        }
    }
    if (askDelete && initial != null) {
        ConfirmDialog("Удалить?", "«${initial.title}» будет удалена на всех устройствах.", "Удалить", onConfirm = { onDelete(initial.id) }, onDismiss = { askDelete = false })
    }
}

@Composable
private fun MemoryEditor(initial: MemoryItem?, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.content.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial?.category ?: "other") }
    var askDelete by remember { mutableStateOf(false) }
    EditorSheet(if (initial == null) "Запомнить" else "Память", onDismiss) {
        LoliField(text, { text = it }, "Что запомнить", singleLine = false, minLines = 2)
        Pills(memoryCategories.map { it.second }, memoryCategories.indexOfFirst { it.first == category }.coerceAtLeast(0), { category = memoryCategories[it].first })
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (initial != null) SecondaryButton("Забыть", { askDelete = true }, danger = true)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Сохранить", { onSave(text.trim(), category) }, enabled = text.isNotBlank())
        }
    }
    if (askDelete) ConfirmDialog("Забыть?", "Ассистент забудет: «${initial?.content?.take(60)}».", "Забыть", onConfirm = onDelete, onDismiss = { askDelete = false })
}

@Composable
fun HistoryScreen(c: AppContainer, onBack: () -> Unit) {
    val messages by remember { c.store.conversations.observeRecent(300) }.collectAsStateWithLifecycle(emptyList())
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    val fmt = remember { DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault()) }
    LoliScreen(title = "История", subtitle = "Разговоры с ассистентом", onBack = onBack) {
        if (messages.isEmpty()) {
            item { EmptyState(Icons.Rounded.History, "История пуста", "Здесь появятся ваши разговоры с ассистентом") }
        } else {
            item {
                Group {
                    messages.forEachIndexed { i, m ->
                        if (i > 0) GroupDivider()
                        RowItem(
                            title = m.content,
                            subtitle = (if (m.role == MessageRole.USER) "Вы" else name) + " · " + fmt.format(m.createdAt),
                            maxSubtitleLines = 1,
                        )
                    }
                }
            }
        }
    }
}
