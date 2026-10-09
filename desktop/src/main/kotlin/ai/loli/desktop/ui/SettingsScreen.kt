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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.loli.core.ai.AIConfig
import ai.loli.core.ai.AIProviderFactory
import ai.loli.core.ai.AIProviderType
import ai.loli.core.assistant.Persona
import ai.loli.core.backup.BackupCodec
import ai.loli.core.model.Routine
import ai.loli.desktop.Autostart
import ai.loli.desktop.DesktopContainer
import ai.loli.desktop.DesktopSettings
import ai.loli.desktop.UpdateChecker
import ai.loli.desktop.voice.LoliVoice
import ai.loli.desktop.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate

/** Разделы настроек — слева списком, как в «Параметрах» Windows 11. */
enum class SettingsSection(val title: String, val subtitle: String, val icon: ImageVector) {
    PROFILE("Профиль", "Имена, город, характер", Icons.Rounded.Person),
    VOICE("Голос и речь", "Русский голос Лоли, микрофон", Icons.Rounded.RecordVoiceOver),
    AI("AI", "Облачный помощник для свободных вопросов", Icons.Rounded.AutoAwesome),
    REMINDERS("Напоминания и запуск", "Окно поверх программ, звук, автозапуск", Icons.Rounded.NotificationsActive),
    ROUTINES("Сценарии", "Несколько команд одной фразой", Icons.Rounded.PlaylistPlay),
    APPEARANCE("Оформление", "Тема и цвет", Icons.Rounded.Palette),
    BACKUP("Резервная копия", "Перенос с телефона и обратно", Icons.Rounded.Backup),
    ABILITIES("Что умеет Лоли", "Команды и примеры", Icons.Rounded.Lightbulb),
    ABOUT("О программе", "Версия, обновления, журнал", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen(c: DesktopContainer, initial: SettingsSection = SettingsSection.PROFILE) {
    val p = palette
    val s by c.settings.state.collectAsState()
    var section by remember(initial) { mutableStateOf(initial) }
    Row(Modifier.fillMaxSize()) {
        // Список разделов
        Column(Modifier.width(272.dp).fillMaxHeight().padding(start = 28.dp, end = 12.dp, top = 28.dp)) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 10.dp, bottom = 18.dp))
            SettingsSection.entries.forEach { item ->
                SectionItem(item, item == section) { section = item }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(p.outline))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 780.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "title-${section.name}") { PageHeader(section.title, section.subtitle) }
                when (section) {
                    SettingsSection.PROFILE -> item { ProfileSection(c, s) }
                    SettingsSection.VOICE -> item { VoiceSection(c, s) }
                    SettingsSection.AI -> item { AiSection(c, s) }
                    SettingsSection.REMINDERS -> item { RemindersSection(c, s) }
                    SettingsSection.ROUTINES -> item { RoutinesSection(c, s) }
                    SettingsSection.APPEARANCE -> item { AppearanceSection(c, s) }
                    SettingsSection.BACKUP -> item { BackupSection(c, s) }
                    SettingsSection.ABILITIES -> item { AbilitiesSection(s) }
                    SettingsSection.ABOUT -> item { AboutSection(c, s) }
                }
            }
        }
    }
}

@Composable
private fun SectionItem(item: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.surfaceHover else if (hovered) p.surfaceHigh else Color.Transparent)
            .hoverable(src).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, null, tint = if (selected) p.accent else p.muted, modifier = Modifier.size(20.dp))
        Text(
            item.title, style = MaterialTheme.typography.labelLarge.copy(color = if (selected) p.text else p.muted),
            modifier = Modifier.padding(start = 12.dp), maxLines = 1,
        )
    }
}

/** Строка настройки с переключателем — короче, чем ToggleRow, для групп. */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(top = 8.dp))

