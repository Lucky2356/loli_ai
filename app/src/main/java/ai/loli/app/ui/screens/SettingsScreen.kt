package ai.loli.app.ui.screens

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
    ASSISTANT("settings/assistant", "Ассистент", "Имя, диалог, голос ответов", Icons.Rounded.Face),
    VOICE("settings/voice", "Голос и распознавание", "Микрофон, офлайн-модель, «Лоли» в фоне", Icons.Rounded.Mic),
    AI("settings/ai", "AI-провайдеры", "Несколько сервисов с автоматическим резервом", Icons.Rounded.AutoAwesome),
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
        SettingsPage.ACCOUNT -> AccountPage(c, onBack, onOpenAuth)
        SettingsPage.PERMISSIONS -> PermissionsPage(c, onBack)
        SettingsPage.ABOUT -> AboutPage(c, onBack)
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
            }
        }
        if (s.ttsEnabled) item(key = "rate") {
            SectionLabel("Скорость речи · ${"%.1f".format(s.speechRate)}×")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Slider(value = s.speechRate, onValueChange = { v -> scope.launch { c.settings.setSpeechRate(v) } }, valueRange = 0.5f..2f)
                    TextButton(onClick = { scope.launch { c.tts.speak("Привет! Я ${s.assistantName}. Так звучит мой голос.") } }) { Text("Прослушать") }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) item(key = "dynamic") {
            SectionLabel("Цвета")
            Group {
                SwitchItem("Цвета обоев", "Акцентный цвет подстраивается под обои (Android 12+)", s.dynamicColor) { v -> scope.launch { c.settings.setDynamicColor(v) } }
            }
        }
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
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
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
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
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

/** Пошаговая инструкция и кнопка перехода в настройки телефона. */
@Composable
fun AssistantGuideDialog(name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("$name — ассистент по умолчанию") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Сервисы Google при этом продолжат работать — меняется только приложение, которое открывается долгим нажатием «Домой» или кнопки питания.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                assistantSteps(name).forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Если «$name» нет в списке — обновите приложение до последней версии и перезагрузите телефон. " +
                        "Вызвать $name можно и без этого: плитка в шторке, виджет на рабочем столе или слово «$name» в фоне.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); openAssistantSettings(context) }) { Text("Открыть настройки") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
