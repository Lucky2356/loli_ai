package ai.loli.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp
import ai.loli.core.ai.AIConfig
import ai.loli.core.ai.AIProviderFactory
import ai.loli.core.ai.AIProviderType
import ai.loli.core.assistant.Persona
import ai.loli.desktop.Autostart
import ai.loli.desktop.DesktopContainer
import ai.loli.desktop.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(c: DesktopContainer) {
    val p = palette
    val s by c.settings.state.collectAsState()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.width(760.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { PageHeader("Настройки", "Всё хранится на этом компьютере") }
            item { AiSection(c, s) }
            item { VoiceSection(c, s) }
            item { StartupSection(c) }
            item {
                Panel {
                    SectionLabel("Профиль")
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Field(s.assistantName, { v -> c.settings.update { it.copy(assistantName = v.take(30)) } }, "Имя ассистента", "Лоли", modifier = Modifier.weight(1f))
                        Field(s.userName, { v -> c.settings.update { it.copy(userName = v.take(40)) } }, "Как вас называть", "Например, Аня", modifier = Modifier.weight(1f))
                    }
                    Field(s.city, { v -> c.settings.update { it.copy(city = v.take(60)) } }, "Город для погоды", "Например, Казань", modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                }
            }
            item {
                Panel {
                    SectionLabel("Характер")
                    Segmented(Persona.entries.map { it.wire to it.title }, s.persona, { v -> c.settings.update { it.copy(persona = v) } })
                    Text(Persona.of(s.persona).hint.replaceFirstChar { it.uppercase() } + ".", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            }
            item {
                Panel {
                    SectionLabel("Оформление")
                    Segmented(listOf<Pair<Boolean?, String>>(null to "Как в системе", false to "Светлая", true to "Тёмная"), s.darkTheme, { v -> c.settings.update { it.copy(darkTheme = v) } })
                }
            }
            item {
                Panel {
                    SectionLabel("О программе")
                    Text("Лоли для Windows · версия ${System.getProperty("jpackage.app-version") ?: "dev"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Данные: ${c.dataDir.absolutePath}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Синхронизация с телефоном появится в следующих версиях. Ключи AI зашифрованы средствами Windows и не покидают компьютер.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                        GhostButton("Папка данных", { openPath(c.dataDir) }, icon = Icons.Rounded.FolderOpen)
                        GhostButton("Журнал ошибок", { openPath(java.io.File(c.dataDir, "logs").takeIf { it.isDirectory } ?: c.dataDir) }, icon = Icons.Rounded.Description)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiSection(c: DesktopContainer, s: ai.loli.desktop.DesktopSettings.Values) {
    val p = palette
    val scope = rememberCoroutineScope()
    val type = AIProviderType.fromId(s.aiProvider).takeIf { !it.builtIn } ?: AIProviderType.OPENAI
    var key by remember(type) { mutableStateOf(c.apiKey(type)) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember(type) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var models by remember(type) { mutableStateOf(type.suggestedModels) }
    var loadingModels by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Облачный AI", style = MaterialTheme.typography.titleLarge)
                Text("Свободный разговор и сложные команды. Без AI Лоли понимает команды сама, без интернета.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            ToggleSwitch(s.aiEnabled) { v -> c.settings.update { it.copy(aiEnabled = v) } }
        }
        Divider(Modifier.padding(vertical = 16.dp))
        Text("Провайдер", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        Dropdown(
            AIProviderType.entries.filter { !it.builtIn }.map { it.id to it.title }, type.id,
            { id -> c.settings.update { it.copy(aiProvider = id, aiModel = "", aiEndpoint = "") }; result = null },
        )
        Field(
            key, { key = it; result = null }, "API-ключ", if (type == AIProviderType.CUSTOM) "Не нужен для своего сервера" else "Вставьте ключ из кабинета провайдера",
            secret = !showKey, modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            trailing = { IconCircle(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показать ключ", { showKey = !showKey }, size = 28.dp) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Модель", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                val current = s.aiModel.ifBlank { type.defaultModel }
                Dropdown((listOf(current) + models).filter { it.isNotBlank() }.distinct().map { it to it }, current, { m -> c.settings.update { it.copy(aiModel = m) } })
            }
            if (type.endpointEditable) Field(
                s.aiEndpoint, { v -> c.settings.update { it.copy(aiEndpoint = v.trim()) } }, "Адрес сервера", type.defaultEndpoint, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
            AccentButton(if (testing) "Проверяю…" else "Сохранить и проверить", {
                if (!c.setApiKey(type, key)) { result = false to "Ключ не сохранился: нет доступа к папке данных. Подробности — в журнале."; return@AccentButton }
                testing = true; result = null
                scope.launch {
                    val cfg = c.aiConfig()
                    val err = withContext(Dispatchers.IO) { c.testAi(cfg) }
                    result = if (err == null) true to "Работает: ${cfg.type.title}, модель ${cfg.model}." else false to err
                    if (err == null && !c.settings.value.aiEnabled) c.settings.update { it.copy(aiEnabled = true) }
                    testing = false
                }
            }, enabled = !testing)
            GhostButton(if (loadingModels) "Загружаю…" else "Загрузить список моделей", {
                c.setApiKey(type, key)
                loadingModels = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { AIProviderFactory.listModels(c.http, AIConfig(type, s.aiEndpoint.ifBlank { null }, null, AIConfig.cleanApiKey(key))) } }
                        .onSuccess { list ->
                            if (list.isNotEmpty()) models = list.map { it.id }.sorted()
                            result = if (list.isEmpty()) false to "Провайдер не вернул ни одной модели." else true to "Моделей: ${list.size}. Выберите в списке."
                        }
                        .onFailure { result = false to (it.message ?: "Не удалось получить список") }
                    loadingModels = false
                }
            }, enabled = !loadingModels && !testing)
        }
        result?.let { (ok, text) ->
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background((if (ok) p.success else p.danger).copy(alpha = 0.1f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) p.success else p.danger, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Text(
            "Ключ шифруется средствами Windows: прочитать его может только ваша учётная запись на этом компьютере. " +
                "Личные записи уходят провайдеру только как контекст для ответа.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun VoiceSection(c: DesktopContainer, s: ai.loli.desktop.DesktopSettings.Values) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<SpeechOutput.Voice>?>(null) }
    LaunchedEffect(Unit) { voices = withContext(Dispatchers.IO) { c.voice.output.voices() } }
    val russian = voices?.filter { it.russian }.orEmpty()
    Panel {
        Text("Голос", style = MaterialTheme.typography.titleLarge)
        Text("Микрофон в чате или Ctrl+Пробел. Речь распознаётся прямо на компьютере, без интернета.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        Divider(Modifier.padding(vertical = 14.dp))
        ToggleRow("Отвечать голосом", "Лоли произносит ответы на голосовые вопросы", s.voiceReplies) { v -> c.settings.update { it.copy(voiceReplies = v) } }
        ToggleRow("Диалоговый режим", "Если Лоли спросила — сразу слушаю ответ, нажимать не нужно", s.dialogMode) { v -> c.settings.update { it.copy(dialogMode = v) } }
        if (c.voice.output.available) {
            Text("Голос Windows", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            when {
                voices == null -> Text("Ищу голоса…", style = MaterialTheme.typography.bodySmall)
                russian.isEmpty() -> Text(
                    "Русского голоса в Windows нет — ответы будут читаться с акцентом. Добавьте его: Параметры → Время и язык → Язык и регион → Русский → Речь.",
                    style = MaterialTheme.typography.bodySmall.copy(color = palette.warning),
                )
                else -> Dropdown(
                    listOf("" to "Автоматически (${russian.first().name})") + voices!!.map { it.name to "${it.name} · ${it.culture}" }, s.voiceName,
                    { v -> c.settings.update { it.copy(voiceName = v) } },
                )
            }
            Text("Скорость речи", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    s.speechRate.toFloat(), { v -> c.settings.update { it.copy(speechRate = v.toInt()) } }, valueRange = -5f..5f, steps = 9,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent, inactiveTrackColor = palette.surfaceHover),
                )
                GhostButton("Прослушать", {
                    scope.launch(Dispatchers.IO) { c.voice.output.speak("Привет! Я ${s.assistantName}. Так звучит мой голос.", c.settings.value.voiceName, c.settings.value.speechRate) }
                }, icon = Icons.Rounded.VolumeUp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        val model = c.voice.input.modelDir()
        Text(
            if (model != null) "Распознавание речи готово." else "Модель распознавания (~45 МБ) скачается при первом нажатии на микрофон.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun StartupSection(c: DesktopContainer) {
    if (!Autostart.supported) return
    var enabled by remember { mutableStateOf(Autostart.isEnabled()) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel {
        SectionLabel("Запуск")
        ToggleRow(
            "Запускать вместе с Windows",
            "Лоли тихо стартует в трее при входе — напоминания приходят, даже если вы её не открывали",
            enabled,
        ) { v ->
            if (Autostart.set(v)) { enabled = v; error = null } else error = "Не получилось изменить автозапуск. Подробности — в журнале."
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = palette.danger), modifier = Modifier.padding(top = 6.dp)) }
        Text(
            "Закрытие окна крестиком оставляет Лоли в трее. Полностью выйти — правой кнопкой по значку → «Выход».",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Открыть папку в Проводнике. */
private fun openPath(dir: java.io.File) {
    runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(dir) }
}

@Composable
private fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = palette
    androidx.compose.material3.Switch(
        checked, onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = p.accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White,
            uncheckedTrackColor = p.surfaceHover, uncheckedThumbColor = p.muted, uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

/** Выпадающий список в стиле полей ввода. */
@Composable
fun <T> Dropdown(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = palette
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surfaceHigh).border(1.dp, p.outline, RoundedCornerShape(12.dp))
                .clickable { open = true }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second ?: selected.toString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Rounded.ExpandMore, null, tint = p.muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(p.surface).heightIn(max = 380.dp)) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title, style = MaterialTheme.typography.bodyMedium.copy(color = if (value == selected) p.accent else p.text)) },
                    onClick = { onSelect(value); open = false },
                )
            }
        }
    }
}