@Composable
private fun Banner(ok: Boolean, text: String) {
    val p = palette
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background((if (ok) p.success else p.danger).copy(alpha = 0.1f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) p.success else p.danger, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}

// ---------------------------------------------------------------- Профиль

@Composable
private fun ProfileSection(c: DesktopContainer, s: DesktopSettings.Values) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            SectionLabel("Ассистент")
            Field(s.assistantName, { v -> c.settings.update { it.copy(assistantName = v.take(30)) } }, "Имя ассистента", "Лоли")
            FlowChips(listOf("Лоли", "Джарвис", "Кира", "Алиса", "Ника").map { it to it }, s.assistantName, { v -> c.settings.update { it.copy(assistantName = v) } }, Modifier.padding(top = 10.dp))
        }
        Panel {
            SectionLabel("Обо мне")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Field(s.userName, { v -> c.settings.update { it.copy(userName = v.take(40)) } }, "Как вас называть", "Например, Аня", modifier = Modifier.weight(1f))
                Field(s.city, { v -> c.settings.update { it.copy(city = v.take(60)) } }, "Город для погоды", "Например, Казань", modifier = Modifier.weight(1f))
            }
            Hint("Можно и голосом: «называй меня Лёша», «я живу в Казани».")
        }
        Panel {
            SectionLabel("Характер")
            Segmented(Persona.entries.map { it.wire to it.title }, s.persona, { v -> c.settings.update { it.copy(persona = v) } })
            Hint(Persona.of(s.persona).hint.replaceFirstChar { it.uppercase() } + ".")
        }
    }
}

// ---------------------------------------------------------------- Голос

@Composable
private fun VoiceSection(c: DesktopContainer, s: DesktopSettings.Values) {
    val p = palette
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    fun test() {
        if (testing) return
        testing = true
        scope.launch(Dispatchers.IO) {
            runCatching { c.voice.say("Привет! Я ${s.assistantName}. Так звучит мой голос.") }
            testing = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            ToggleRow("Отвечать голосом", "${s.assistantName} произносит ответы на голосовые вопросы", s.voiceReplies) { v -> c.settings.update { it.copy(voiceReplies = v) } }
            ToggleRow("Диалоговый режим", "Если ${s.assistantName} спросила — сразу слушаю ответ, нажимать не нужно", s.dialogMode) { v -> c.settings.update { it.copy(dialogMode = v) } }
            Hint("Микрофон — кнопка в чате или Ctrl+Пробел с любой вкладки. Речь распознаётся прямо на компьютере, без интернета.")
        }
        Panel {
            SectionLabel("Чей голос")
            Segmented(listOf("loli" to "Голос Лоли — русский, как на телефоне", "windows" to "Голоса Windows"), s.voiceMode, { v -> c.settings.update { it.copy(voiceMode = v) } })
            if (s.voiceMode == "loli") {
                Hint("Встроенный синтез без интернета: говорит по-русски на любом компьютере. Один голос уже в программе, остальные скачиваются (~65 МБ).")
                Column(Modifier.padding(top = 12.dp)) {
                    LoliVoice.VOICES.forEachIndexed { i, v ->
                        if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                        VoiceRow(c, v, selected = c.voice.loli.effective(s.loliVoice) == v.id && c.voice.loli.isReady(v.id) || (s.loliVoice == v.id && !c.voice.loli.isReady(v.id))) {
                            c.settings.update { it.copy(loliVoice = v.id) }
                            scope.launch(Dispatchers.IO) { runCatching { c.voice.loli.warmUp(v.id) } }
                        }
                    }
                }
                Text("Скорость речи · ${s.loliSpeed}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        s.loliSpeed.toFloat(), { v -> c.settings.update { it.copy(loliSpeed = (v / 5).toInt() * 5) } }, valueRange = 70f..150f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceHover),
                    )
                    GhostButton(if (testing) "Говорю…" else "Прослушать", ::test, icon = Icons.AutoMirrored.Rounded.VolumeUp, modifier = Modifier.padding(start = 12.dp))
                }
            } else {
                WindowsVoices(c, s, testing, ::test)
            }
        }
        Panel {
            SectionLabel("Распознавание речи")
            Text(
                if (c.voice.input.modelDir() != null) "Готово: русская модель на компьютере, без интернета."
                else "Модель (~45 МБ) скачается при первом нажатии на микрофон.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Hint("Если микрофон не слышно: Параметры Windows → Конфиденциальность → Микрофон → разрешить приложениям.")
        }
    }
}

