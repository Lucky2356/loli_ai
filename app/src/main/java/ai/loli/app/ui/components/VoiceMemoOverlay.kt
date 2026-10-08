package ai.loli.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.voice.VoiceMemoRecorder
import kotlinx.coroutines.delay

/** Панель записи голосовой заметки внизу экрана: время, уровень звука, текст по мере расшифровки, «Готово». */
@Composable
fun VoiceMemoOverlay(c: AppContainer) {
    val state by c.voiceMemo.state.collectAsStateWithLifecycle()
    if (state is VoiceMemoRecorder.State.Idle) return
    // Итог показываем несколько секунд и убираем.
    LaunchedEffect(state) {
        if (state is VoiceMemoRecorder.State.Saved || state is VoiceMemoRecorder.State.Failed) { delay(4000); c.voiceMemo.dismiss() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = state) {
                    is VoiceMemoRecorder.State.Recording -> {
                        Text("Запись · ${s.seconds / 60}:${"%02d".format(s.seconds % 60)}", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { s.level }, modifier = Modifier.fillMaxWidth())
                        Text(
                            s.text.ifBlank { "Говорите — закончу сама, когда замолчите." }.takeLast(240),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PrimaryButton("Готово", { c.voiceMemo.stop() }) }
                    }
                    VoiceMemoRecorder.State.Saving -> Text("Сохраняю…", style = MaterialTheme.typography.titleMedium)
                    is VoiceMemoRecorder.State.Saved -> Text("Сохранила: ${s.title}. Она в «Записи → Заметки».", style = MaterialTheme.typography.bodyLarge)
                    is VoiceMemoRecorder.State.Failed -> Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    VoiceMemoRecorder.State.Idle -> Unit
                }
            }
        }
    }
}
