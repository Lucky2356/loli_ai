package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.DeleteButton
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.LoliTopBar
import ai.loli.app.ui.components.SectionTitle
import ai.loli.core.assistant.RuFormat
import ai.loli.core.finance.ExpenseAnalytics
import ai.loli.core.finance.PeriodPreset
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ExpensesScreen(c: AppContainer, onBack: (() -> Unit)?) {
    val all by remember { c.store.expenses.observe() }.collectAsStateWithLifecycle(emptyList())
    var preset by rememberSaveable { mutableStateOf(PeriodPreset.THIS_MONTH) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = c.time.today()
    val range = ExpenseAnalytics.range(preset, today)
    val report = ExpenseAnalytics.report(all, range)
    val presets = listOf(
        PeriodPreset.TODAY to "Сегодня", PeriodPreset.LAST_7_DAYS to "7 дней", PeriodPreset.THIS_MONTH to "Месяц",
        PeriodPreset.LAST_MONTH to "Прошлый месяц", PeriodPreset.THIS_YEAR to "Год", PeriodPreset.ALL to "Всё время",
    )
    Scaffold(
        topBar = { LoliTopBar("Расходы", onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить расход") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                    items(presets) { (p, label) -> FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(label) }) }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(range.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.format(report.totalMinor, report.currency), style = MaterialTheme.typography.displaySmall)
                        Text(ExpenseAnalytics.plural(report.count, "операция", "операции", "операций"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        report.otherCurrencies.forEach { (cur, sum) -> Text("+ ${Money.format(sum, cur)}") }
                    }
                }
            }
            if (report.byCategory.isNotEmpty()) {
                item { SectionTitle("По категориям") }
                items(report.byCategory) { cat ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row {
                            Text(cat.category, modifier = Modifier.weight(1f))
                            Text(Money.format(cat.amountMinor, report.currency))
                        }
                        LinearProgressIndicator(
                            progress = { if (report.totalMinor == 0L) 0f else cat.amountMinor.toFloat() / report.totalMinor },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
            item { SectionTitle("История") }
            if (report.items.isEmpty()) {
                item { EmptyState(Icons.Outlined.Payments, "Расходов за период нет", "Скажите: «Лоли, потратила 500 рублей на продукты»") }
            }
            items(report.items, key = { it.id }) { e ->
                ListItem(
                    headlineContent = { Text("${Money.format(e.amountMinor, e.currency)} — ${e.category}") },
                    supportingContent = { Text(listOf(RuFormat.date(e.occurredOn, today), e.description).filter { it.isNotBlank() }.joinToString(" · ")) },
                    trailingContent = { DeleteButton("Расход ${Money.format(e.amountMinor, e.currency)} будет удалён.") { scope.launch { c.store.expenses.delete(e.id) } } },
                )
            }
        }
    }
    if (adding) {
        AddExpenseDialog(today, onDismiss = { adding = false }) { amount, category, description, date ->
            scope.launch { c.store.expenses.create(Money.toMinor(amount), "RUB", category, description, date) }
            adding = false
        }
    }
}

@Composable
private fun AddExpenseDialog(today: LocalDate, onDismiss: () -> Unit, onSave: (Double, String, String, LocalDate) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var yesterday by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val value = amount.replace(',', '.').replace(" ", "").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый расход") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("На что") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedButton(onClick = { menu = true }) { Text(category.ifBlank { "Категория: авто" }) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Определить автоматически") }, onClick = { category = ""; menu = false })
                        ExpenseCategories.all.forEach { cat -> DropdownMenuItem(text = { Text(cat) }, onClick = { category = cat; menu = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("Сегодня") })
                    FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("Вчера") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null && value > 0, onClick = {
                val cat = category.ifBlank { ExpenseCategories.categorize(description) }
                onSave(value ?: 0.0, cat, description, if (yesterday) today.minusDays(1) else today)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