@Composable
private fun VoiceRow(c: DesktopContainer, v: LoliVoice.Voice, selected: Boolean, onSelect: () -> Unit) {
    val p = palette
    val scope = rememberCoroutineScope()
    val state by c.voice.loli.state(v.id).collectAsState()
    val ready = state is LoliVoice.State.Ready
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = ready, onClick = onSelect).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected && ready) p.accent else p.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected && ready) Box(Modifier.size(10.dp).clip(CircleShape).background(p.accent)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(v.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (val st = state) {
                    is LoliVoice.State.Downloading -> "Скачиваю… ${st.percent}%"
                    is LoliVoice.State.Failed -> "Не скачался: ${st.message}"
                    LoliVoice.State.Ready -> v.subtitle
                    LoliVoice.State.Missing -> "${v.subtitle} · не скачан, ${v.bytes / 1_000_000} МБ"
                },
                style = MaterialTheme.typography.bodySmall.copy(color = if (state is LoliVoice.State.Failed) p.danger else p.muted),
            )
        }
        when (state) {
            LoliVoice.State.Missing, is LoliVoice.State.Failed ->
                GhostButton("Скачать", { scope.launch(Dispatchers.IO) { if (c.voice.loli.download(v.id)) onSelect() } }, icon = Icons.Rounded.Download)
            is LoliVoice.State.Downloading -> Unit
            LoliVoice.State.Ready -> if (selected) Tag("Выбран", p.accent)
        }
    }
}

@Composable
private fun WindowsVoices(c: DesktopContainer, s: DesktopSettings.Values, testing: Boolean, test: () -> Unit) {
    val p = palette
    var voices by remember { mutableStateOf<List<SpeechOutput.Voice>?>(null) }
    LaunchedEffect(Unit) { voices = withContext(Dispatchers.IO) { c.voice.output.voices() } }
    val russian = voices?.filter { it.russian }.orEmpty()
    if (!c.voice.output.available) { Hint("Голоса Windows недоступны на этой системе."); return }
    Text("Голос Windows", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    when {
        voices == null -> Text("Ищу голоса…", style = MaterialTheme.typography.bodySmall)
        else -> Dropdown(
            listOf("" to (russian.firstOrNull()?.let { "Автоматически (${it.name})" } ?: "Автоматически")) + voices!!.map { it.name to "${it.name} · ${it.culture}" },
            s.voiceName, { v -> c.settings.update { it.copy(voiceName = v) } },
        )
    }
    if (voices != null && russian.isEmpty()) Text(
        "В Windows нет русского голоса — ответы будут читаться с английским акцентом. Выберите «Голос Лоли» выше или добавьте русский: " +
            "Параметры → Время и язык → Язык и регион → Русский → Речь.",
        style = MaterialTheme.typography.bodySmall.copy(color = p.warning), modifier = Modifier.padding(top = 8.dp),
    )
    Text("Скорость речи", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            s.speechRate.toFloat(), { v -> c.settings.update { it.copy(speechRate = v.toInt()) } }, valueRange = -5f..5f, steps = 9,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceHover),
        )
        GhostButton(if (testing) "Говорю…" else "Прослушать", test, icon = Icons.AutoMirrored.Rounded.VolumeUp, modifier = Modifier.padding(start = 12.dp))
    }
}

// ---------------------------------------------------------------- Напоминания и запуск

