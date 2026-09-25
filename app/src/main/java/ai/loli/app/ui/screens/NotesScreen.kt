package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.DeleteButton
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.LoliTopBar
import ai.loli.app.ui.components.SearchField
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.nlp.TextAnalysis
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault())

@Composable
fun NotesScreen(c: AppContainer, kind: NoteKind, onBack: () -> Unit) {
    val notes by remember(kind) { c.store.notes.observe(kind) }.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isIdea = kind == NoteKind.IDEA
    val filtered = if (query.isBlank()) notes else {
        val q = TextAnalysis.stems(query)
        notes.filter { n ->
            val s = TextAnalysis.stems(n.title + " " + n.content)
            q.all { qs -> s.any { TextAnalysis.stemSimilarity(qs, it) >= 0.7 } }
        }
    }
    Scaffold(
        topBar = { LoliTopBar(if (isIdea) "Идеи" else "Заметки", onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(query, { query = it }, if (isIdea) "Поиск по идеям" else "Поиск по заметкам")
            if (filtered.isEmpty()) {
                EmptyState(
                    if (isIdea) Icons.Outlined.Lightbulb else Icons.Outlined.Description,
                    if (query.isBlank()) (if (isIdea) "Идей пока нет" else "Заметок пока нет") else "Ничего не найдено",
                    if (isIdea) "Скажите: «Лоли, у меня появилась идея…»" else "Скажите: «Лоли, создай заметку…»",
                )
            } else {
                LazyColumn {
                    items(filtered, key = { it.id }) { n ->
                        Card(onClick = { editing = n }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            androidx.compose.foundation.layout.Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(n.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium)
                                    if (n.content.isNotBlank()) {
                                        Text(n.content, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    Text(dateFmt.format(n.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                                DeleteButton("«${n.title}» будет удалена на всех устройствах.") { scope.launch { c.store.notes.delete(n.id) } }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        NoteEditorDialog(
            initial = editing, isIdea = isIdea,
            onDismiss = { creating = false; editing = null },
            onSave = { title, content ->
                scope.launch {
                    val e = editing
                    if (e == null) c.store.notes.create(kind, title, content) else c.store.notes.update(e.copy(title = title, content = content))
                }
                creating = false; editing = null
            },
        )
    }
}

@Composable
private fun NoteEditorDialog(initial: Note?, isIdea: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var content by rememberSaveable { mutableStateOf(initial?.content.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) (if (isIdea) "Новая идея" else "Новая заметка") else "Редактирование") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it }, label = { Text("Текст") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() || content.isNotBlank(), onClick = { onSave(title.ifBlank { content.take(60) }, content) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
