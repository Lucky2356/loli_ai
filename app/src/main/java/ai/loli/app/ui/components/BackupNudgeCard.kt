package ai.loli.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.backup.BackupNudge
import ai.loli.core.auth.AuthState

/** Одна строка на главном экране: данные только на телефоне, копии давно не было. Показывается редко и скрывается на неделю. */
@Composable
fun BackupNudgeCard(c: AppContainer, name: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    val auth by c.auth.state.collectAsStateWithLifecycle()
    var hidden by remember { mutableStateOf(false) }
    if (hidden || !BackupNudge.due(context, signedIn = auth is AuthState.SignedIn)) return
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text("Сохраните резервную копию", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "Ваши записи хранятся только в этом телефоне. Если с ним что-то случится, $name их не вернёт. Копия — один файл с паролем.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            Row {
                TextButton(onClick = onOpen) { Text("Сохранить") }
                TextButton(onClick = { BackupNudge.hideForWeek(context); hidden = true }) { Text("Позже") }
            }
        }
    }
}