@Composable
private fun RemindersSection(c: DesktopContainer, s: DesktopSettings.Values) {
    val scope = rememberCoroutineScope()
    var autostart by remember { mutableStateOf(Autostart.isEnabled()) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            SectionLabel("Как приходят напоминания")
            ToggleRow(
                "Окно поверх всех программ", "Карточка в углу экрана с кнопками «Готово», «+10 мин», «Завтра» — даже если уведомления Windows выключены",
                s.reminderPopup,
            ) { v -> c.settings.update { it.copy(reminderPopup = v) } }
            ToggleRow("Звуковой сигнал", "Короткая мелодия, когда приходит напоминание", s.reminderSound) { v -> c.settings.update { it.copy(reminderSound = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                GhostButton("Показать пример", { c.alerts.show("Напоминание", "Так выглядит напоминание ${s.assistantName}", null, routine = false) }, icon = Icons.Rounded.NotificationsActive)
                GhostButton("Напомнить через минуту", {
                    scope.launch {
                        c.store.reminders.create("Проверка: напоминания работают", c.time.now().plusSeconds(60), null, c.time.zone().id)
                        message = true to "Через минуту придёт проверочное напоминание."
                    }
                })
            }
            message?.let { (ok, text) -> Banner(ok, text) }
        }
        Panel {
            SectionLabel("Запуск")
            if (Autostart.supported) {
                ToggleRow(
                    "Запускать вместе с Windows",
                    "${s.assistantName} тихо стартует в трее при входе — напоминания приходят, даже если вы её не открывали",
                    autostart,
                ) { v -> if (Autostart.set(v)) autostart = v else message = false to "Не получилось изменить автозапуск. Подробности — в журнале." }
            } else {
                Text("Автозапуск доступен в установленной программе (из установщика).", style = MaterialTheme.typography.bodyMedium)
            }
            Hint("Напоминания приходят, пока ${s.assistantName} запущена. Закрытие окна крестиком оставляет её в трее; полностью выйти — правой кнопкой по значку → «Выход».")
        }
    }
}

// ---------------------------------------------------------------- Сценарии

private val ROUTINE_TEMPLATES = listOf(
    "Доброе утро" to listOf("что у меня на сегодня", "какая погода"),
    "Начинаю работу" to listOf("какие задачи на сегодня", "что у меня на сегодня"),
    "Итоги дня" to listOf("итоги дня"),
    "Что купить" to listOf("что купить"),
)

@Composable
private fun RoutinesSection(c: DesktopContainer, s: DesktopSettings.Values) {
    val scope = rememberCoroutineScope()
    val routines by remember { c.store.routines.observe() }.collectAsState(emptyList())
    var trigger by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            SectionLabel("Мои сценарии")
            if (routines.isEmpty()) Text(
                "Сценарий — несколько команд одной фразой. Скажите в чате: «когда я говорю „доброе утро“ — расскажи план на день и погоду». Или добавьте ниже.",
                style = MaterialTheme.typography.bodyMedium.copy(color = palette.muted),
            )
            routines.forEachIndexed { i, r ->
                if (i > 0) Divider(Modifier.padding(vertical = 2.dp))
                ListRow("«${r.trigger}»", r.commands.joinToString(" · "), trailing = {
                    IconCircle(Icons.Rounded.Close, "Удалить сценарий", { scope.launch { c.store.routines.delete(r.id) } }, size = 28.dp)
                })
            }
            val missing = ROUTINE_TEMPLATES.filter { t -> routines.none { Routine.normalize(it.trigger) == Routine.normalize(t.first) } }
            if (missing.isNotEmpty()) {
                Text("Готовые", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                FlowChips(missing.map { it.first to "+ ${it.first}" }, "", { name ->
                    missing.firstOrNull { it.first == name }?.let { (t, cmds) -> scope.launch { c.store.routines.save(t, cmds) } }
                })
            }
        }
        Panel {
            SectionLabel("Новый сценарий")
            Field(trigger, { trigger = it }, "Фраза", "Например: я дома")
            Field(commands, { commands = it }, "Что сделать — команды через запятую или с новой строки", "что у меня на сегодня, какая погода", lines = 3, modifier = Modifier.padding(top = 12.dp))
            val list = commands.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
            Row(Modifier.padding(top = 14.dp)) {
                AccentButton("Сохранить сценарий", {
                    scope.launch { c.store.routines.save(trigger.trim(), list); trigger = ""; commands = "" }
                }, enabled = trigger.isNotBlank() && list.isNotEmpty())
            }
            Hint("Потом просто скажите или напишите фразу — ${s.assistantName} выполнит команды по очереди. Можно и по времени: «каждый день в 8 утра выполняй доброе утро».")
        }
    }
}

