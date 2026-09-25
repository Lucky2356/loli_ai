package ai.loli.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.security.KeystoreSecretStore
import ai.loli.app.ui.components.LabeledRow
import ai.loli.app.ui.components.LoliTopBar
import ai.loli.app.voice.VoskModelManager
import ai.loli.app.voice.WakeWordService
import ai.loli.core.ai.AIProviderType
import ai.loli.core.auth.AuthState
import ai.loli.core.sync.SyncStatus
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(c: AppContainer, onOpenAuth: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val sync by c.syncEngine.status.collectAsStateWithLifecycle()
    val model by c.voskModels.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val resumeTick = ai.loli.app.ui.components.rememberResumeTick()

    Scaffold(topBar = { LoliTopBar("Настройки") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            // ---------- Ассистент ----------
            Section("Ассистент") {
                var name by remember(s.assistantName) { mutableStateOf(s.assistantName) }
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Имя и слово активации") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Лоли", "Джарвис", "Кира", "Алиса").forEach { n -> FilterChip(selected = name == n, onClick = { name = n }, label = { Text(n) }) }
                }
                Button(enabled = name.isNotBlank() && name != s.assistantName, onClick = {
                    scope.launch {
                        c.settings.updateProfile(assistantName = name)
                        if (WakeWordService.running) { WakeWordService.stop(context); WakeWordService.start(context) }
                    }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Сохранить имя") }
            }

            // ---------- AI ----------
            Section("Облачный AI (необязательно)") {
                Text(
                    "По умолчанию ${s.assistantName} понимает команды прямо на телефоне — без интернета и без API-ключей. " +
                        "Облачный AI можно включить для свободного разговора и сложных формулировок; " +
                        "если он недоступен, команды всё равно выполнятся локально.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Использовать облачный AI", if (s.useAI) "Включено: нужен интернет и API-ключ" else "Выключено: всё работает локально", s.useAI) { v -> scope.launch { c.settings.setUseAI(v) } }
                var providerMenu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Провайдер", modifier = Modifier.weight(1f))
                    Column {
                        OutlinedButton(onClick = { providerMenu = true }) { Text(s.aiProvider.title) }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            AIProviderType.entries.forEach { p ->
                                DropdownMenuItem(text = { Text(p.title) }, onClick = {
                                    providerMenu = false
                                    scope.launch { c.settings.updateProfile(provider = p, model = "", endpoint = "") }
                                })
                            }
                        }
                    }
                }
                var modelText by remember(s.aiProvider, s.aiModel) { mutableStateOf(s.aiModel) }
                OutlinedTextField(modelText, { modelText = it }, label = { Text("Модель") }, placeholder = { Text(s.aiProvider.defaultModel.ifBlank { "например, llama3.1" }) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (s.aiProvider.suggestedModels.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.aiProvider.suggestedModels.take(3).forEach { m -> FilterChip(selected = s.effectiveModel == m, onClick = { modelText = m }, label = { Text(m, maxLines = 1) }) }
                    }
                }
                var endpointText by remember(s.aiProvider, s.aiEndpoint) { mutableStateOf(s.aiEndpoint) }
                if (s.aiProvider.endpointEditable) {
                    OutlinedTextField(endpointText, { endpointText = it }, label = { Text("API endpoint") }, placeholder = { Text(s.aiProvider.defaultEndpoint) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (modelText != s.aiModel || (s.aiProvider.endpointEditable && endpointText != s.aiEndpoint)) {
                    Button(onClick = { scope.launch { c.settings.updateProfile(model = modelText, endpoint = endpointText) } }, modifier = Modifier.padding(top = 8.dp)) { Text("Сохранить модель") }
                }
                // API-ключ: хранится зашифрованным в Android Keystore, не синхронизируется и не показывается повторно.
                val keyName = KeystoreSecretStore.aiKey(s.aiProvider.id)
                val hasKey = remember(s.aiProvider, refresh) { c.secrets.contains(keyName) }
                var key by remember(s.aiProvider) { mutableStateOf("") }
                OutlinedTextField(
                    key, { key = it.trim() }, label = { Text(if (hasKey) "API-ключ сохранён (введите новый, чтобы заменить)" else "API-ключ") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                var testResult by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = key.isNotBlank(), onClick = { c.secrets.put(keyName, key); key = ""; refresh++; testResult = null }) { Text("Сохранить ключ") }
                    if (hasKey) OutlinedButton(onClick = { c.secrets.put(keyName, null); refresh++ }) { Text("Удалить") }
                }
                OutlinedButton(enabled = !testing, onClick = {
                    testing = true
                    scope.launch {
                        testResult = testAiConnection(c).fold({ it }, { "Ошибка: ${it.message}" })
                        testing = false
                    }
                }, modifier = Modifier.padding(top = 4.dp)) { Text(if (testing) "Проверяю…" else "Проверить подключение") }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                SwitchRow("Семантический поиск (эмбеддинги)", "Поиск по смыслу через AI-провайдера (OpenAI, Gemini)", s.embeddingsEnabled) { v -> scope.launch { c.settings.setEmbeddings(v) } }
            }

            // ---------- Голос ----------
            Section("Голос") {
                val micGranted = remember(refresh, resumeTick) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                }
                // После выдачи разрешения сразу включаем фоновое прослушивание, о котором просил пользователь.
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    refresh++
                    if (granted) {
                        scope.launch { c.settings.setWakeWord(true) }
                        WakeWordService.start(context)
                    }
                }
                SwitchRow("Озвучивать ответы", "Системный синтезатор речи", s.ttsEnabled) { v -> scope.launch { c.settings.setTts(v) } }
                Text("Скорость речи: ${"%.1f".format(s.speechRate)}", modifier = Modifier.padding(top = 4.dp))
                Slider(value = s.speechRate, onValueChange = { v -> scope.launch { c.settings.setSpeechRate(v) } }, valueRange = 0.5f..2f)
                SwitchRow("Диалоговый режим", "После ответа продолжаю слушать без повторного имени", s.dialogModeEnabled) { v -> scope.launch { c.settings.setDialogMode(v) } }
                SwitchRow("Распознавать офлайн (Vosk)", "Звук не покидает устройство, качество ниже", s.preferOfflineStt) { v -> scope.launch { c.settings.setPreferOfflineStt(v) } }
                LabeledRow("Системное распознавание", if (c.systemStt.isAvailable()) "доступно" else "недоступно")

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Офлайн-модель речи (~45 МБ)", style = MaterialTheme.typography.titleSmall)
                when (val m = model) {
                    VoskModelManager.State.Missing -> Button(onClick = { scope.launch { c.voskModels.download() } }) { Text("Скачать модель") }
                    is VoskModelManager.State.Downloading -> Column {
                        Text("Загрузка ${(m.progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    VoskModelManager.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Модель установлена", modifier = Modifier.weight(1f))
                        TextButton(onClick = { WakeWordService.stop(context); c.voskEngine.release(); c.voskModels.delete(); scope.launch { c.settings.setWakeWord(false) } }) { Text("Удалить") }
                    }
                    is VoskModelManager.State.Failed -> Column {
                        Text("Ошибка: ${m.message}", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { scope.launch { c.voskModels.download() } }) { Text("Повторить") }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(
                    "Слушать «${s.assistantName}» в фоне",
                    "Постоянное уведомление, повышенный расход батареи. Распознавание на устройстве.",
                    s.wakeWordEnabled,
                    enabled = model is VoskModelManager.State.Ready,
                ) { v ->
                    if (v && !micGranted) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return@SwitchRow }
                    scope.launch { c.settings.setWakeWord(v) }
                    if (v) WakeWordService.start(context) else WakeWordService.stop(context)
                }
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Сделать ассистентом по умолчанию") }
                Text(
                    "Выберите «${s.assistantName}» в «Цифровой ассистент»: тогда долгое нажатие кнопки «Домой» или питания сразу запустит прослушивание — без фоновой работы микрофона.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------- Разрешения ----------
            Section("Разрешения") {
                key(refresh, resumeTick) {
                    PermissionRow("Микрофон", Manifest.permission.RECORD_AUDIO) { refresh++ }
                    if (Build.VERSION.SDK_INT >= 33) PermissionRow("Уведомления", Manifest.permission.POST_NOTIFICATIONS) { refresh++ }
                    val exact = c.reminderScheduler.canScheduleExact()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("Точные напоминания", modifier = Modifier.weight(1f))
                        if (exact) Text("разрешены") else TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 31) runCatching {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                            }
                        }) { Text("Разрешить") }
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text("Настройки приложения") }
                }
            }

            // ---------- Синхронизация и аккаунт ----------
            Section("Аккаунт и синхронизация") {
                var pending by remember { mutableIntStateOf(0) }
                LaunchedEffect(sync, refresh) { pending = c.store.pendingChanges() }
                when (val a = auth) {
                    is AuthState.SignedIn -> {
                        LabeledRow("Аккаунт", a.email)
                        LabeledRow("Не отправлено изменений", "$pending")
                        val status = when (val st = sync) {
                            SyncStatus.Idle -> "ещё не выполнялась"
                            SyncStatus.Running -> "идёт…"
                            is SyncStatus.Done -> st.report.finishedAt?.let {
                                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(it)
                            }.orEmpty() + if (st.report.ok) " ✓" else " — ${st.report.error}"
                        }
                        LabeledRow("Синхронизация", status)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(enabled = sync !is SyncStatus.Running, onClick = {
                                scope.launch { c.syncEngine.sync(); c.rescheduleReminders(); refresh++ }
                            }) { Text("Синхронизировать") }
                            var confirmOut by remember { mutableStateOf(false) }
                            var outError by remember { mutableStateOf<String?>(null) }
                            OutlinedButton(onClick = { confirmOut = true }) { Text("Выйти") }
                            if (confirmOut) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { confirmOut = false },
                                    title = { Text("Выйти из аккаунта?") },
                                    text = { Text(outError ?: "Данные останутся в облаке, а с этого устройства будут удалены.") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            scope.launch {
                                                if (outError != null) { c.forceSignOutDiscardingLocal(); confirmOut = false }
                                                else if (c.signOut()) confirmOut = false
                                                else outError = "Не удалось отправить $pending несинхронизированных изменений (нет сети). Выйти всё равно и потерять их?"
                                            }
                                        }) { Text(if (outError != null) "Выйти и удалить" else "Выйти") }
                                    },
                                    dismissButton = { TextButton(onClick = { confirmOut = false; outError = null }) { Text("Отмена") } },
                                )
                            }
                        }
                    }
                    else -> {
                        Text("Данные хранятся только на этом устройстве. Войдите, чтобы синхронизировать их между устройствами.")
                        Button(onClick = { scope.launch { c.settings.setLocalOnly(false) }; onOpenAuth() }, modifier = Modifier.padding(top = 8.dp)) { Text("Войти или зарегистрироваться") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ServerConfig(c)
            }

            // ---------- Безопасность ----------
            Section("Безопасность") {
                Text(
                    "• Локальная база зашифрована (SQLCipher, AES-256), ключ защищён Android Keystore.\n" +
                        "• API-ключи и токены хранятся только в зашифрованном виде и не синхронизируются.\n" +
                        "• Облачные данные защищены Row Level Security: доступны только вашему аккаунту.\n" +
                        "• Голос в фоне распознаётся на устройстве; в AI отправляется только текст команды.\n" +
                        "• Секреты и тексты команд не пишутся в журналы.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Версия ${ai.loli.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun ServerConfig(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val cfg = c.supabaseConfig()
    LabeledRow("Сервер", if (cfg.isConfigured) cfg.url.removePrefix("https://").take(40) else "не настроен")
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть настройки сервера" else "Свой сервер Supabase") }
    if (expanded) {
        var url by remember { mutableStateOf(s.supabaseUrlOverride) }
        var key by remember { mutableStateOf("") }
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Supabase URL (https://…supabase.co)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it.trim() }, label = { Text("anon / publishable key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text("Оставьте пустым, чтобы использовать сервер из сборки.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            scope.launch {
                c.settings.setSupabaseUrl(url)
                if (key.isNotBlank()) c.secrets.put(KeystoreSecretStore.SUPABASE_ANON_OVERRIDE, key)
                if (url.isBlank()) c.secrets.put(KeystoreSecretStore.SUPABASE_ANON_OVERRIDE, null)
                c.auth.restore()
            }
            expanded = false
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Применить") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PermissionRow(title: String, permission: String, onResult: () -> Unit) {
    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, modifier = Modifier.weight(1f))
        if (granted) Text("разрешено") else TextButton(onClick = { launcher.launch(permission) }) { Text("Разрешить") }
    }
}
