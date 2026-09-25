package ai.loli.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
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
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.ui.components.Segmented
import ai.loli.app.ui.components.rememberResumeTick
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Reminder
import ai.loli.core.model.TaskItem
import ai.loli.core.nlp.RuDateTimeParser
import kotlinx.coroutines.launch

/** Вкладка «Планы»: задачи и напоминания. */
@Composable
fun PlansScreen(c: AppContainer, segment: Int, onSegment: (Int) -> Unit) {
    val tasks by remember { c.store.tasks.observe() }.collectAsStateWithLifecycle(emptyList())
    val reminders by remember { c.store.reminders.observe() }.collectAsStateWithLifecycle(emptyList())
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskItem?>(null) }
    var editReminder by remember { mutableStateOf<Reminder?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val today = c.time.today()
    val now = c.time.zonedNow().toLocalTime()
    val zone = c.time.zone()
    val resumeTick = rememberResumeTick()
    val exact = remember(resumeTick) { c.reminderScheduler.canScheduleExact() }

    val active = tasks.count { !it.done }
    val scheduled = reminders.count { it.active }
    LoliScreen(
        title = "Планы",
        subtitle = "$active задач · $scheduled напоминаний",
        fab = { LoliFab(Icons.Rounded.Add, if (segment == 0) "Добавить задачу" else "Добавить напоминание") { adding = true } },
    ) {
        item(key = "segments") { Segmented(listOf("Задачи", "Напоминания"), segment, onSegment, Modifier.padding(bottom = 12.dp)) }
        if (segment == 0) {
            item(key = "filters") { Pills(listOf("Активные", "Просроченные", "Готово"), filter, { filter = it }) }
            val shown = when (filter) {
                0 -> tasks.filter { !it.done }.sortedWith(compareBy({ it.dueDate == null }, { it.dueDate }, { it.dueTime }))
                1 -> tasks.filter { it.isOverdue(today, now) }
                else -> tasks.filter { it.done }
            }
            if (shown.isEmpty()) {
                item(key = "empty") { EmptyState(Icons.Rounded.TaskAlt, if (filter == 1) "Просроченных нет" else "Здесь пусто", "Скажите: «$name, добавь задачу купить продукты завтра»") }
            } else item(key = "tasks") {
                Group(Modifier.padding(top = 12.dp)) {
                    shown.forEachIndexed { i, t ->
                        if (i > 0) GroupDivider(inset = 60.dp)
                        val overdue = t.isOverdue(today, now)
                        val due = t.dueDate?.let { d -> RuFormat.date(d, today) + (t.dueTime?.let { " ${RuFormat.time(it)}" } ?: "") }
                        RowItem(
                            title = t.title,
                            subtitle = listOfNotNull(due, t.details.takeIf { it.isNotBlank() }).joinToString(" · "),
                            titleColor = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            onClick = { editTask = t },
                            leading = {
                                IconButton(onClick = { scope.launch { c.store.tasks.setDone(t.id, !t.done) } }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        if (t.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = if (t.done) "Вернуть в работу" else "Выполнено",
                                        tint = if (t.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        } else {
            if (!exact && Build.VERSION.SDK_INT >= 31) item(key = "exact") {
                Surface(
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) } },
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Точные напоминания выключены — Android может задержать их. Нажмите, чтобы разрешить.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            val upcoming = reminders.filter { it.active }.sortedBy { it.triggerAt }
            val past = reminders.filter { !it.active }.sortedByDescending { it.lastFiredAt ?: it.triggerAt }
            if (reminders.isEmpty()) item(key = "empty") { EmptyState(Icons.Rounded.Alarm, "Напоминаний нет", "Скажите: «$name, напомни завтра в 10 утра позвонить маме»") }
            if (upcoming.isNotEmpty()) {
                item(key = "up-label") { SectionLabel("Запланированные") }
                item(key = "up") {
                    Group {
                        upcoming.forEachIndexed { i, r ->
                            if (i > 0) GroupDivider(inset = 66.dp)
                            RowItem(
                                title = r.text, icon = Icons.Rounded.Alarm,
                                subtitle = RuFormat.dateTime(r.triggerAt, zone, c.time.now()) + (r.recurrence?.let { " · ${it.describeRu()}" } ?: ""),
                                onClick = { editReminder = r },
                            )
                        }
                    }
                }
            }
            if (past.isNotEmpty()) {
                item(key = "past-label") { SectionLabel("Прошедшие и отключённые") }
                item(key = "past") {
                    Group {
                        past.forEachIndexed { i, r ->
                            if (i > 0) GroupDivider()
                            RowItem(
                                title = r.text, titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                subtitle = RuFormat.dateTime(r.lastFiredAt ?: r.triggerAt, zone, c.time.now()),
                                onClick = { editReminder = r },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        if (segment == 0) TaskEditor(c, null, onDismiss = { adding = false }) else ReminderCreator(c, onDismiss = { adding = false })
    }
    editTask?.let { t -> TaskEditor(c, t, onDismiss = { editTask = null }) }
    editReminder?.let { r ->
        var askDelete by remember { mutableStateOf(false) }
        EditorSheet("Напоминание", onDismiss = { editReminder = null }) {
            Text(r.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (r.active) "Сработает " else "Было ") + RuFormat.dateTime(if (r.active) r.triggerAt else (r.lastFiredAt ?: r.triggerAt), zone, c.time.now()) +
                    (r.recurrence?.let { ", ${it.describeRu()}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SecondaryButton("Удалить", { askDelete = true }, danger = true)
                Spacer(Modifier.weight(1f))
                if (r.active) PrimaryButton("Отключить", {
                    scope.launch { c.reminderScheduler.cancel(r.id); c.store.reminders.cancel(r.id) }
                    editReminder = null
                })
            }
        }
        if (askDelete) ConfirmDialog("Удалить напоминание?", "«${r.text}» будет удалено.", "Удалить", onConfirm = {
            scope.launch { c.reminderScheduler.cancel(r.id); c.store.reminders.delete(r.id) }
            editReminder = null
        }, onDismiss = { askDelete = false })
    }
}

@Composable
private fun TaskEditor(c: AppContainer, task: TaskItem?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = c.time.today()
    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var details by rememberSaveable { mutableStateOf(task?.details.orEmpty()) }
    var askDelete by remember { mutableStateOf(false) }
    val parser = remember { RuDateTimeParser() }
    val parsed = remember(title) { parser.parse(title, today) }
    val dueDate = parsed.spec.date ?: parsed.spec.time?.let { today }
    EditorSheet(if (task == null) "Новая задача" else "Задача", onDismiss) {
        LoliField(title, { title = it }, "Что сделать", singleLine = false,
            supporting = when {
                task != null -> null
                dueDate != null -> "Срок: " + RuFormat.date(dueDate, today) + (parsed.spec.time?.let { " ${RuFormat.time(it)}" } ?: "")
                else -> "Срок можно написать словами: «завтра в 15:00 отправить отчёт»"
            })
        LoliField(details, { details = it }, "Подробности", singleLine = false, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (task != null) SecondaryButton("Удалить", { askDelete = true }, danger = true)
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (task == null) "Добавить" else "Сохранить", {
                scope.launch {
                    if (task == null) {
                        val clean = parsed.remainder.ifBlank { title }.trim().replaceFirstChar { it.uppercase() }
                        c.store.tasks.create(clean, details.trim(), dueDate, parsed.spec.time)
                    } else {
                        c.store.tasks.update(task.copy(title = title.trim(), details = details.trim()))
                    }
                }
                onDismiss()
            }, enabled = title.isNotBlank())
        }
    }
    if (askDelete && task != null) ConfirmDialog("Удалить задачу?", "«${task.title}» будет удалена.", "Удалить",
        onConfirm = { scope.launch { c.store.tasks.delete(task.id) }; onDismiss() }, onDismiss = { askDelete = false })
}

@Composable
private fun ReminderCreator(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val zone = c.time.zone()
    var text by rememberSaveable { mutableStateOf("") }
    var whenText by rememberSaveable { mutableStateOf("") }
    val parser = remember { RuDateTimeParser() }
    val spec = parser.parse(whenText, c.time.today()).spec
    val trigger = parser.resolveTrigger(spec, c.time.now(), zone)
    val valid = trigger != null && trigger.isAfter(c.time.now())
    EditorSheet("Новое напоминание", onDismiss) {
        LoliField(text, { text = it }, "О чём напомнить", singleLine = false)
        LoliField(whenText, { whenText = it }, "Когда", placeholder = "завтра в 10:00, каждый понедельник в 9")
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                when {
                    whenText.isBlank() -> "Укажите время словами"
                    trigger == null -> "Время не распознано"
                    !valid -> "Это время уже прошло"
                    else -> "Сработает " + RuFormat.dateTime(trigger, zone, c.time.now()) + (parser.effectiveRecurrence(spec)?.let { ", ${it.describeRu()}" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (whenText.isNotBlank() && !valid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton("Создать", {
            val at = trigger ?: return@PrimaryButton
            scope.launch {
                val r = c.store.reminders.create(text.trim(), at, parser.effectiveRecurrence(spec), zone.id)
                c.reminderScheduler.schedule(r)
            }
            onDismiss()
        }, enabled = text.isNotBlank() && valid, modifier = Modifier.fillMaxWidth())
    }
}