// ---------------------------------------------------------------- Оформление

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(c: DesktopContainer, s: DesktopSettings.Values) {
    val p = palette
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            SectionLabel("Тема")
            Segmented(listOf<Pair<Boolean?, String>>(null to "Как в системе", false to "Светлая", true to "Тёмная"), s.darkTheme, { v -> c.settings.update { it.copy(darkTheme = v) } })
        }
        Panel {
            SectionLabel("Цвет ${s.assistantName}")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Accent.entries.forEach { a ->
                    val selected = Accent.of(s.accent) == a
                    val dark = p.dark
                    val color = Color(if (dark) a.dark else a.light)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .then(if (selected) Modifier.border(3.dp, p.text, CircleShape) else Modifier)
                                .padding(if (selected) 6.dp else 3.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(color, Color(a.glow))))
                                .clickable { c.settings.update { it.copy(accent = a.id) } },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Rounded.Check, a.title, tint = if (color.luminanceValue() > 0.6f) Color.Black else Color.White, modifier = Modifier.size(20.dp)) }
                        Text(a.title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun Color.luminanceValue(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ---------------------------------------------------------------- Резервная копия

@Composable
private fun BackupSection(c: DesktopContainer, s: DesktopSettings.Values) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var includeChat by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val validNew = password.length >= BackupCodec.MIN_PASSWORD && password == repeat
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            Text(
                "Копия — один файл, зашифрованный вашим паролем. Тот же формат, что на телефоне: сделайте копию в телефоне (Настройки → Резервная копия) " +
                    "и восстановите её здесь — заметки, задачи, напоминания, траты и память появятся на компьютере. И наоборот.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Field(password, { password = it }, "Пароль копии", "Не меньше ${BackupCodec.MIN_PASSWORD} знаков", secret = true, modifier = Modifier.padding(top = 14.dp))
            Field(repeat, { repeat = it }, "Повторите пароль (для новой копии)", "", secret = true, modifier = Modifier.padding(top = 12.dp))
            if (repeat.isNotEmpty() && repeat != password) Hint("Пароли не совпадают")
            ToggleRow("Включить переписку с ${s.assistantName}", "История чата тоже попадёт в файл", includeChat) { includeChat = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                AccentButton(if (busy) "Подождите…" else "Сохранить в файл", {
                    val file = chooseFile(save = true, "loli-${LocalDate.now()}.${BackupCodec.EXTENSION}") ?: return@AccentButton
                    busy = true; message = null
                    scope.launch {
                        message = runCatching {
                            withContext(Dispatchers.IO) { file.writeBytes(c.backup.export(password.toCharArray(), includeChat)) }
                        }.fold(
                            { true to "Готово: ${file.name}. Храните файл там, где он переживёт компьютер (облако, флешка). Пароль не потеряйте." },
                            { false to "Не удалось сохранить: ${it.message ?: it::class.simpleName}" },
                        )
                        busy = false
                    }
                }, enabled = validNew && !busy, icon = Icons.Rounded.Backup)
                GhostButton("Восстановить из файла", {
                    val file = chooseFile(save = false, null) ?: return@GhostButton
                    busy = true; message = null
                    scope.launch {
                        message = try {
                            val report = withContext(Dispatchers.IO) {
                                if (file.length() > 60L * 1024 * 1024) error("файл слишком большой")
                                c.backup.restore(file.readBytes(), password.toCharArray())
                            }
                            val skipped = if (report.skippedNewerLocal > 0) " Пропущено ${report.skippedNewerLocal}: здесь уже есть более новые версии." else ""
                            true to "Восстановлено записей: ${report.total}.$skipped"
                        } catch (e: BackupCodec.BackupException) {
                            false to (e.message ?: "Не удалось восстановить.")
                        } catch (e: Exception) {
                            false to "Не удалось восстановить: ${e.message ?: e::class.simpleName}"
                        }
                        busy = false
                    }
                }, enabled = password.length >= BackupCodec.MIN_PASSWORD && !busy, icon = Icons.Rounded.FolderOpen)
            }
            message?.let { (ok, text) -> Banner(ok, text) }
            Hint("Восстановление не стирает данные: запись из копии берётся, только если она новее той, что уже есть. API-ключи в копию не входят никогда.")
        }
    }
}

