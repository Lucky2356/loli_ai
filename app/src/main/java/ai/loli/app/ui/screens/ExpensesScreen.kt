package ai.loli.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
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
import ai.loli.core.assistant.RuFormat
import ai.loli.core.finance.ExpenseAnalytics
import ai.loli.core.finance.PeriodPreset
import ai.loli.core.model.Expense
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import kotlinx.coroutines.launch
import java.time.LocalDate

private val palette = listOf(
    Color(0xFF6366F1), Color(0xFF2DD4BF), Color(0xFFF59E0B), Color(0xFFEC4899),
    Color(0xFF3B82F6), Color(0xFF84CC16), Color(0xFFF97316), Color(0xFFA855F7),
)

private val presets = listOf(
    PeriodPreset.TODAY to "Сегодня", PeriodPreset.LAST_7_DAYS to "7 дней", PeriodPreset.THIS_MONTH to "Месяц",
    PeriodPreset.LAST_MONTH to "Прошлый", PeriodPreset.THIS_YEAR to "Год", PeriodPreset.ALL to "Всё",
)

@Composable
fun ExpensesScreen(c: AppContainer) {
    val all by remember { c.store.expenses.observe() }.collectAsStateWithLifecycle(emptyList())
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    var presetIndex by rememberSaveable { mutableIntStateOf(2) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    val scope = rememberCoroutineScope()
    val today = c.time.today()
    val range = ExpenseAnalytics.range(presets[presetIndex].first, today)
    val report = ExpenseAnalytics.report(all, range)

    LoliScreen(title = "Расходы", fab = { LoliFab(Icons.Rounded.Add, "Добавить расход") { adding = true } }) {
        item(key = "total") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(range.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(report.totalMinor, report.currency), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 2.dp))
                Text(
                    ExpenseAnalytics.plural(report.count, "операция", "операции", "операций") +
                        report.otherCurrencies.entries.joinToString("") { (cur, sum) -> " · + ${Money.format(sum, cur)}" },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "periods") { Pills(presets.map { it.second }, presetIndex, { presetIndex = it }, Modifier.padding(vertical = 16.dp)) }
        if (report.byCategory.isNotEmpty() && report.totalMinor > 0) {
            item(key = "bar") {
                // Одна полоса из долей категорий — компактнее десятка отдельных индикаторов.
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    report.byCategory.forEachIndexed { i, cat ->
                        val w = cat.amountMinor.toFloat() / report.totalMinor
                        if (w > 0f) Box(Modifier.weight(w).height(10.dp).background(palette[i % palette.size]))
                    }
                }
            }
            item(key = "cats") {
                Group(Modifier.padding(top = 16.dp)) {
                    report.byCategory.forEachIndexed { i, cat ->
                        if (i > 0) GroupDivider(inset = 44.dp)
                        val share = (cat.amountMinor * 100 / report.totalMinor).toInt()
                        RowItem(
                            title = cat.category,
                            leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(palette[i % palette.size])) },
                            trailing = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Money.format(cat.amountMinor, report.currency), style = MaterialTheme.typography.bodyLarge)
                                    Text("$share%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (report.items.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Rounded.Payments, "Расходов за период нет", "Скажите: «$name, потратила 500 рублей на продукты»") }
        } else {
            report.items.groupBy { it.occurredOn }.toSortedMap(compareByDescending<LocalDate> { it }).forEach { (day, items) ->
                item(key = "day-$day") {
                    SectionLabel(RuFormat.date(day, today), trailing = {
                        Text(Money.format(items.filter { it.currency == report.currency }.sumOf { it.amountMinor }, report.currency),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                }
                item(key = "items-$day") {
                    Group {
                        items.forEachIndexed { i, e ->
                            if (i > 0) GroupDivider()
                            RowItem(
                                title = e.description.ifBlank { e.category },
                                subtitle = if (e.description.isNotBlank()) e.category else null,
                                onClick = { editing = e },
                                trailing = { Text(Money.format(e.amountMinor, e.currency), style = MaterialTheme.typography.bodyLarge) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        ExpenseEditor(today, null, onDismiss = { adding = false }, onSave = { amount, category, description, date ->
            scope.launch { c.store.expenses.create(Money.toMinor(amount), "RUB", category, description, date) }
            adding = false
        }, onDelete = {})
    }
    editing?.let { e ->
        ExpenseEditor(today, e, onDismiss = { editing = null }, onSave = { amount, category, description, date ->
            scope.launch { c.store.expenses.update(e.copy(amountMinor = Money.toMinor(amount), category = category, description = description, occurredOn = date)) }
            editing = null
        }, onDelete = { scope.launch { c.store.expenses.delete(e.id) }; editing = null })
    }
}

@Composable
private fun ExpenseEditor(
    today: LocalDate,
    initial: Expense?,
    onDismiss: () -> Unit,
    onSave: (Double, String, String, LocalDate) -> Unit,
    onDelete: () -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(initial?.let { (it.amountMinor / 100.0).let { v -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString() } }.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial?.category.orEmpty()) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var askDelete by remember { mutableStateOf(false) }
    val value = amount.replace(',', '.').replace(" ", "").toDoubleOrNull()
    val categories = listOf("Авто") + ExpenseCategories.all
    EditorSheet(if (initial == null) "Новый расход" else "Расход", onDismiss) {
        LoliField(amount, { amount = it }, "Сумма, ₽", keyboardType = KeyboardType.Decimal)
        LoliField(description, { description = it }, "На что")
        Text("Категория", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Pills(categories, if (category.isBlank()) 0 else categories.indexOf(category).coerceAtLeast(0), { i -> category = if (i == 0) "" else categories[i] })
        if (initial == null) {
            Pills(listOf("Сегодня", "Вчера", "Позавчера"), dayOffset, { dayOffset = it })
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (initial != null) SecondaryButton("Удалить", { askDelete = true }, danger = true)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Сохранить", {
                val cat = category.ifBlank { ExpenseCategories.categorize(description) }
                onSave(value ?: 0.0, cat, description.trim(), initial?.occurredOn ?: today.minusDays(dayOffset.toLong()))
            }, enabled = value != null && value > 0)
        }
    }
    if (askDelete && initial != null) ConfirmDialog("Удалить расход?", "${Money.format(initial.amountMinor, initial.currency)} — ${initial.category}", "Удалить",
        onConfirm = onDelete, onDismiss = { askDelete = false })
}
