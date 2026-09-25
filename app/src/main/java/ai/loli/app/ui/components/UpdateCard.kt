package ai.loli.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.update.UpdateState
import kotlinx.coroutines.launch

/** Карточка «Доступна новая версия»: скачать и установить одним нажатием, с прогрессом. */
@Composable
fun UpdateCard(c: AppContainer, modifier: Modifier = Modifier) {
    val state by c.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Installing
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        val s = state
                        val title = when (s) {
                            is UpdateState.Available -> "Доступна версия ${s.info.version}"
                            is UpdateState.Downloading -> "Скачиваю ${s.info.version} · ${(s.progress * 100).toInt()}%"
                            is UpdateState.Installing -> "Устанавливаю ${s.info.version}…"
                            else -> ""
                        }
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Сейчас ${c.updates.currentVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                    }
                    val s = state
                    if (s is UpdateState.Available) TextButton(onClick = {
                        if (!c.updates.canInstall()) runCatching { context.startActivity(c.updates.installPermissionIntent()) }
                        else c.appScope.launch { c.updates.downloadAndInstall(s.info) }
                    }) { Text("Обновить") }
                }
                (state as? UpdateState.Downloading)?.let { d ->
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        }
    }
}
