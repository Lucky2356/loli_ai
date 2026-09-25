package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.DeleteButton
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.LoliTopBar
import ai.loli.app.ui.components.SearchField
import ai.loli.app.ui.components.SectionTitle
import ai.loli.core.model.MessageRole
import ai.loli.core.search.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Раздел «Память»: общий поиск по смыслу и переход к разделам. */
@Composable
fun MemoryHubScreen(c: AppContainer, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(350)
        hits = runCatching { c.search.search(query, limit = 20, minScore = 0.25) }.getOrDefault(emptyList())
    }
    Scaffold(topBar = { LoliTopBar("Память") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SearchField(query, { query = it }, "Найти по смыслу во всех записях") }
            if (query.isNotBlank()) {
                if (hits.isEmpty()) item { Text("Ничего не найдено", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(hits, key = { it.doc.id }) { h ->
                    ListItem(
                        overlineContent = { Text(h.doc.type.titleRu) },
                        headlineContent = { Text(h.doc.title) },
                        supportingContent = { if (h.doc.body.isNotBlank()) Text(h.doc.body.take(120), maxLines = 2) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                item { SectionTitle("Разделы") }
                item { HubCard(Icons.Outlined.Description, "Заметки", "Списки, мысли, записи") { onOpen("notes/note") } }
                item { HubCard(Icons.Outlined.Lightbulb, "Идеи", "Идеи и их развитие") { onOpen("notes/idea") } }
                item { HubCard(Icons.Outlined.Psychology, "Что я помню о вас", "Факты, предпочтения, цели") { onOpen("memories") } }
                item { HubCard(Icons.Outlined.Alarm, "Напоминания", "Запланированные и прошедшие") { onOpen("reminders") } }
                item { HubCard(Icons.Outlined.History, "История", "Все разговоры с ассистентом") { onOpen("history") } }
            }
        }
    }
}

@Composable
private fun HubCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
        )
    }
}

@Composable
fun MemoriesScreen(c: AppContainer, onBack: () -> Unit) {
    val items by remember { c.store.memories.observe() }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { LoliTopBar("Память", onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Запомнить") } },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(Icons.Outlined.Psychology, "Пока ничего не запомнила", "Скажите: «Лоли, запомни, что я люблю зелёный чай»", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.id }) { m ->
                    ListItem(
                        headlineContent = { Text(m.content) },
                        supportingContent = { Text(categoryRu(m.category)) },
                        trailingContent = { DeleteButton("Лоли забудет: «${m.content.take(60)}»") { scope.launch { c.store.memories.delete(m.id) } } },
                    )
                }
            }
        }
    }
    if (adding) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Запомнить") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Что запомнить") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { scope.launch { c.store.memories.create(text, "other") }; adding = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}

private fun categoryRu(c: String) = when (c) {
    "preference" -> "Предпочтение"; "fact" -> "Факт"; "goal" -> "Цель"; "person" -> "Люди"; else -> "Разное"
}

@Composable
fun HistoryScreen(c: AppContainer, onBack: () -> Unit) {
    val messages by remember { c.store.conversations.observeRecent(300) }.collectAsStateWithLifecycle(emptyList())
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    val fmt = remember { DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault()) }
    Scaffold(topBar = { LoliTopBar("История", onBack) }) { padding ->
        if (messages.isEmpty()) {
            EmptyState(Icons.Outlined.History, "История пуста", "Здесь появятся ваши разговоры с ассистентом", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(messages, key = { it.id }) { m ->
                    ListItem(
                        overlineContent = { Text((if (m.role == MessageRole.USER) "Вы" else name) + " · " + fmt.format(m.createdAt)) },
                        headlineContent = { Text(m.content) },
                    )
                }
            }
        }
    }
}
