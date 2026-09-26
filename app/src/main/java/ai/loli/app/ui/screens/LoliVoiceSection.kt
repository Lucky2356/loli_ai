package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.voice.LoliVoiceModels
import kotlinx.coroutines.launch

/**
 * «Кто говорит»: встроенный «Голос Лоли» (одинаково по-русски на любом телефоне)
 * или синтезатор телефона. Встроенные голоса скачиваются один раз (~65 МБ каждый).
 */
@Composable
fun LoliVoiceSection(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sample = "Привет! Я ${s.assistantName}. Так звучит мой голос."

    SectionLabel("Кто говорит")
    Group {
        val modes = listOf(
            Triple("auto", "Автоматически", "Голос Лоли, если скачан; иначе — синтезатор телефона, если он точно говорит по-русски"),
            Triple("loli", "Голос Лоли", "Встроенный: одинаково по-русски на любом телефоне, без интернета"),
            Triple("system", "Синтезатор телефона", "Google, Samsung и др. На некоторых телефонах читает русский неправильно"),
        )
        modes.forEachIndexed { i, (id, title, sub) ->
            if (i > 0) GroupDivider(inset = 52.dp)
            RadioRow(title, sub, s.voiceMode == id) { scope.launch { c.settings.setVoiceMode(id) } }
        }
    }

    SectionLabel("Голос Лоли")
    Group {
        LoliVoiceModels.VOICES.forEachIndexed { i, v ->
            if (i > 0) GroupDivider(inset = 16.dp)
            val state by c.loliVoiceModels.state(v.id).collectAsStateWithLifecycle()
            val selected = s.loliVoice == v.id
            val status = when (val st = state) {
                LoliVoiceModels.State.Ready -> if (selected) "Выбран · работает без интернета" else "Скачан"
                LoliVoiceModels.State.Missing -> "${v.subtitle} · ~${v.bytes / 1_000_000} МБ"
                is LoliVoiceModels.State.Downloading -> "Скачиваю… ${(st.progress * 100).toInt()}%"
                LoliVoiceModels.State.Installing -> "Устанавливаю…"
                is LoliVoiceModels.State.Failed -> "Не скачался: ${st.message}. Нажмите, чтобы повторить"
            }
            RowItem(
                title = v.title + if (selected) " ✓" else "",
                subtitle = "${v.subtitle} · $status",
                onClick = {
                    scope.launch {
                        c.settings.setLoliVoice(v.id)
                        if (s.voiceMode == "system") c.settings.setVoiceMode("auto")
                        if (c.loliVoiceModels.download(v.id)) c.loliVoice.speak(sample, voice = v.id)
                    }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (state) {
                            is LoliVoiceModels.State.Downloading, LoliVoiceModels.State.Installing ->
                                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(22.dp), strokeWidth = 2.dp)
                            LoliVoiceModels.State.Ready -> TextButton(onClick = { scope.launch { c.loliVoice.speak(sample, voice = v.id) } }) { Text("Слушать") }
                            else -> TextButton(onClick = {
                                scope.launch {
                                    c.settings.setLoliVoice(v.id)
                                    if (c.loliVoiceModels.download(v.id)) c.loliVoice.speak(sample, voice = v.id)
                                }
                            }) { Text("Скачать") }
                        }
                    }
                },
            )
        }
    }
    Hint(
        "Голоса скачиваются один раз из релизов Лоли на GitHub (оттуда же приходят обновления) и проверяются по контрольной сумме. " +
            "Лицензии: Денис и Дмитрий — CC0, Руслан — только некоммерческое использование, Ирина — датасет RHVoice.",
    )
    Text(
        "Стили звучания и скорость ниже работают и для голоса Лоли.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}
