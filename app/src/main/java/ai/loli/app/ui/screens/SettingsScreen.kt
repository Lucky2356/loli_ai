package ai.loli.app.ui.screens

import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import ai.loli.app.settings.AccentColor
import androidx.compose.material3.Surface
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.BuildConfig
import ai.loli.app.device.BackgroundLauncher
import ai.loli.app.security.KeystoreSecretStore
import ai.loli.app.settings.SttMode
import ai.loli.app.settings.ThemeMode
import ai.loli.app.ui.components.ConfirmDialog
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.Pills
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.ui.components.SwitchItem
import ai.loli.app.ui.components.ValueItem
import ai.loli.app.ui.components.UpdateCard
import ai.loli.app.update.UpdateState
import ai.loli.app.ui.components.rememberResumeTick
import ai.loli.app.voice.VoskModelManager
import ai.loli.app.voice.WakeWordService
import ai.loli.core.ai.AIProviderType
import ai.loli.core.auth.AuthState
import ai.loli.core.sync.SyncStatus
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SettingsPage(val route: String, val title: String, val subtitle: String, val icon: ImageVector) {
    CAPABILITIES("settings/capabilities", "Что умеет Лоли", "Что работает на устройстве, а что — с AI", Icons.Rounded.Lightbulb),
    ASSISTANT("settings/assistant", "Ассистент", "Имя, диалог, голос ответов", Icons.Rounded.Face),
    VOICE("settings/voice", "Голос и распознавание", "Микрофон, офлайн-модель, «Лоли» в фоне", Icons.Rounded.Mic),
    AI("settings/ai", "AI-провайдеры", "Несколько сервисов с автоматическим резервом", Icons.Rounded.AutoAwesome),
    ACCESS("settings/access", "Доступ и безопасность", "Экран блокировки, приложения, кнопки, вход по отпечатку", Icons.Rounded.Shield),
    APPEARANCE("settings/appearance", "Оформление", "Тема и цвета", Icons.Rounded.Palette),
    ACCOUNT("settings/account", "Аккаунт и синхронизация", "Supabase, резервная копия в облаке", Icons.Rounded.Cloud),
    PERMISSIONS("settings/permissions", "Разрешения", "Микрофон, уведомления, будильники", Icons.Rounded.Lock),
    ABOUT("settings/about", "О приложении", "Безопасность и версия", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen(
    c: AppContainer,
    page: SettingsPage?,
    open: (SettingsPage) -> Unit,
    openRoute: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAuth: () -> Unit,
) {
    when (page) {
        null -> SettingsRoot(c, open)
        SettingsPage.ASSISTANT -> AssistantPage(c, onBack)
        SettingsPage.VOICE -> VoicePage(c, onBack)
        SettingsPage.AI -> AiPage(c, onBack, openProvider = { type -> openRoute("settings/ai/${type.id}") })
        SettingsPage.APPEARANCE -> AppearancePage(c, onBack)
        SettingsPage.ACCESS -> AccessPage(c, onBack)
        SettingsPage.ACCOUNT -> AccountPage(c, onBack, onOpenAuth)
        SettingsPage.PERMISSIONS -> PermissionsPage(c, onBack)
        SettingsPage.ABOUT -> AboutPage(c, onBack)
        SettingsPage.CAPABILITIES -> CapabilitiesPage(c, onBack, openAi = { open(SettingsPage.AI) })
    }
}

// ------------------------------------------------------------------ Корень

@Composable
private fun SettingsRoot(c: AppContainer, open: (SettingsPage) -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val isAssistant = remember(resumeTick) { isDefaultAssistant(context) }
    var guide by remember { mutableStateOf(false) }
    if (guide) AssistantGuideDialog(s.assistantName) { guide = false }
    LoliScreen(title = "Настройки") {
        if (isAssistant == false) {
            item(key = "assist") {
                Group(Modifier.padding(bottom = 8.dp)) {
                    RowItem(
                        title = "Сделать ${s.assistantName} ассистентом по умолчанию",
                        subtitle = "Долгое нажатие «Домой» или кнопки питания откроет ${s.assistantName} поверх любого приложения",
                        icon = Icons.Rounded.TouchApp, chevron = true, onClick = { guide = true },
                    )
                }
            }
        }
        item(key = "pages") {
            Group {
                SettingsPage.entries.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(inset = 66.dp)
                    RowItem(title = p.title, subtitle = p.subtitle, icon = p.icon, chevron = true, onClick = { open(p) })
                }
            }
        }
        item(key = "version") { Hint("${s.assistantName} ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 8.dp)) }
    }
}

// ------------------------------------------------------------------ Ассистент

@Composable
private fun AssistantPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LoliScreen(title = "Ассистент", onBack = onBack) {
        item(key = "name") {
            var name by remember(s.assistantName) { mutableStateOf(s.assistantName) }
            val presets = listOf("Лоли", "Джарвис", "Кира", "Алиса", "Ника")
            SectionLabel("Имя и слово для вызова")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LoliField(name, { name = it.take(40) }, "Имя")
            }
            Pills(presets, presets.indexOf(name), { name = presets[it] }, Modifier.padding(vertical = 10.dp))
            if (name.isNotBlank() && name.trim() != s.assistantName) {
                PrimaryButton("Сохранить имя", {
                    scope.launch {
                        c.settings.setAssistantName(name)
                        if (WakeWordService.running) { WakeWordService.stop(context); WakeWordService.start(context) }
                    }
                }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            }
            Hint("Имя — это и слово активации: «${name.ifBlank { "Лоли" }}, запиши расход 300 рублей».")
        }
        item(key = "dialog") {
            SectionLabel("Разговор")
            Group {
                SwitchItem("Диалоговый режим", "После ответа продолжаю слушать без повторного имени, пока вы не скажете «хватит» или не замолчите",
                    s.dialogModeEnabled) { v -> scope.launch { c.settings.setDialogMode(v) } }
                GroupDivider()
                SwitchItem("Отвечать голосом", "Системный синтезатор речи", s.ttsEnabled) { v -> scope.launch { c.settings.setTts(v) } }
                GroupDivider()
                val appContext = LocalContext.current.applicationContext
                SwitchItem("Утренняя сводка", "В 8:30 — уведомление с задачами и напоминаниями на день (только если они есть)", s.morningBrief) { v ->
                    scope.launch { c.settings.setMorningBrief(v); ai.loli.app.reminders.MorningBrief.schedule(appContext, v) }
                }
            }
        }
        item(key = "routines") { RoutinesSection(c) }
        if (s.ttsEnabled) item(key = "loli-voice") { LoliVoiceSection(c) }
        if (s.ttsEnabled) item(key = "voice") {
            val context = LocalContext.current
            val systemMode = s.voiceMode == "system" || (s.voiceMode == "auto" && !c.loliVoiceModels.isReady(s.loliVoice))
            var voices by remember { mutableStateOf<List<ai.loli.app.voice.AndroidTtsProvider.VoiceOption>?>(null) }
            var engines by remember { mutableStateOf<List<ai.loli.app.voice.AndroidTtsProvider.EngineOption>>(emptyList()) }
            val resume = ai.loli.app.ui.components.rememberResumeTick()
            LaunchedEffect(s.ttsEngine, resume) { voices = null; c.tts.recheck(); engines = c.tts.engines(); voices = c.tts.russianVoices() }
            val ru by c.tts.russianStatus.collectAsStateWithLifecycle()
            val engineLabel by c.tts.engineLabel.collectAsStateWithLifecycle()
            val sample = "Привет! Я ${s.assistantName}. Так звучит мой голос."
            if (systemMode && (ru == ai.loli.app.voice.AndroidTtsProvider.RuStatus.NO_RUSSIAN || ru == ai.loli.app.voice.AndroidTtsProvider.RuStatus.MISSING_DATA)) {
                androidx.compose.material3.Surface(
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
            Hint(
                if (!systemMode) "Сейчас говорит: Голос Лоли · ${ai.loli.app.voice.LoliVoiceModels.VOICES.firstOrNull { it.id == s.loliVoice }?.title.orEmpty()}"
                else if (engineLabel.isNotBlank()) "Сейчас говорит синтезатор телефона: $engineLabel · " + if (ru == ai.loli.app.voice.AndroidTtsProvider.RuStatus.OK) "русский есть" else "русского нет"
                else "",
            )
            // Готовые стили: работают с любым движком, даже если в нём всего один голос.
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
            if (systemMode && engines.size > 1) {
                SectionLabel("Синтезатор речи")
                Group {
                    val current = s.ttsEngine.ifBlank { c.tts.defaultEngine() }
                    engines.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider(inset = 52.dp)
                        RadioRow(e.label, if (e.name == c.tts.defaultEngine()) "Системный по умолчанию" else e.name, current == e.name) {
                            scope.launch { c.settings.setTtsEngine(if (e.name == c.tts.defaultEngine()) "" else e.name) }
                        }
                    }
                }
            }
            if (systemMode) SectionLabel("Голос синтезатора телефона")
            if (systemMode) Group {
                RadioRow("Как в системе", "Голос по умолчанию синтезатора речи", s.voiceName.isBlank()) {
                    scope.launch { c.settings.setVoiceName(""); c.tts.speak(sample) }
                }
                val list = voices
                if (list == null) {
                    GroupDivider(inset = 52.dp)
                    Hint("Загружаю голоса…")
                } else {
                    list.forEach { v ->
                        GroupDivider(inset = 52.dp)
                        RadioRow(v.title, v.subtitle, s.voiceName == v.name) {
                            scope.launch { c.settings.setVoiceName(v.name); c.tts.preview(sample, v.name) }
                        }
                    }
                }
            }
            if (systemMode) Hint(
                if (voices?.isEmpty() == true) "Русских голосов не найдено. Установите их кнопкой ниже или выберите другой синтезатор (например, RHVoice)."
                else "Нажмите на голос — ${s.assistantName} сразу им заговорит. Больше голосов: установите другой синтезатор речи (например, RHVoice из Google Play) и выберите его ниже.",
            )
            if (systemMode) Row(Modifier.padding(horizontal = 8.dp)) {
                // «Скачать голоса» работает только у синтезатора Google; у Vivo/Huawei и др. кнопка ничего не делала.
                if (c.tts.activeEngine() == ai.loli.app.voice.AndroidTtsProvider.GOOGLE_TTS) TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                .setPackage(ai.loli.app.voice.AndroidTtsProvider.GOOGLE_TTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Скачать голоса") }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }) { Text("Синтезатор речи") }
            }
        }
        if (s.ttsEnabled) item(key = "rate") {
            SectionLabel("Скорость · ${"%.1f".format(s.speechRate)}×   Высота · ${"%.1f".format(s.speechPitch)}")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Скорость", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = s.speechRate, onValueChange = { v -> scope.launch { c.settings.setSpeechRate(v) } }, valueRange = 0.5f..2f)
                    Text("Высота голоса", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = s.speechPitch, onValueChange = { v -> scope.launch { c.settings.setSpeechPitch(v) } }, valueRange = 0.5f..2f)
                    TextButton(onClick = { scope.launch { c.speech.speak("Привет! Я ${s.assistantName}. Так звучит мой голос.") } }) { Text("Прослушать") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Голос

@Composable
private fun VoicePage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val model by c.voskModels.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val micGranted = remember(refresh, resumeTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val isAssistant = remember(resumeTick) { isDefaultAssistant(context) }
    var guide by remember { mutableStateOf(false) }
    if (guide) AssistantGuideDialog(s.assistantName) { guide = false }
    val services = remember(resumeTick) { c.systemStt.services().map { c.systemStt.serviceLabel(it) }.distinct() }
    // После выдачи разрешения сразу включаем фоновое прослушивание, о котором просил пользователь.
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) {
            scope.launch { c.settings.setWakeWord(true) }
            WakeWordService.start(context)
        }
    }
    // Встроенная модель распаковывается при первом открытии страницы — дальше всё работает офлайн.
    LaunchedEffect(Unit) { if (c.voskModels.isBundled) c.voskModels.ensureReady() }

    LoliScreen(title = "Голос", subtitle = "Как ${s.assistantName} слышит вас", onBack = onBack) {
        item(key = "mode") {
            SectionLabel("Распознавание речи")
            Group {
                SttMode.entries.forEachIndexed { i, m ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(m.title, m.hint, s.sttMode == m) { scope.launch { c.settings.setSttMode(m) } }
                }
            }
            Hint(
                if (services.isEmpty()) "Системных сервисов распознавания не найдено — используется офлайн-модель. Для лучшего качества установите приложение Google."
                else "Найдено на телефоне: ${services.joinToString()}. Если один не работает, ${s.assistantName} сама переключится на другой или на офлайн-модель.",
            )
        }
        item(key = "pause") {
            SectionLabel("Пауза до конца фразы")
            Group {
                ai.loli.app.settings.SpeechPause.entries.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(p.title, p.hint, s.speechPause == p) { scope.launch { c.settings.setSpeechPause(p) } }
                }
            }
            Hint("Если ${s.assistantName} перебивает или отвечает, пока вы ещё говорите, — выберите «Длинная». Оборванную фразу («купи хлеб и…») ${s.assistantName} дослушает сама.")
        }
        item(key = "model") {
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
        }
        item(key = "wake") {
            SectionLabel("Вызов голосом")
            Group {
                SwitchItem(
                    "Слушать «${s.assistantName}» в фоне",
                    "Постоянное уведомление, повышенный расход батареи. Распознавание — на устройстве, звук никуда не отправляется.",
                    s.wakeWordEnabled, enabled = c.voskModels.isObtainable(),
                ) { v ->
                    if (v && !micGranted) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return@SwitchItem }
                    scope.launch { c.settings.setWakeWord(v) }
                    if (v) WakeWordService.start(context) else WakeWordService.stop(context)
                }
                GroupDivider()
                RowItem(
                    title = if (isAssistant == true) "${s.assistantName} — ассистент по умолчанию" else "Сделать ассистентом по умолчанию",
                    subtitle = if (isAssistant == true) "Долгое нажатие «Домой» или кнопки питания открывает ${s.assistantName}"
                    else "Откроются настройки: выберите «${s.assistantName}» в пункте «Цифровой ассистент»",
                    icon = Icons.Rounded.TouchApp, chevron = true,
                    onClick = { guide = true },
                )
            }
            Hint("Не получается выбрать? Нажмите на пункт выше — покажу путь именно для вашего телефона.")
        }
    }
}

// ------------------------------------------------------------------ Оформление

@Composable
private fun AppearancePage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LoliScreen(title = "Оформление", onBack = onBack) {
        item(key = "theme") {
            SectionLabel("Тема")
            Group {
                ThemeMode.entries.forEachIndexed { i, m ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(m.title, null, s.themeMode == m) { scope.launch { c.settings.setTheme(m) } }
                }
            }
        }
        item(key = "accent") {
            SectionLabel("Цвет ${s.assistantName}")
            Group {
                Column(Modifier.padding(16.dp)) {
                    // 10 цветов: 2 ряда по 5, выбранный — с кольцом и галочкой.
                    AccentColor.entries.chunked(5).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { a -> AccentSwatch(a, selected = !s.dynamicColor && s.accent == a) { scope.launch { c.settings.setAccent(a) } } }
                        }
                    }
                    Text(
                        if (s.dynamicColor) "Сейчас цвет берётся из обоев" else s.accent.title,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    GroupDivider()
                    SwitchItem("Цвета обоев", "Вместо своего цвета — под обои (Android 12+)", s.dynamicColor) { v -> scope.launch { c.settings.setDynamicColor(v) } }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(a: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val color = androidx.compose.ui.graphics.Color(a.dark)
    val glow = androidx.compose.ui.graphics.Color(a.glow)
    val circle = androidx.compose.foundation.shape.CircleShape
    Box(
        Modifier.size(48.dp).clip(circle)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, circle) else Modifier)
            .padding(if (selected) 6.dp else 3.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(color, glow)))
            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClickLabel = a.title, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = a.title, tint = if (color.luminance() > 0.5f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
    }
}

// ------------------------------------------------------------------ Аккаунт

@Composable
private fun AccountPage(c: AppContainer, onBack: () -> Unit, onOpenAuth: () -> Unit) {
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val sync by c.syncEngine.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(sync, refresh) { pending = c.store.pendingChanges() }
    var confirmOut by remember { mutableStateOf(false) }
    var outError by remember { mutableStateOf<String?>(null) }
    LoliScreen(title = "Аккаунт", subtitle = "Синхронизация между устройствами", onBack = onBack) {
        item(key = "account") {
            when (val a = auth) {
                is AuthState.SignedIn -> {
                    Group(Modifier.padding(top = 4.dp)) {
                        ValueItem("Аккаунт", a.email)
                        GroupDivider()
                        ValueItem("Не отправлено", "$pending")
                        GroupDivider()
                        val status = when (val st = sync) {
                            SyncStatus.Idle -> "ещё не выполнялась"
                            SyncStatus.Running -> "идёт…"
                            is SyncStatus.Done -> st.report.finishedAt?.let {
                                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(it)
                            }.orEmpty() + if (st.report.ok) " ✓" else " — ${st.report.error}"
                        }
                        ValueItem("Синхронизация", status)
                    }
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Синхронизировать", { scope.launch { c.syncEngine.sync(); c.rescheduleReminders(); refresh++ } }, enabled = sync !is SyncStatus.Running)
                        SecondaryButton("Выйти", { confirmOut = true })
                    }
                }
                else -> {
                    Hint("Сейчас данные хранятся только на этом устройстве. Войдите, чтобы они синхронизировались между телефонами и сохранялись в облаке.")
                    PrimaryButton("Войти или зарегистрироваться", { scope.launch { c.settings.setLocalOnly(false) }; onOpenAuth() },
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                }
            }
        }
        item(key = "server") {
            SectionLabel("Сервер")
            ServerConfig(c)
        }
    }
    if (confirmOut) {
        ConfirmDialog(
            title = "Выйти из аккаунта?",
            text = outError ?: "Данные останутся в облаке, а с этого устройства будут удалены.",
            confirm = if (outError != null) "Выйти и удалить" else "Выйти",
            onConfirm = {
                scope.launch {
                    if (outError != null) { c.forceSignOutDiscardingLocal(); outError = null }
                    else if (!c.signOut()) {
                        outError = "Не удалось отправить $pending несинхронизированных изменений (нет сети). Выйти всё равно и потерять их?"
                        confirmOut = true
                    }
                }
            },
            onDismiss = { confirmOut = false },
        )
    }
}

@Composable
fun ServerConfig(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val cfg = c.supabaseConfig()
    Group {
        ValueItem("Supabase", if (cfg.isConfigured) cfg.url.removePrefix("https://").take(32) else "не настроен", onClick = { expanded = !expanded })
        AnimatedVisibility(expanded) {
            var url by remember { mutableStateOf(s.supabaseUrlOverride) }
            var key by remember { mutableStateOf("") }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LoliField(url, { url = it.trim() }, "Адрес проекта", placeholder = "https://….supabase.co", keyboardType = KeyboardType.Uri,
                    supporting = "Оставьте пустым, чтобы использовать сервер из сборки")
                LoliField(key, { key = it.trim() }, "anon / publishable key", keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation())
                PrimaryButton("Применить", {
                    scope.launch {
                        c.settings.setSupabaseUrl(url)
                        if (key.isNotBlank()) c.secrets.put(KeystoreSecretStore.SUPABASE_ANON_OVERRIDE, key)
                        if (url.isBlank()) c.secrets.put(KeystoreSecretStore.SUPABASE_ANON_OVERRIDE, null)
                        c.auth.restore()
                    }
                    expanded = false
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ------------------------------------------------------------------ Разрешения

@Composable
private fun PermissionsPage(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    LoliScreen(title = "Разрешения", onBack = onBack) {
        item(key = "perms") {
            val tick = resumeTick + refresh
            Group(Modifier.padding(top = 4.dp)) {
                PermissionRow("Микрофон", "Голосовые команды", Icons.Rounded.Mic, Manifest.permission.RECORD_AUDIO, tick) { refresh++ }
                GroupDivider(inset = 66.dp)
                val overlay = remember(tick) { c.launcher.canLaunchFromBackground() }
                RowItem(
                    title = "Поверх других приложений",
                    subtitle = if (overlay) "Разрешено: таймеры, будильники и приложения открываются, даже когда Лоли свёрнута"
                    else "Нужно, чтобы по голосу открывать часы, приложения и звонки, когда Лоли свёрнута",
                    icon = Icons.Rounded.Layers,
                    trailing = { if (!overlay) TextButton(onClick = { runCatching { context.startActivity(BackgroundLauncher.overlaySettings(context)) } }) { Text("Разрешить") } },
                )
                GroupDivider(inset = 66.dp)
                PermissionRow("Контакты", "Чтобы звонить и писать по имени: «позвони маме»", Icons.Rounded.Contacts, Manifest.permission.READ_CONTACTS, tick) { refresh++ }
                if (Build.VERSION.SDK_INT >= 33) {
                    GroupDivider(inset = 66.dp)
                    PermissionRow("Уведомления", "Напоминания и фоновое прослушивание", Icons.Rounded.Notifications, Manifest.permission.POST_NOTIFICATIONS, tick) { refresh++ }
                }
                GroupDivider(inset = 66.dp)
                val exact = remember(tick) { c.reminderScheduler.canScheduleExact() }
                RowItem(
                    title = "Точные напоминания", subtitle = if (exact) "Разрешено" else "Без него Android может задержать напоминание",
                    icon = Icons.Rounded.Alarm,
                    trailing = {
                        if (!exact) TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 31) runCatching {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                            }
                        }) { Text("Разрешить") }
                    },
                )
                GroupDivider(inset = 66.dp)
                RowItem(
                    title = "Работа в фоне", subtitle = "Отключите экономию батареи для ${context.getString(ai.loli.app.R.string.app_name)}, чтобы фоновое прослушивание не останавливалось",
                    icon = Icons.Rounded.BatteryChargingFull, chevron = true,
                    onClick = { requestBatteryExemption(context) },
                )
            }
            SecondaryButton("Все настройки приложения", {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.padding(16.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, icon: ImageVector, permission: String, tick: Int, onResult: () -> Unit) {
    val context = LocalContext.current
    val granted = remember(tick) { ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    RowItem(
        title = title, subtitle = if (granted) "Разрешено" else subtitle, icon = icon,
        trailing = { if (!granted) TextButton(onClick = { launcher.launch(permission) }) { Text("Разрешить") } },
    )
}

// ------------------------------------------------------------------ О приложении

@Composable
private fun AboutPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val update by c.updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LoliScreen(title = "О приложении", onBack = onBack) {
        item(key = "update") {
            SectionLabel("Обновления")
            UpdateCard(c)
            Group {
                ValueItem("Версия", c.updates.currentVersion)
                GroupDivider()
                SwitchItem("Обновлять автоматически", "Проверять раз в 12 часов, скачивать и устанавливать новые версии", s.autoUpdate) { v ->
                    scope.launch { c.settings.setAutoUpdate(v) }
                }
                GroupDivider()
                RowItem(
                    title = "Проверить обновления",
                    subtitle = when (val u = update) {
                        UpdateState.Checking -> "Проверяю…"
                        UpdateState.UpToDate -> "Установлена последняя версия"
                        is UpdateState.Error -> u.message
                        else -> c.updates.lastCheckedAt.takeIf { it > 0 }?.let {
                            "Проверено " + DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale("ru")).withZone(ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(it))
                        }
                    },
                    icon = Icons.Rounded.SystemUpdate,
                    onClick = { scope.launch { c.updates.check() } },
                )
            }
        }
        item(key = "security") {
            SectionLabel("Безопасность")
            Group {
                listOf(
                    "Локальная база зашифрована (SQLCipher, AES-256), ключ защищён Android Keystore.",
                    "API-ключи и токены хранятся только в зашифрованном виде и не синхронизируются.",
                    "Облачные данные защищены Row Level Security: доступны только вашему аккаунту.",
                    "Голос в фоне и офлайн-распознавание работают на устройстве; в AI отправляется только текст команды.",
                    "Секреты и тексты команд не пишутся в журналы.",
                ).forEachIndexed { i, t ->
                    if (i > 0) GroupDivider()
                    RowItem(title = t)
                }
            }
        }

    }
}

// ------------------------------------------------------------------ Общие

@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    RowItem(
        title = title, subtitle = subtitle, onClick = onClick,
        leading = {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ProgressRow(title: String, progress: Float) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("$title · ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

/** true/false — известно, null — не удалось определить (старые версии Android). */
fun isDefaultAssistant(context: Context): Boolean? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } else {
        Settings.Secure.getString(context.contentResolver, "assistant")?.startsWith(context.packageName + "/")
    }
}.getOrNull()

/**
 * Открывает системный выбор цифрового ассистента. У производителей разные экраны, поэтому пробуем по порядку:
 * прямой экран роли «Ассистент» → «Приложения по умолчанию» → «Помощник и голосовой ввод» → настройки.
 * (Экран «Голосовой ввод» на многих телефонах ведёт в настройки Google — поэтому он не первый.)
 */
fun openAssistantSettings(context: Context) {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Intent("android.intent.action.MANAGE_DEFAULT_APP").putExtra("android.intent.extra.ROLE_NAME", "android.app.role.ASSISTANT"))
        }
        add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        add(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        add(Intent(Settings.ACTION_SETTINGS))
    }
    for (intent in intents) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** Путь к выбору ассистента в настройках конкретного производителя. */
fun assistantSteps(name: String): List<String> {
    val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return when {
        "samsung" in brand -> listOf(
            "Настройки → Приложения → Выбор приложений по умолчанию",
            "«Цифровой помощник» (или «Приложение цифрового помощника»)",
            "Выберите «$name» вместо Google/Gemini",
            "Дополнительно: Настройки → Дополнительные функции → Боковая клавиша → Двойное нажатие → Открыть приложение → $name",
        )
        "xiaomi" in brand || "redmi" in brand || "poco" in brand -> listOf(
            "Настройки → Приложения → Приложения по умолчанию (или «Все приложения» → ⋮ → «Приложения по умолчанию»)",
            "«Помощник и голосовой ввод» → «Помощник»",
            "Выберите «$name»",
        )
        "huawei" in brand || "honor" in brand -> listOf(
            "Настройки → Приложения → Приложения по умолчанию",
            "«Помощник» (или «Голосовой помощник»)",
            "Выберите «$name»",
        )
        "oppo" in brand || "realme" in brand || "oneplus" in brand || "vivo" in brand -> listOf(
            "Настройки → Приложения → Приложения по умолчанию",
            "«Цифровой помощник» / «Помощник и голосовой ввод»",
            "Выберите «$name»",
        )
        else -> listOf(
            "Настройки → Приложения → Приложения по умолчанию",
            "«Цифровой ассистент» (или «Помощник и голосовой ввод»)",
            "Выберите «$name» вместо Google/Gemini",
        )
    }
}

/**
 * Пошаговая инструкция «Сделать ассистентом по умолчанию».
 * Если прошивка не показывает Лоли в списке (realme, OPPO, OnePlus, Xiaomi…), Лоли назначает себя сама —
 * через Shizuku прямо на телефоне или одной командой с компьютера.
 */
@Composable
fun AssistantGuideDialog(name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val access = remember { ai.loli.app.device.SystemAccess(context) }
    val scope = rememberCoroutineScope()
    val tick = ai.loli.app.ui.components.rememberResumeTick()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var poll by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Shizuku могут запустить, пока открыт диалог: тихо перепроверяем состояние.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); poll++ } }
    val isAssistant = remember(tick, poll, result) { access.isAssistant() }
    val installed = remember(tick, poll) { access.shizukuInstalled() }
    val running = remember(tick, poll) { access.shizukuRunning() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("$name — ассистент по умолчанию") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = if (isAssistant) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (isAssistant) "✓ Сейчас ассистент — $name" else "Сейчас ассистент — другое приложение",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                Text(
                    "Сервисы Google продолжат работать — меняется только то, что открывается долгим нажатием «Домой» или кнопки питания.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Способ 1 — в настройках телефона", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                assistantSteps(name).forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { openAssistantSettings(context) }) { Text("Открыть настройки телефона") }

                Text("Способ 2 — если «$name» нет в списке", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Некоторые прошивки (realme, OPPO, OnePlus, Xiaomi и др.) показывают в этом списке только Google. " +
                        "$name может назначить себя сама через бесплатное приложение Shizuku — компьютер не нужен:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val steps = listOf(
                    "Установите Shizuku" to installed,
                    "Запустите его: в Shizuku «Запуск через беспроводную отладку» → «Сопряжение», код появится в уведомлении" to running,
                    "Нажмите «Настроить автоматически» и разрешите доступ" to isAssistant,
                )
                steps.forEachIndexed { i, (text, done) ->
                    Text((if (done) "✓ " else "${i + 1}. ") + text, style = MaterialTheme.typography.bodyMedium,
                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                when {
                    !installed -> PrimaryButton("Установить Shizuku", { runCatching { context.startActivity(access.shizukuDownload().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                    !running -> PrimaryButton("Открыть Shizuku", { access.openShizuku()?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } })
                    else -> PrimaryButton(if (busy) "Настраиваю…" else "Настроить автоматически", {
                        if (!busy) scope.launch {
                            busy = true
                            result = access.setupWithShizuku().message
                            busy = false
                        }
                    }, enabled = !busy)
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                Text(
                    "Заодно $name включит свои кнопки громкости и работу поверх приложений. Shizuku после этого можно закрыть; " +
                        "после перезагрузки телефона настройки сохраняются.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Способ 3 — с компьютера", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Включите «Отладку по USB» (Настройки → О телефоне → 7 раз нажать «Номер сборки» → Для разработчиков), подключите телефон и выполните:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        access.adbCommands, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", access.adbCommands))
                    android.widget.Toast.makeText(context, "Команды скопированы", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Скопировать команды") }
                Text(
                    "Пока ассистент не выбран, $name всё равно вызывается: слово «$name», кнопки громкости, плитка в шторке, виджет.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

/** Лоли не усыпляется экономией батареи: одно системное окно «Разрешить», без поиска в списке приложений. */
fun isBatteryExempt(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@android.annotation.SuppressLint("BatteryLife")
fun requestBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private class VoiceStyle(val title: String, val subtitle: String, val pitch: Float, val rate: Float)

private val VOICE_STYLES = listOf(
    VoiceStyle("Обычный", "Как задумано синтезатором", 1.0f, 1.0f),
    VoiceStyle("Мягкий", "Чуть выше и спокойнее", 1.15f, 0.92f),
    VoiceStyle("Бодрый", "Выше и быстрее", 1.2f, 1.15f),
    VoiceStyle("Низкий", "Ниже и размереннее", 0.8f, 0.95f),
    VoiceStyle("Деловой", "Ровно и быстро", 0.95f, 1.2f),
)

/** Сценарии: фраза → несколько команд. Готовые шаблоны добавляются одним нажатием. */
@Composable
private fun RoutinesSection(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val routines by remember { c.store.routines.observe() }.collectAsStateWithLifecycle(emptyList())
    SectionLabel("Сценарии")
    Group {
        if (routines.isEmpty()) {
            Hint("Скажите: «когда я говорю „спокойной ночи“ — поставь будильник на 7 и включи не беспокоить». Или добавьте шаблон ниже.")
        }
        routines.forEachIndexed { i, r ->
            if (i > 0) GroupDivider()
            RowItem(
                title = "«${r.trigger}»", subtitle = r.commands.joinToString(" · "), maxSubtitleLines = 3,
                trailing = {
                    IconButton(onClick = { scope.launch { c.store.routines.delete(r.id) } }) {
                        Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Удалить сценарий", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
    val context = LocalContext.current
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = {
            scope.launch {
                val r = ai.loli.app.device.BirthdayImport.run(context, c)
                val msg = when {
                    r.permissionDenied -> "Нужен доступ к контактам, чтобы найти дни рождения"
                    r.imported == 0 -> "Новых дней рождения в контактах не нашлось"
                    else -> "Добавила ${ai.loli.core.assistant.RuFormat.count(r.imported, "день рождения", "дня рождения", "дней рождения")} — напомню накануне и в сам день"
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }) { Text("Дни рождения из контактов") }
    }
    val missing = ROUTINE_TEMPLATES.filter { t -> routines.none { ai.loli.core.model.Routine.normalize(it.trigger) == ai.loli.core.model.Routine.normalize(t.first) } }
    if (missing.isNotEmpty()) {
        Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState())) {
            missing.forEach { (trigger, commands) ->
                TextButton(onClick = { scope.launch { c.store.routines.save(trigger, commands) } }) { Text("+ $trigger") }
            }
        }
    }
}

private val ROUTINE_TEMPLATES = listOf(
    "Доброе утро" to listOf("что у меня на сегодня", "какая погода"),
    "Спокойной ночи" to listOf("поставь будильник на 7:00", "включи не беспокоить"),
    "Я за рулём" to listOf("включи не беспокоить", "включи музыку"),
    "Я дома" to listOf("выключи не беспокоить", "что у меня на сегодня"),
)
