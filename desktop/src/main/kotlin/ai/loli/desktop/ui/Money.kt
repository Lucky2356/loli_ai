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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Payments
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
import ai.loli.core.finance.ExpenseAnalytics
import ai.loli.core.finance.PeriodPreset
import ai.loli.core.model.Expense
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Цвета категорий — те же, что на телефоне. */
private val CATEGORY_COLORS = listOf(
    Color(0xFF6366F1), Color(0xFF14B8A6), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF10B981),
    Color(0xFFEC4899), Color(0xFF3B82F6), Color(0xFF84CC16), Color(0xFFF97316), Color(0xFFA855F7),
)

private val PRESETS = listOf(
    PeriodPreset.TODAY to "Сегодня", PeriodPreset.LAST_7_DAYS to "7 дней", PeriodPreset.THIS_MONTH to "Месяц",
    PeriodPreset.LAST_MONTH to "Прошлый месяц", PeriodPreset.THIS_YEAR to "Год", PeriodPreset.ALL to "Всё",
)

/** Вкладка «Финансы» — как «Расходы» на телефоне: период, сумма, доли категорий, траты по дням. */
@Composable
fun MoneyScreen(c: DesktopContainer) {
    val p = palette
    val all by remember { c.store.expenses.observe() }.collectAsState(emptyList())
    val name = c.settings.state.collectAsState().value.assistantName
    var preset by remember { mutableStateOf(2) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val range = remember(preset, today) { ExpenseAnalytics.range(PRESETS[preset].first, today) }
    val report = remember(all, range) { ExpenseAnalytics.report(all, range) }
    WithToast { toast ->
        Page {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    PageHeader("Финансы", range.label.replaceFirstChar { it.uppercase() }) {
                        AccentButton("Новая трата", { adding = true }, icon = Icons.Rounded.Add)
                    }
                    QuickAddBar(c, listOf(QuickAdd.Kind.EXPENSE to "Трата"), mapOf(QuickAdd.Kind.EXPENSE to "Быстро: «кофе 250», «такси 640 вчера», «зарплата 80000» — и Enter"), toast)
                }
                item { Segmented(PRESETS.mapIndexed { i, it -> i to it.second }, preset, { preset = it }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        StatCard(
                            "Потрачено", Money.format(report.totalMinor, report.currency), Modifier.weight(1f),
                            (ExpenseAnalytics.plural(report.count, "операция", "операции", "операций") +
                                report.otherCurrencies.entries.joinToString("") { (cur, sum) -> " · + ${Money.format(sum, cur)}" }) to p.muted,
                        )
                        StatCard("Больше всего", report.byCategory.firstOrNull()?.category ?: "—", Modifier.weight(1f),
                            report.byCategory.firstOrNull()?.let { Money.format(it.amountMinor, report.currency) to p.muted })
                        val days = (range.to.toEpochDay() - range.from.toEpochDay() + 1).coerceAtLeast(1)
                        StatCard("В среднем за день", if (preset == 5 || report.count == 0) "—" else Money.format(report.totalMinor / days, report.currency), Modifier.weight(1f), null)
                    }
                }
                if (report.byCategory.isNotEmpty() && report.totalMinor > 0) item {
                    Panel {
                        SectionLabel("По категориям")
                        // Одна полоса из долей категорий — как на телефоне.
                        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                            report.byCategory.forEachIndexed { i, cat ->
                                val w = cat.amountMinor.toFloat() / report.totalMinor
                                if (w > 0f) Box(Modifier.weight(w).height(10.dp).background(CATEGORY_COLORS[i % CATEGORY_COLORS.size]))
                            }
                        }
                        Column(Modifier.padding(top = 10.dp)) {
                            report.byCategory.forEachIndexed { i, cat ->
                                val share = (cat.amountMinor * 100 / report.totalMinor).toInt()
                                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(CATEGORY_COLORS[i % CATEGORY_COLORS.size]))
                                    Text(cat.category, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                    Text("$share%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 14.dp))
                                    Text(Money.format(cat.amountMinor, report.currency), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                if (report.items.isEmpty()) item {
                    EmptyState(Icons.Rounded.Payments, "Трат за период нет", "Добавьте выше или скажите: «$name, потратила 500 рублей на продукты».")
                } else report.items.groupBy { it.occurredOn }.toSortedMap(compareByDescending<LocalDate> { it }).forEach { (day, items) ->
                    item(key = "day-$day") {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(RuFormat.date(day, today).removePrefix("в "), Modifier.weight(1f))
                                Text(
                                    Money.format(items.filter { it.currency == report.currency && it.category != ExpenseCategories.INCOME }.sumOf { it.amountMinor }, report.currency),
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 10.dp),
                                )
                            }
                            items.forEachIndexed { i, e ->
                                if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                                ExpenseRow(e) { editing = e }
                            }
                        }
                    }
                }
            }
        }
        if (adding) ExpenseEditor(today, null, onDismiss = { adding = false }, onSave = { amount, category, description, date ->
            scope.launch { c.store.expenses.create(Money.toMinor(amount), "RUB", category, description, date); toast(true to "Записала трату.") }
        }, onDelete = {})
        editing?.let { e ->
            ExpenseEditor(today, e, onDismiss = { editing = null }, onSave = { amount, category, description, date ->
                scope.launch {
                    c.store.expenses.update(e.copy(amountMinor = Money.toMinor(amount), category = category, description = description, occurredOn = date))
                    toast(true to "Сохранила.")
                }
            }, onDelete = { scope.launch { c.store.expenses.delete(e.id); toast(true to "Удалила.") } })
        }
    }
}

@Composable
private fun ExpenseRow(e: Expense, onClick: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val income = e.category == ExpenseCategories.INCOME
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hovered) p.surfaceHigh else Color.Transparent)
            .hoverable(src).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.description.ifBlank { e.category }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.description.isNotBlank()) Text(e.category, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            (if (income) "+" else "−") + Money.format(e.amountMinor, e.currency),
            style = MaterialTheme.typography.titleSmall.copy(color = if (income) p.success else p.text),
        )
    }
}

