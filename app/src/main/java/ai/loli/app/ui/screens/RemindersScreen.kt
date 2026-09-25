package ai.loli.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.DeleteButton
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.LoliTopBar
import ai.loli.app.ui.components.SectionTitle
import ai.loli.core.assistant.RuFormat
import ai.loli.core.nlp.RuDateTimeParser
import kotlinx.coroutines.launch

@Composable
fun RemindersScreen(c: AppContainer, onBack: () -> Unit) {
    val reminders by remember { c.store.reminders.observe() }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val zone = c.time.zone()
    val now = c.time.now()
    val exact = c.reminderScheduler.canScheduleExact()
    Scaffold(
        topBar = { LoliTopBar("Напоминания", onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить напоминание") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (!exact && Build.VERSION.SDK_INT >= 31) {
                item {
                    Card(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text(
                            "Точные напоминания выключены: Android может задержать их на несколько минут. Нажмите, чтобы разрешить «Будильники и напоминания».",
                            modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            val active = reminders.filter { it.active }
            val past = reminders.filter { !it.active }
            if (reminders.isEmpty()) {
                item { EmptyState(Icons.Outlined.Alarm, "Напоминаний нет", "Скажите: «Лоли, напомни завтра в 10 утра позвонить клиенту»") }
            }
            if (active.isNotEmpty()) item { SectionTitle("Запланированные") }
            items(active, key = { it.id }) { r ->
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                    headlineContent = { Text(r.text) },
                    supportingContent = { Text(RuFormat.dateTime(r.triggerAt, zone, now) + (r.recurrence?.let { " · ${it.describeRu()}" } ?: "")) },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch { c.reminderScheduler.cancel(r.id); c.store.reminders.cancel(r.id) }
                        }) { Icon(Icons.Outlined.AlarmOff, contentDescription = "Отключить") }
                    },
                )
            }
            if (past.isNotEmpty()) item { SectionTitle("Прошедшие и отключённые") }
            items(past, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.text, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supportingContent = { Text((r.lastFiredAt ?: r.triggerAt).let { RuFormat.dateTime(it, zone, now) }) },
                    trailingContent = { DeleteButton("Напоминание будет удалено.") { scope.launch { c.store.reminders.delete(r.id) } } },
                )
            }
        }
    }
    if (adding) {
        var text by rememberSaveable { mutableStateOf("") }
        var whenText by rememberSaveable { mutableStateOf("") }
        val parser = remember { RuDateTimeParser() }
        val spec = parser.parse(whenText, c.time.today()).spec
        val trigger = parser.resolveTrigger(spec, c.time.now(), zone)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Новое напоминание") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, label = { Text("О чём напомнить") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(whenText, { whenText = it }, label = { Text("Когда (например: завтра в 10:00, каждый понедельник в 9)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(
                        trigger?.let { "Сработает " + RuFormat.dateTime(it, zone, c.time.now()) + (parser.effectiveRecurrence(spec)?.let { r -> ", ${r.describeRu()}" } ?: "") }
                            ?: "Время не распознано",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && trigger != null && trigger.isAfter(c.time.now()), onClick = {
                    val at = trigger ?: return@TextButton
                    scope.launch {
                        val r = c.store.reminders.create(text, at, parser.effectiveRecurrence(spec), zone.id)
                        c.reminderScheduler.schedule(r)
                    }
                    adding = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}
