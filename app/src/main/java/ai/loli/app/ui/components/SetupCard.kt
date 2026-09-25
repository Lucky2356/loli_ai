package ai.loli.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.loli.app.AppContainer
import ai.loli.app.device.BackgroundLauncher
import ai.loli.app.device.LoliAccessibilityService
import ai.loli.app.ui.screens.AssistantGuideDialog
import ai.loli.app.ui.screens.isDefaultAssistant

/** «Настройте Лоли»: чего не хватает для полной работы — каждый пункт включается одним нажатием. */
@Composable
fun SetupCard(c: AppContainer, name: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("loli_ui", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("setup_dismissed", false)) }
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    var guide by remember { mutableStateOf(false) }
    val mic = remember(tick, refresh) { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val overlay = remember(tick) { c.launcher.canLaunchFromBackground() }
    val a11y = remember(tick) { LoliAccessibilityService.isEnabled }
    val assistant = remember(tick) { isDefaultAssistant(context) != false }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val todo = listOfNotNull(
        if (!mic) Triple(Icons.Rounded.Mic, "Разрешить микрофон") { micLauncher.launch(Manifest.permission.RECORD_AUDIO) } else null,
        if (!assistant) Triple(Icons.Rounded.TouchApp, "Сделать $name ассистентом") { guide = true } else null,
        if (!overlay) Triple(Icons.Rounded.Layers, "Работа поверх приложений") { runCatching { context.startActivity(BackgroundLauncher.overlaySettings(context)) }; Unit } else null,
        if (!a11y) Triple(Icons.Rounded.VolumeUp, "Кнопки громкости и системные команды") {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }; Unit
        } else null,
    )
    if (guide) AssistantGuideDialog(name) { guide = false }
    AnimatedVisibility(!dismissed && todo.isNotEmpty(), exit = shrinkVertically() + fadeOut()) {
        Surface(shape = MaterialTheme.shapes.large, color = groupColor(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Настройте $name полностью", style = MaterialTheme.typography.titleSmall)
                        Text("Осталось шагов: ${todo.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { dismissed = true; prefs.edit().putBoolean("setup_dismissed", true).apply() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Скрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                todo.forEach { (icon, title, action) ->
                    RowItem(title = title, icon = icon, chevron = true, onClick = action)
                }
            }
        }
    }
}
