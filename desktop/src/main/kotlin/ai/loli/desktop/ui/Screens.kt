package ai.loli.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Wallet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.loli.core.assistant.QuickAdd
import ai.loli.core.assistant.RuFormat
import ai.loli.core.model.Expense
import ai.loli.core.model.Note
import ai.loli.core.model.NoteKind
import ai.loli.core.model.Reminder
import ai.loli.core.model.TaskItem
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.desktop.DesktopContainer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Общая разметка страницы: отступы и ограничение ширины, чтобы на широком мониторе не растекалось. */
@Composable
internal fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) { content() }
    }
}

/**
 * Строка быстрого добавления: фраза → запись, без захода в чат. Enter или кнопка «Добавить».
 * Результат (или подсказка, если не поняла) показывается всплывающим сообщением.
 */
@Composable
internal fun QuickAddBar(
    c: DesktopContainer, kinds: List<Pair<QuickAdd.Kind, String>>, placeholders: Map<QuickAdd.Kind, String>,
    onResult: (Pair<Boolean, String>) -> Unit, modifier: Modifier = Modifier,
) {
    var kind by remember { mutableStateOf(kinds.first().first) }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (text.isBlank() || saving) return
        saving = true
        scope.launch {
            val r = c.quickAdd(kind, text)
            if (r.first) text = ""
            saving = false
            onResult(r)
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (kinds.size > 1) Segmented(kinds, kind, { kind = it })
        Field(text, { text = it }, null, placeholders[kind].orEmpty(), modifier = Modifier.weight(1f), onSubmit = ::submit)
        AccentButton(if (saving) "Сохраняю…" else "Добавить", ::submit, enabled = text.isNotBlank() && !saving, icon = Icons.Rounded.Add)
    }
}

/** Экран с всплывающим сообщением внизу. */
@Composable
internal fun WithToast(content: @Composable (show: (Pair<Boolean, String>) -> Unit) -> Unit) {
    var toast by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Box(Modifier.fillMaxSize()) {
        content { toast = it }
        Toast(toast, { toast = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}


@Composable
internal fun CheckCircle(checked: Boolean, onClick: () -> Unit) {
    val p = palette
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(if (checked) p.gradient else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
            .border(1.5.dp, if (checked) Color.Transparent else p.faint, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Icons.Rounded.Check, null, tint = p.onAccent, modifier = Modifier.size(14.dp)) }
}

@Composable
internal fun StatCard(title: String, value: String, modifier: Modifier, note: Pair<String, Color>?) {
    Panel(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (note != null) Text(note.first, style = MaterialTheme.typography.bodySmall.copy(color = note.second), modifier = Modifier.padding(top = 4.dp), maxLines = 2)
    }
}
