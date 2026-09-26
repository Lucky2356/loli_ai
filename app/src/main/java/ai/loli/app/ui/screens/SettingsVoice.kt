package ai.loli.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.settings.SttMode
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.MoreToggle
import ai.loli.app.ui.components.Pills
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.ui.components.SwitchItem
import ai.loli.app.ui.components.rememberResumeTick
import ai.loli.app.voice.AndroidTtsProvider
import ai.loli.app.voice.VoskModelManager
import ai.loli.app.voice.WakeWordService
import kotlinx.coroutines.launch

/**
 * «Голос и речь»: сверху — то, что меняют обычно (кто говорит, характер голоса, разговор, сценарии);
 * синтезатор телефона, скорость, распознавание и офлайн-модель — в свёрнутом «Дополнительно».
 */
@Composable
internal fun VoiceSpeechPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val model by c.voskModels.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    if (guide) AssistantGuideDialog(s.assistantName) { guide = false }
    val systemMode = s.voiceMode == "system" || (s.voiceMode == "auto" && !c.loliVoiceModels.isReady(c.loliVoiceModels.effective(s.loliVoice)))
    var voices by remember { mutableStateOf<List<AndroidTtsProvider.VoiceOption>?>(null) }
    var engines by remember { mutableStateOf<List<AndroidTtsProvider.EngineOption>>(emptyList()) }
    // Синтезатор телефона проверяем, только если он сейчас говорит или открыт «Дополнительно».
    LaunchedEffect(s.ttsEngine, resume, advanced, systemMode) {
        if (systemMode || advanced) { voices = null; c.tts.recheck(); engines = c.tts.engines(); voices = c.tts.russianVoices() }
    }
    val ru by c.tts.russianStatus.collectAsStateWithLifecycle()
    val engineLabel by c.tts.engineLabel.collectAsStateWithLifecycle()
    val sample = "Привет! Я ${s.assistantName}. Так звучит мой голос."
    val isAssistant = remember(resume) { isDefaultAssistant(context) }
    val services = remember(resume, advanced) { if (advanced) c.systemStt.services().map { c.systemStt.serviceLabel(it) }.distinct() else emptyList() }
    LaunchedEffect(Unit) { if (c.voskModels.isBundled) c.voskModels.ensureReady() }

    LoliScreen(title = "Голос и речь", onBack = onBack) {
        if (!s.ttsEnabled) item(key = "off") {
            Group(Modifier.padding(top = 4.dp)) {
                SwitchItem("Отвечать голосом", "Сейчас ${s.assistantName} отвечает только текстом", false, icon = Icons.Rounded.RecordVoiceOver) { v ->
                    scope.launch { c.settings.setTts(v) }
                }
            }
        }
        if (s.ttsEnabled) item(key = "loli-voice") { LoliVoiceSection(c) }
        if (s.ttsEnabled) item(key = "style") {
            if (systemMode && (ru == AndroidTtsProvider.RuStatus.NO_RUSSIAN || ru == AndroidTtsProvider.RuStatus.MISSING_DATA)) {
                Surface(
                    onClick = { ai.loli.app.voice.RussianVoiceHelp.act(context, ru, c.tts.activeEngine()) },
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(ai.loli.app.voice.RussianVoiceHelp.title(ru), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(ai.loli.app.voice.RussianVoiceHelp.hint(ru), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            SectionLabel("Характер голоса")
            Group {
                VOICE_STYLES.forEachIndexed { i, st ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    val selected = kotlin.math.abs(s.speechPitch - st.pitch) < 0.03f && kotlin.math.abs(s.speechRate - st.rate) < 0.03f
                    RadioRow(st.title, st.subtitle, selected) {
                        scope.launch {
                            c.settings.setVoiceStyle(st.pitch, st.rate)
                            if (!systemMode) c.loliVoice.speak(sample, rateOverride = st.rate, pitchOverride = st.pitch)
                            else c.tts.preview(sample, s.voiceName, st.pitch, st.rate)
                        }
                    }
                }
            }
        }
        item(key = "dialog") {
            SectionLabel("Разговор")
            Group {
                SwitchItem("Диалоговый режим", "После ответа продолжаю слушать без повторного имени, пока вы не скажете «хватит» или не замолчите",
                    s.dialogModeEnabled) { v -> scope.launch { c.settings.setDialogMode(v) } }
                GroupDivider()
                val appContext = LocalContext.current.applicationContext
                SwitchItem("Утренняя сводка", "В 8:30 — погода, задачи и напоминания на день", s.morningBrief) { v ->
                    scope.launch { c.settings.setMorningBrief(v); ai.loli.app.reminders.MorningBrief.schedule(appContext, v) }
                }
            }
        }
        item(key = "routines") { RoutinesSection(c) }
        item(key = "more") { MoreToggle(advanced, { advanced = !advanced }) }

        if (advanced && s.ttsEnabled) item(key = "rate") {
            SectionLabel("Скорость · ${"%.1f".format(s.speechRate)}×   Высота · ${"%.1f".format(s.speechPitch)}")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Скорость", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = s.speechRate, onValueChange = { v -> scope.launch { c.settings.setSpeechRate(v) } }, valueRange = 0.5f..2f)
                    Text("Высота голоса", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = s.speechPitch, onValueChange = { v -> scope.launch { c.settings.setSpeechPitch(v) } }, valueRange = 0.5f..2f)
                    TextButton(onClick = { scope.launch { c.speech.speak(sample) } }) { Text("Прослушать") }
                }
            }
        }
        if (advanced && s.ttsEnabled) item(key = "system-voice") {
            SectionLabel("Синтезатор телефона")
            Hint(
                if (engineLabel.isNotBlank()) "$engineLabel · " + (if (ru == AndroidTtsProvider.RuStatus.OK) "русский есть" else "русского нет") +
                    ". Используется, если выбрано «Синтезатор телефона» или голос Лоли не скачан."
                else "Используется, если выбрано «Синтезатор телефона» или голос Лоли не скачан.",
            )
            if (engines.size > 1) Group {
                val current = s.ttsEngine.ifBlank { c.tts.defaultEngine() }
                engines.forEachIndexed { i, e ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(e.label, if (e.name == c.tts.defaultEngine()) "Системный по умолчанию" else e.name, current == e.name) {
                        scope.launch { c.settings.setTtsEngine(if (e.name == c.tts.defaultEngine()) "" else e.name) }
                    }
                }
            }
            SectionLabel("Голос синтезатора")
            Group {
                RadioRow("Как в системе", "Голос по умолчанию синтезатора речи", s.voiceName.isBlank()) {
                    scope.launch { c.settings.setVoiceName(""); c.tts.speak(sample) }
                }
                val list = voices
                if (list == null) {
                    GroupDivider(inset = 52.dp)
                    Hint("Загружаю голоса…")
                } else list.forEach { v ->
                    GroupDivider(inset = 52.dp)
                    RadioRow(v.title, v.subtitle, s.voiceName == v.name) {
                        scope.launch { c.settings.setVoiceName(v.name); c.tts.preview(sample, v.name) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                // «Скачать голоса» работает только у синтезатора Google.
                if (c.tts.activeEngine() == AndroidTtsProvider.GOOGLE_TTS) TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                .setPackage(AndroidTtsProvider.GOOGLE_TTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Скачать голоса") }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }) { Text("Настройки синтезатора") }
            }
        }
        if (advanced) item(key = "stt") {
            SectionLabel("Распознавание речи")
            Group {
                SttMode.entries.forEachIndexed { i, m ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(m.title, m.hint, s.sttMode == m) { scope.launch { c.settings.setSttMode(m) } }
                }
            }
            Hint(
                if (services.isEmpty()) "Системных сервисов распознавания не найдено — используется офлайн-модель."
                else "Найдено на телефоне: ${services.joinToString()}. Если один не работает, ${s.assistantName} сама переключится на другой или на офлайн-модель.",
            )
            SectionLabel("Пауза до конца фразы")
            Group {
                ai.loli.app.settings.SpeechPause.entries.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(p.title, p.hint, s.speechPause == p) { scope.launch { c.settings.setSpeechPause(p) } }
                }
            }
            SectionLabel("Офлайн-модель русской речи")
            Group {
                when (val m = model) {
                    VoskModelManager.State.Ready -> RowItem(
                        title = "Установлена", subtitle = if (c.voskModels.isBundled) "Встроена в приложение, работает без интернета" else "Работает без интернета",
                        icon = Icons.Rounded.CheckCircle,
                        trailing = {
                            if (!c.voskModels.isBundled) {
                                TextButton(onClick = { WakeWordService.stop(context); c.voskEngine.release(); c.voskModels.delete(); scope.launch { c.settings.setWakeWord(false) } }) { Text("Удалить") }
                            }
                        },
                    )
                    is VoskModelManager.State.Installing -> ProgressRow("Устанавливаю встроенную модель", m.progress)
                    is VoskModelManager.State.Downloading -> ProgressRow("Скачиваю модель (~45 МБ)", m.progress)
                    VoskModelManager.State.Missing -> RowItem(
                        title = if (c.voskModels.isBundled) "Встроенная модель" else "Не установлена",
                        subtitle = if (c.voskModels.isBundled) "Будет установлена автоматически" else "Нужна для офлайн-распознавания и фонового «${s.assistantName}»",
                        icon = Icons.Rounded.RecordVoiceOver,
                        trailing = { TextButton(onClick = { c.appScope.launch { c.voskModels.download() } }) { Text(if (c.voskModels.isBundled) "Установить" else "Скачать") } },
                    )
                    is VoskModelManager.State.Failed -> RowItem(
                        title = "Не удалось установить", subtitle = m.message, icon = Icons.Rounded.RecordVoiceOver,
                        trailing = { TextButton(onClick = { c.appScope.launch { c.voskModels.download() } }) { Text("Повторить") } },
                    )
                }
            }
            SectionLabel("Ассистент телефона")
            Group {
                RowItem(
                    title = if (isAssistant == true) "${s.assistantName} — ассистент по умолчанию" else "Сделать ассистентом по умолчанию",
                    subtitle = if (isAssistant == true) "Долгое нажатие «Домой» или кнопки питания открывает ${s.assistantName}"
                    else "Откроются настройки: выберите «${s.assistantName}» в пункте «Цифровой ассистент»",
                    icon = Icons.Rounded.TouchApp, chevron = true,
                    onClick = { guide = true },
                )
            }
        }
    }
}

/** Ввод одного значения: имя, город. Пустое значение — сбросить. */
@Composable
internal fun TextDialog(
    title: String,
    initial: String,
    hint: String,
    presets: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LoliField(value, { value = it.take(40) }, title)
                if (presets.isNotEmpty()) Pills(presets, presets.indexOf(value), { value = presets[it] })
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Места для напоминаний «когда буду дома»: запомнить текущее место одним нажатием или забыть. */
@Composable
internal fun PlacesDialog(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val places = remember(tick) { c.geo.places() }
    val reminders = remember(tick) { c.geo.reminders() }
    fun save(name: String) {
        status = "Определяю, где вы…"
        scope.launch {
            status = runCatching { c.skillHost.savePlace(name) }.fold(
                onSuccess = { p -> if (p != null) "Запомнила: здесь — $name." else "Не удалось определить место. Включите геолокацию." },
                onFailure = { "Нужен доступ к геолокации." },
            )
            tick++
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
        title = { Text("Места") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Нужны для напоминаний «когда буду дома…» и «когда уйду с работы…». Хранятся только на телефоне.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                places.forEach { p ->
                    Row {
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            val n = reminders.count { it.place == p.name }
                            if (n > 0) Text("Напоминаний: $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { c.geo.removePlace(p.name); tick++ }) { Icon(Icons.Rounded.Delete, contentDescription = "Забыть") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { save("дом") }) { Text("Я сейчас дома") }
                    TextButton(onClick = { save("работа") }) { Text("Я на работе") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text("Можно и голосом: «запомни, здесь моя дача».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