/** Системное окно выбора файла Windows. null — пользователь отменил. */
private fun chooseFile(save: Boolean, suggested: String?): File? {
    val dialog = FileDialog(null as Frame?, if (save) "Сохранить копию" else "Открыть копию", if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (suggested != null) dialog.file = suggested
    if (!save) dialog.setFilenameFilter { _, name -> name.endsWith(".${BackupCodec.EXTENSION}") }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val f = File(dialog.directory, name)
    return if (save && !f.name.endsWith(".${BackupCodec.EXTENSION}")) File(f.path + ".${BackupCodec.EXTENSION}") else f
}

// ---------------------------------------------------------------- Что умеет

private val ABILITIES = listOf(
    "Расходы и доходы" to "«Потратила 850 на продукты», «зарплата пришла 80000», «сколько я потратила на кафе в этом месяце», «на что больше всего трачу»",
    "Задачи и списки" to "«Нужно сходить в зал в понедельник», «отметь задачу купить хлеб выполненной», «какие задачи на сегодня»",
    "Напоминания и таймеры" to "«Напомни через час выпить воды», «каждый понедельник в 9 напоминай про планёрку», «таймер на 5 минут»",
    "Заметки, идеи, память" to "«Запиши заметку…», «у меня идея…», «добавь к идее…», «запомни, что я не ем сладкое», «найди всё про отпуск»",
    "Покупки" to "«Добавь в покупки молоко, хлеб и яйца», «что купить?», «купила молоко», «убери купленное»",
    "Долги, накопления, сроки" to "«Саша должен мне 500», «отложил 2000 на ремонт», «гарантия на телефон до 5 мая», «у Маши день рождения 12 октября»",
    "Здоровье и привычки" to "«Давление 120 на 80», «вес 68», «спала 7 часов», «выпила стакан воды», «какое у меня давление за неделю»",
    "Сценарии" to "«Когда я говорю „доброе утро“ — расскажи план и погоду», потом просто «доброе утро»",
    "Даты словами" to "«2 дня назад потратила 500 на такси», «в прошлую пятницу», «через 3 недели», «через час двадцать»",
    "Компьютер" to "«Открой youtube.com», «найди в интернете рецепт плова», «маршрут до вокзала», «включи музыку» — в браузере; «выгрузи траты в таблицу» — в «Загрузки»",
    "Быстрые ответы" to "Время и дата, время в других городах, сколько дней до даты, калькулятор и проценты, перевод единиц, монетка и кубик",
    "Игры и сказки" to "«Давай в города», «загадай число», «загадай загадку», «расскажи сказку про колобка»",
    "С интернетом" to "Погода, курсы валют, новости, справка из Википедии",
    "С AI" to "Любые вопросы, тексты и письма, планы и анализ трат, перевод, свободная речь — подключите AI в настройках",
)

@Composable
private fun AbilitiesSection(s: DesktopSettings.Values) {
    Panel {
        ABILITIES.forEachIndexed { i, (title, examples) ->
            if (i > 0) Divider(Modifier.padding(vertical = 4.dp))
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(examples, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Hint("Говорите или пишите ${s.assistantName} своими словами — команды понимаются и без AI, прямо на компьютере. Звонки, SMS, фонарик и прочее телефонное работают только на телефоне.")
    }
}

// ---------------------------------------------------------------- О программе

@Composable
private fun AboutSection(c: DesktopContainer, s: DesktopSettings.Values) {
    val scope = rememberCoroutineScope()
    val update by c.updates.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            SectionLabel("Обновления")
            Text("${s.assistantName} для Windows · версия ${c.updates.current}", style = MaterialTheme.typography.bodyMedium)
            Text(
                when (val u = update) {
                    UpdateChecker.State.Idle -> "Проверяю обновления раз в день при запуске."
                    UpdateChecker.State.Checking -> "Проверяю…"
                    UpdateChecker.State.UpToDate -> "Установлена последняя версия."
                    is UpdateChecker.State.Available -> "Вышла версия ${u.version}. Установщик ставится поверх — записи и настройки сохранятся."
                    is UpdateChecker.State.Failed -> u.message
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                (update as? UpdateChecker.State.Available)?.let { u ->
                    AccentButton("Скачать ${u.version}", { openUrl(u.url) }, icon = Icons.Rounded.Download)
                }
                GhostButton("Проверить обновления", { scope.launch { c.updates.check() } }, icon = Icons.Rounded.SystemUpdate, enabled = update !is UpdateChecker.State.Checking)
            }
        }
        Panel {
            SectionLabel("Данные")
            Text("Папка: ${c.dataDir.absolutePath}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                GhostButton("Папка данных", { openPath(c.dataDir) }, icon = Icons.Rounded.FolderOpen)
                GhostButton("Журнал ошибок", { openPath(File(c.dataDir, "logs").takeIf { it.isDirectory } ?: c.dataDir) }, icon = Icons.Rounded.Description)
            }
        }
        Panel {
            SectionLabel("Безопасность")
            listOf(
                "Все данные хранятся только на этом компьютере, в вашей папке пользователя.",
                "Ключи AI зашифрованы средствами Windows (DPAPI): прочитать их может только ваша учётная запись.",
                "Распознавание и синтез речи работают на компьютере — звук никуда не отправляется.",
                "В AI уходит только текст вопроса и нужный контекст, и только если вы включили AI.",
                "Секреты и тексты команд не пишутся в журнал.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

/** Открыть папку в Проводнике. */
private fun openPath(dir: File) {
    runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(dir) }
}

/** Открыть ссылку в браузере. */
internal fun openUrl(url: String) {
    runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
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
