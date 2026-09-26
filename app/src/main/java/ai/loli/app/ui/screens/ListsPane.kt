package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.EditorSheet
import ai.loli.app.ui.components.EmptyState
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SectionLabel
import ai.loli.core.model.ShoppingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Вкладка «Покупки» в «Планах»: списки с галочками, сначала то, что ещё купить. */
fun LazyListScope.listsPane(c: AppContainer, items: List<ShoppingItem>, name: String, scope: CoroutineScope) {
    if (items.isEmpty()) {
        item(key = "lists-empty") {
            EmptyState(Icons.Rounded.ShoppingCart, "Список покупок пуст", "Скажите: «$name, добавь в покупки молоко, хлеб и яйца». Потом — «что купить?» и «купила молоко».")
        }
        return
    }
    items.groupBy { it.listName }.forEach { (list, rows) ->
        val left = rows.count { !it.done }
        item(key = "list-label-$list") {
            SectionLabel("$list · осталось $left", trailing = if (rows.any { it.done }) ({
                TextButton(onClick = { scope.launch { c.store.shopping.clear(list, onlyDone = true) } }) { Text("Убрать купленное") }
            }) else null)
        }
        item(key = "list-$list") {
            Group {
                rows.sortedWith(compareBy({ it.done }, { it.createdAt })).forEachIndexed { i, it ->
                    if (i > 0) GroupDivider(inset = 60.dp)
                    RowItem(
                        title = it.text,
                        titleColor = if (it.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        onClick = { scope.launch { c.store.shopping.setDone(it.id, !it.done) } },
                        leading = {
                            Icon(
                                if (it.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = if (it.done) "Вернуть" else "Куплено",
                                tint = if (it.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp).size(24.dp),
                            )
                        },
                        trailing = {
                            IconButton(onClick = { scope.launch { c.store.shopping.delete(it.id) } }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Добавление пунктов вручную: через запятую — сразу несколько. */
@Composable
fun ShoppingAdder(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(ShoppingItem.DEFAULT_LIST) }
    EditorSheet("В список", onDismiss = onDismiss) {
        LoliField(text, { text = it }, "Что добавить", placeholder = "Молоко, хлеб, яйца")
        LoliField(list, { list = it }, "Список", supporting = "Например: «Покупки», «В дорогу», «На дачу»")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            PrimaryButton("Добавить", {
                val items = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) scope.launch { c.store.shopping.add(list.trim().ifEmpty { ShoppingItem.DEFAULT_LIST }, items) }
                onDismiss()
            }, enabled = text.isNotBlank())
        }
    }
}
