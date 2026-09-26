package ai.loli.app.ui.screens

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.EditorSheet
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.core.model.SecretNote
import kotlinx.coroutines.launch

/** Строка «Секретные заметки» в «Записях»: открывает их только после отпечатка или PIN-кода. */
@Composable
fun SecretNotesEntry(c: AppContainer) {
    val activity = LocalActivity.current
    val notes by remember { c.store.secrets.observe() }.collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf(false) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) open = true
    }
    Group(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        RowItem(
            title = "Секретные заметки",
            subtitle = if (notes.isEmpty()) "Только на этом телефоне, по отпечатку. Не уходят в облако и AI" else "${notes.size} · открываются по отпечатку",
            icon = Icons.Rounded.Lock, chevron = true,
            onClick = {
                val a = activity
                if (a == null) open = true
                else c.appLock.confirm(a, "Секретные заметки", onSuccess = { open = true }, legacy = { legacy.launch(it) })
            },
        )
    }
    if (open) SecretNotesSheet(c, notes) { open = false }
}

@Composable
private fun SecretNotesSheet(c: AppContainer, notes: List<SecretNote>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SecretNote?>(null) }
    var creating by remember { mutableStateOf(false) }
    EditorSheet("Секретные заметки", onDismiss = onDismiss) {
        if (editing != null || creating) {
            val note = editing
            var title by remember(note) { mutableStateOf(note?.title.orEmpty()) }
            var content by remember(note) { mutableStateOf(note?.content.orEmpty()) }
            LoliField(title, { title = it }, "Название")
            LoliField(content, { content = it }, "Текст", singleLine = false, minLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (note != null) SecondaryButton("Удалить", { scope.launch { c.store.secrets.delete(note.id) }; editing = null }, danger = true)
                Spacer(Modifier.weight(1f))
                PrimaryButton("Сохранить", {
                    scope.launch { c.store.secrets.save(title.ifBlank { content.take(40) }, content, note?.id) }
                    editing = null; creating = false
                }, enabled = title.isNotBlank() || content.isNotBlank())
            }
        } else {
            if (notes.isEmpty()) Hint("Пока пусто. Скажите: «Лоли, запиши секретную заметку …» или нажмите «Новая».")
            else Group {
                notes.forEachIndexed { i, n ->
                    if (i > 0) GroupDivider()
                    RowItem(title = n.title, subtitle = n.content.take(120), onClick = { editing = n })
                }
            }
            Text(
                "Хранятся только в зашифрованной базе этого телефона: не синхронизируются, не попадают в поиск и не отправляются в AI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Spacer(Modifier.weight(1f))
                PrimaryButton("Новая", { creating = true })
            }
        }
    }
}
