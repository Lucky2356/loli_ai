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
import ai.loli.app.diagnostics.Diagnostics

/** После сбоя: «Лоли неожиданно закрылась — отправить отчёт?». Ничего не уходит без нажатия. */
@Composable
fun CrashCard(name: String) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(Diagnostics.pendingCrash(context)) }
    val text = report ?: return
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text("$name неожиданно закрылась", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Отправьте отчёт — так ошибку исправят быстрее. В нём только модель телефона и место ошибки в коде, без ваших записей.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            Row {
                TextButton(onClick = {
                    runCatching { context.startActivity(Diagnostics.issueIntent("Сбой: " + text.lineSequence().drop(3).firstOrNull().orEmpty().take(80), "```\n$text\n```")) }
                        .onFailure { runCatching { context.startActivity(Diagnostics.shareIntent("Отчёт о сбое $name", text)) } }
                    Diagnostics.clearCrash(context); report = null
                }) { Text("Отправить") }
                TextButton(onClick = { runCatching { context.startActivity(Diagnostics.shareIntent("Отчёт о сбое $name", text)) }; Diagnostics.clearCrash(context); report = null }) { Text("Поделиться") }
                TextButton(onClick = { Diagnostics.clearCrash(context); report = null }) { Text("Не надо") }
            }
        }
    }
}
