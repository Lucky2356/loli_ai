package ai.loli.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.QuickAdd
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Reminder
import ai.loli.core.model.ShoppingItem
import ai.loli.core.model.TaskItem
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Вкладка «Планы» — как на телефоне: задачи, напоминания и списки покупок. */
@Composable
fun PlansScreen(c: DesktopContainer) {
    val p = palette
    val tasks by remember { c.store.tasks.observe() }.collectAsState(emptyList())
    val reminders by remember { c.store.reminders.observe() }.collectAsState(emptyList())
    val shopping by remember { c.store.shopping.observe() }.collectAsState(emptyList())
    val name = c.settings.state.collectAsState().value.assistantName
    var segment by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(0) }
    var editTask by remember { mutableStateOf<TaskItem?>(null) }
    var newTask by remember { mutableStateOf(false) }
    var newReminder by remember { mutableStateOf(false) }
    var openReminder by remember { mutableStateOf<Reminder?>(null) }
    var newItems by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val now = LocalTime.now()
    val zone = ZoneId.systemDefault()
    val open = remember(tasks) {
        tasks.filter { !it.done }.sortedWith(compareBy<TaskItem> { it.dueDate == null }.thenBy { it.dueDate?.toEpochDay() ?: 0L }.thenBy { it.dueTime })
    }
    val shownTasks = when (filter) {
        0 -> open
        1 -> open.filter { it.isOverdue(today, now) }
        else -> tasks.filter { it.done }.sortedByDescending { it.completedAt }
    }
    val upcoming = remember(reminders) { reminders.filter { it.active }.sortedBy { it.triggerAt } }
    val past = remember(reminders) { reminders.filter { !it.active }.sortedByDescending { it.lastFiredAt ?: it.triggerAt }.take(30) }
    WithToast { toast ->
        Page {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    PageHeader(
                        "Планы",
                        "${RuFormat.count(open.size, "задача", "задачи", "задач")} · ${RuFormat.count(upcoming.size, "напоминание", "напоминания", "напоминаний")}" +
                            " · ${RuFormat.count(shopping.count { !it.done }, "покупка", "покупки", "покупок")}",
                    ) {
                        when (segment) {
                            0 -> AccentButton("Новая задача", { newTask = true }, icon = Icons.Rounded.Add)
                            1 -> AccentButton("Новое напоминание", { newReminder = true }, icon = Icons.Rounded.Add)
                            else -> AccentButton("Добавить в список", { newItems = true }, icon = Icons.Rounded.Add)
                        }
                    }
                    Segmented(listOf(0 to "Задачи", 1 to "Напоминания", 2 to "Покупки"), segment, { segment = it })
                }
                when (segment) {
                    0 -> {
                        item {
                            QuickAddBar(c, listOf(QuickAdd.Kind.TASK to "Задача"), mapOf(QuickAdd.Kind.TASK to "Быстро: «купить хлеб завтра», «созвон в пятницу в 15:00» — и Enter"), toast)
                        }
                        item { Segmented(listOf(0 to "Активные · ${open.size}", 1 to "Просроченные", 2 to "Готово"), filter, { filter = it }) }
                        item {
                            Panel {
                                if (shownTasks.isEmpty()) Text(
                                    when (filter) { 1 -> "Просроченных нет."; 2 -> "Пока ничего не сделано."; else -> "Задач нет. Добавьте выше или скажите: «$name, добавь задачу купить продукты завтра»." },
                                    style = MaterialTheme.typography.bodyMedium.copy(color = p.muted),
                                )
                                shownTasks.forEachIndexed { i, t ->
                                    if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                    TaskRow(t, today, now, onToggle = { scope.launch { c.store.tasks.setDone(t.id, !t.done) } }, onOpen = { editTask = t })
                                }
                            }
                        }
                    }
                    1 -> {
                        item {
                            QuickAddBar(c, listOf(QuickAdd.Kind.REMINDER to "Напоминание"), mapOf(QuickAdd.Kind.REMINDER to "Быстро: «завтра в 9 позвонить маме», «через 20 минут чай» — и Enter"), toast)
                        }
                        item {
                            Panel {
                                SectionLabel("Запланированные")
                                if (upcoming.isEmpty()) Text("Напоминаний нет. Добавьте выше или скажите: «$name, напомни завтра в 10 позвонить маме».", style = MaterialTheme.typography.bodyMedium.copy(color = p.muted))
                                upcoming.forEachIndexed { i, r ->
                                    if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                    ReminderRow(r, zone, active = true) { openReminder = r }
                                }
                            }
                        }
                        if (past.isNotEmpty()) item {
                            Panel {
                                SectionLabel("Прошедшие и отключённые")
                                past.forEachIndexed { i, r ->
                                    if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                    ReminderRow(r, zone, active = false) { openReminder = r }
                                }
                            }
                        }
                        item {
                            Text(
                                "Напоминания приходят, пока $name запущена (закрытое окно остаётся в трее): окно поверх всех программ со звуком. " +
                                    "Чтобы ничего не пропустить — «Настройки → Напоминания и запуск → Запускать вместе с Windows».",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    else -> {
                        if (shopping.isEmpty()) item {
                            EmptyState(Icons.Rounded.ShoppingCart, "Список покупок пуст", "Нажмите «Добавить в список» или скажите: «$name, добавь в покупки молоко, хлеб и яйца».")
                        }
                        shopping.groupBy { it.listName }.forEach { (list, rows) ->
                            item(key = "list-$list") {
                                Panel {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SectionLabel("$list · осталось ${rows.count { !it.done }}", Modifier.weight(1f))
                                        if (rows.any { it.done }) GhostButton("Убрать купленное", { scope.launch { c.store.shopping.clear(list, onlyDone = true) } })
                                    }
                                    rows.sortedWith(compareBy({ it.done }, { it.createdAt })).forEachIndexed { i, it ->
                                        if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                        ShoppingRow(it, onToggle = { scope.launch { c.store.shopping.setDone(it.id, !it.done) } }, onDelete = { scope.launch { c.store.shopping.delete(it.id) } })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (newTask || editTask != null) TaskEditor(c, editTask, onDone = toast, onDismiss = { newTask = false; editTask = null })
        if (newReminder) ReminderCreator(c, onDone = toast, onDismiss = { newReminder = false })
        openReminder?.let { r -> ReminderDetails(c, r, onDone = toast, onDismiss = { openReminder = null }) }
        if (newItems) ShoppingAdder(c, onDone = toast, onDismiss = { newItems = false })
    }
}

@Composable
private fun TaskRow(t: TaskItem, today: LocalDate, now: LocalTime, onToggle: () -> Unit, onOpen: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val overdue = t.isOverdue(today, now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hovered) p.surfaceHigh else Color.Transparent)
            .hoverable(src).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(t.done, onToggle)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (t.done) p.faint else if (overdue) p.danger else p.text,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                ),
            )
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

@Composable
private fun ReminderRow(r: Reminder, zone: ZoneId, active: Boolean, onClick: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hovered) p.surfaceHigh else Color.Transparent)
            .hoverable(src).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background((if (active) p.accent else p.faint).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (r.recurrence != null) Icons.Rounded.Repeat else if (active) Icons.Rounded.Alarm else Icons.Rounded.NotificationsNone,
                null, tint = if (active) p.accent else p.faint, modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(r.text, style = MaterialTheme.typography.bodyMedium.copy(color = if (active) p.text else p.muted))
            Text(
                (if (active) "" else "Было ") + RuFormat.dateTime(if (active) r.triggerAt else (r.lastFiredAt ?: r.triggerAt), zone, Instant.now()) +
                    (r.recurrence?.let { " · ${it.describeRu()}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hovered) p.surfaceHigh else Color.Transparent)
            .hoverable(src).clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(item.done, onToggle)
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium.copy(color = if (item.done) p.faint else p.text, textDecoration = if (item.done) TextDecoration.LineThrough else null),
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterEnd) {
            if (hovered) IconCircle(Icons.Rounded.Close, "Удалить", onDelete, size = 26.dp)
        }
    }
}

/** Новая задача или правка: срок можно написать словами прямо в названии — как на телефоне. */
@Composable
private fun TaskEditor(c: DesktopContainer, task: TaskItem?, onDone: (Pair<Boolean, String>) -> Unit, onDismiss: () -> Unit) {
    val p = palette
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var details by remember { mutableStateOf(task?.details.orEmpty()) }
    var askDelete by remember { mutableStateOf(false) }
    val parser = remember { RuDateTimeParser() }
    val parsed = remember(title) { parser.parse(title, today) }
    val dueDate = parsed.spec.date ?: parsed.spec.time?.let { today }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun save() {
        if (title.isBlank()) return
        scope.launch {
            if (task == null) {
                val clean = parsed.remainder.ifBlank { title }.trim().replaceFirstChar { it.uppercase() }
                c.store.tasks.create(clean, details.trim(), dueDate, parsed.spec.time)
                onDone(true to "Добавила задачу.")
            } else {
                c.store.tasks.update(task.copy(title = title.trim(), details = details.trim()))
                onDone(true to "Сохранила.")
            }
        }
        onDismiss()
    }
    LoliDialog(if (task == null) "Новая задача" else "Задача", onDismiss, width = 560.dp) {
        Field(title, { title = it }, "Что сделать", "Например: завтра в 15:00 отправить отчёт", onSubmit = ::save, focusRequester = focus)
        Text(
            when {
                task != null -> task.dueDate?.let { "Срок: " + RuFormat.date(it, today) + (task.dueTime?.let { t -> " ${RuFormat.time(t)}" } ?: "") } ?: "Без срока"
                dueDate != null -> "Срок: " + RuFormat.date(dueDate, today) + (parsed.spec.time?.let { " ${RuFormat.time(it)}" } ?: "")
                else -> "Срок можно написать словами: «завтра», «в пятницу в 15:00», «через неделю»"
            },
            style = MaterialTheme.typography.bodySmall.copy(color = if (dueDate != null && task == null) p.accent else p.muted),
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        Field(details, { details = it }, "Подробности", "Необязательно", lines = 3, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (task != null) GhostButton("Удалить", { askDelete = true }, icon = Icons.Rounded.DeleteOutline, danger = true)
            Box(Modifier.weight(1f))
            GhostButton("Отмена", onDismiss)
            Box(Modifier.width(10.dp))
            AccentButton(if (task == null) "Добавить" else "Сохранить", ::save, enabled = title.isNotBlank())
        }
    }
    if (askDelete && task != null) ConfirmDialog(
        "Удалить задачу?", "«${task.title}» будет удалена.", "Удалить",
        onConfirm = { scope.launch { c.store.tasks.delete(task.id); onDone(true to "Удалила задачу.") }; onDismiss() },
        onDismiss = { askDelete = false },
    )
}

/** Новое напоминание: о чём и когда — словами, с проверкой «Сработает …» сразу при вводе. */
@Composable
private fun ReminderCreator(c: DesktopContainer, onDone: (Pair<Boolean, String>) -> Unit, onDismiss: () -> Unit) {
    val p = palette
    val scope = rememberCoroutineScope()
    val zone = c.time.zone()
    var text by remember { mutableStateOf("") }
    var whenText by remember { mutableStateOf("") }
    val parser = remember { RuDateTimeParser() }
    val spec = remember(whenText) { parser.parse(whenText, LocalDate.now()).spec }
    val trigger = remember(spec) { parser.resolveTrigger(spec, c.time.now(), zone) }
    val valid = trigger != null && trigger.isAfter(c.time.now())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun create() {
        val at = trigger ?: return
        if (text.isBlank() || !valid) return
        scope.launch {
            c.store.reminders.create(text.trim().replaceFirstChar { it.uppercase() }, at, parser.effectiveRecurrence(spec), zone.id)
            onDone(true to "Напомню " + RuFormat.dateTime(at, zone, c.time.now()) + ".")
        }
        onDismiss()
    }
    LoliDialog("Новое напоминание", onDismiss, width = 560.dp) {
        Field(text, { text = it }, "О чём напомнить", "Позвонить маме", focusRequester = focus)
        Field(whenText, { whenText = it }, "Когда", "через 20 минут, завтра в 10:00, каждый понедельник в 9", onSubmit = ::create, modifier = Modifier.padding(top = 14.dp))
        Text(
            when {
                whenText.isBlank() -> "Укажите время словами"
                trigger == null -> "Время не распознано"
                !valid -> "Это время уже прошло"
                else -> "Сработает " + RuFormat.dateTime(trigger, zone, c.time.now()) + (parser.effectiveRecurrence(spec)?.let { ", ${it.describeRu()}" } ?: "")
            },
            style = MaterialTheme.typography.bodySmall.copy(color = if (whenText.isNotBlank() && !valid) p.danger else if (valid) p.accent else p.muted),
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            GhostButton("Отмена", onDismiss)
            AccentButton("Создать", ::create, enabled = text.isNotBlank() && valid)
        }
    }
}

@Composable
private fun ReminderDetails(c: DesktopContainer, r: Reminder, onDone: (Pair<Boolean, String>) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val zone = c.time.zone()
    var askDelete by remember { mutableStateOf(false) }
    LoliDialog("Напоминание", onDismiss, width = 520.dp) {
        Text(r.text, style = MaterialTheme.typography.bodyLarge)
        Text(
            (if (r.active) "Сработает " else "Было ") + RuFormat.dateTime(if (r.active) r.triggerAt else (r.lastFiredAt ?: r.triggerAt), zone, c.time.now()) +
                (r.recurrence?.let { ", ${it.describeRu()}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium.copy(color = palette.muted), modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Удалить", { askDelete = true }, icon = Icons.Rounded.DeleteOutline, danger = true)
            Box(Modifier.weight(1f))
            if (r.active) GhostButton("Отключить", { scope.launch { c.store.reminders.cancel(r.id); onDone(true to "Отключила.") }; onDismiss() })
            else GhostButton("Повторить через 10 мин", {
                scope.launch { c.store.reminders.create(r.text, c.time.now().plusSeconds(600), null, zone.id); onDone(true to "Напомню через 10 минут.") }
                onDismiss()
            })
            AccentButton("Закрыть", onDismiss)
        }
    }
    if (askDelete) ConfirmDialog(
        "Удалить напоминание?", "«${r.text}»" + (if (r.recurrence != null) " — повторяющееся, удалятся и все следующие." else ""), "Удалить",
        onConfirm = { scope.launch { c.store.reminders.delete(r.id); onDone(true to "Удалила напоминание.") }; onDismiss() },
        onDismiss = { askDelete = false },
    )
}

/** Добавить в список: через запятую — сразу несколько, как на телефоне. */
@Composable
private fun ShoppingAdder(c: DesktopContainer, onDone: (Pair<Boolean, String>) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(ShoppingItem.DEFAULT_LIST) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun add() {
        val items = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return
        scope.launch {
            c.store.shopping.add(list.trim().ifEmpty { ShoppingItem.DEFAULT_LIST }, items)
            onDone(true to "Добавила: ${items.size}.")
        }
        onDismiss()
    }
    LoliDialog("В список", onDismiss, width = 520.dp) {
        Field(text, { text = it }, "Что добавить", "Молоко, хлеб, яйца", onSubmit = ::add, focusRequester = focus)
        Field(list, { list = it }, "Список", "Покупки", modifier = Modifier.padding(top = 14.dp))
        Text("Например: «Покупки», «В дорогу», «На дачу».", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            GhostButton("Отмена", onDismiss)
            AccentButton("Добавить", ::add, enabled = text.isNotBlank())
        }
    }
}