/** Трата: сумма, на что, категория (или «Авто» — по описанию), день. */
@Composable
private fun ExpenseEditor(
    today: LocalDate, initial: Expense?, onDismiss: () -> Unit,
    onSave: (Double, String, String, LocalDate) -> Unit, onDelete: () -> Unit,
) {
    var amount by remember {
        mutableStateOf(initial?.let { (it.amountMinor / 100.0).let { v -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString() } }.orEmpty())
    }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category.orEmpty()) }
    var dayOffset by remember { mutableStateOf(0) }
    var askDelete by remember { mutableStateOf(false) }
    val value = amount.replace(',', '.').replace(" ", "").toDoubleOrNull()
    val categories = listOf("") + ExpenseCategories.all + (initial?.category?.takeIf { it.isNotBlank() && it !in ExpenseCategories.all }?.let { listOf(it) } ?: emptyList())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun save() {
        if (value == null || value <= 0) return
        val cat = category.ifBlank { ExpenseCategories.categorize(description) }
        onSave(value, cat, description.trim(), initial?.occurredOn ?: today.minusDays(dayOffset.toLong()))
        onDismiss()
    }
    LoliDialog(if (initial == null) "Новая трата" else "Трата", onDismiss, width = 640.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Field(amount, { amount = it }, "Сумма, ₽", "500", modifier = Modifier.width(160.dp), focusRequester = focus, onSubmit = ::save)
            Field(description, { description = it }, "На что", "Продукты, такси, кафе…", modifier = Modifier.weight(1f), onSubmit = ::save)
        }
        Text("Категория", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowChips(categories.map { it to it.ifBlank { "Авто" } }, category, { category = it })
        if (initial == null) {
            Text("Когда", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Segmented(listOf(0 to "Сегодня", 1 to "Вчера", 2 to "Позавчера"), dayOffset, { dayOffset = it })
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (initial != null) GhostButton("Удалить", { askDelete = true }, icon = Icons.Rounded.DeleteOutline, danger = true)
            Box(Modifier.weight(1f))
            GhostButton("Отмена", onDismiss)
            Box(Modifier.width(10.dp))
            AccentButton("Сохранить", ::save, enabled = value != null && value > 0)
        }
    }
    if (askDelete && initial != null) ConfirmDialog(
        "Удалить трату?", "${Money.format(initial.amountMinor, initial.currency)} — ${initial.category}", "Удалить",
        onConfirm = { onDelete(); onDismiss() }, onDismiss = { askDelete = false },
    )
}
