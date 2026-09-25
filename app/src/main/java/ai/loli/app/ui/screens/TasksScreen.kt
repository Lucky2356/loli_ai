package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.DeleteButton
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.LoliTopBar
import ai.loli.core.assistant.RuFormat
import ai.loli.core.nlp.RuDateTimeParser
import kotlinx.coroutines.launch

@Composable
fun TasksScreen(c: AppContainer, onBack: (() -> Unit)?) {
    val tasks by remember { c.store.tasks.observe() }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = c.time.today()
    val now = c.time.zonedNow().toLocalTime()
    val shown = when (tab) {
        0 -> tasks.filter { !it.done }
        1 -> tasks.filter { it.isOverdue(today, now) }
        else -> tasks.filter { it.done }
    }
    Scaffold(
        topBar = { LoliTopBar("Задачи", onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить задачу") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Активные", "Просроченные", "Выполненные").forEachIndexed { i, label ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                }
            }
            if (shown.isEmpty()) {
                EmptyState(Icons.Outlined.TaskAlt, "Здесь пусто", "Скажите: «Лоли, добавь задачу купить продукты»")
            } else {
                LazyColumn {
                    items(shown, key = { it.id }) { t ->
                        val overdue = t.isOverdue(today, now)
                        ListItem(
                            leadingContent = { Checkbox(checked = t.done, onCheckedChange = { v -> scope.launch { c.store.tasks.setDone(t.id, v) } }) },
                            headlineContent = { Text(t.title, textDecoration = if (t.done) TextDecoration.LineThrough else null) },
                            supportingContent = {
                                val due = t.dueDate?.let { d -> "Срок: ${RuFormat.date(d, today)}" + (t.dueTime?.let { " ${RuFormat.time(it)}" } ?: "") }
                                val text = listOfNotNull(due, t.details.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (text.isNotEmpty()) Text(text, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingContent = { DeleteButton("Задача «${t.title}» будет удалена.") { scope.launch { c.store.tasks.delete(t.id) } } },
                        )
                    }
                }
            }
        }
    }
    if (adding) {
        var title by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Новая задача") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Что сделать") }, modifier = Modifier.fillMaxWidth())
                    Text("Можно указать срок словами: «завтра в 15:00 отправить отчёт»", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    val parsed = RuDateTimeParser().parse(title, today)
                    val clean = parsed.remainder.ifBlank { title }.replaceFirstChar { it.uppercase() }
                    scope.launch { c.store.tasks.create(clean, "", parsed.spec.date ?: parsed.spec.time?.let { today }, parsed.spec.time) }
                    adding = false
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}
