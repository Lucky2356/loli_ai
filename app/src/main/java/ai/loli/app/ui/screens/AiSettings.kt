package ai.loli.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.security.KeystoreSecretStore
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SearchBox
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.ui.components.SwitchItem
import ai.loli.app.ui.components.rememberResumeTick
import ai.loli.core.ai.AIProviderFactory
import ai.loli.core.ai.AIProviderType
import ai.loli.core.ai.ModelInfo
import kotlinx.coroutines.launch

/** Короткое пояснение к провайдеру — чтобы было понятно, что выбрать. */
private fun AIProviderType.about(): String = when (this) {
    AIProviderType.OPENAI -> "GPT-5, GPT-4.1, o-серия · platform.openai.com"
    AIProviderType.GEMINI -> "Gemini 2.5 Flash/Pro · есть бесплатный лимит · aistudio.google.com"
    AIProviderType.ANTHROPIC -> "Claude Opus, Sonnet, Haiku · console.anthropic.com"
    AIProviderType.OPENROUTER -> "Сотни моделей разных компаний по одному ключу · openrouter.ai"
    AIProviderType.DEEPSEEK -> "DeepSeek Chat и Reasoner · недорого · platform.deepseek.com"
    AIProviderType.MISTRAL -> "Mistral Small/Medium/Large · console.mistral.ai"
    AIProviderType.GROQ -> "Llama и другие открытые модели, очень быстро · console.groq.com"
    AIProviderType.XAI -> "Grok · console.x.ai"
    AIProviderType.CUSTOM -> "Ollama, LM Studio, vLLM на вашем компьютере — без облака"
    AIProviderType.LOLI_CLOUD -> "Сервер Лоли: работает после входа в аккаунт, без своего ключа"
}

@Composable
fun AiPage(c: AppContainer, onBack: () -> Unit, openProvider: (AIProviderType) -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()
    LoliScreen(title = "AI", subtitle = "Необязательно: команды и так понимаются на устройстве", onBack = onBack) {
        if (c.supabaseConfig().isConfigured) item(key = "cloud") { LoliCloudSection(c) }
        item(key = "offline") { OfflineModelSection(c) }
        item(key = "use") {
            SectionLabel("Облачный AI")
            Group(Modifier.padding(top = 4.dp)) {
                SwitchItem(
                    "Облачный AI", if (s.useAI) "Свободный разговор и сложные фразы. Нужен интернет и API-ключ" else "Выключен: всё работает локально, без интернета",
                    s.useAI, icon = Icons.Rounded.AutoAwesome,
                ) { v -> scope.launch { c.settings.setUseAI(v) } }
            }
            Hint(
                "Подключите один или несколько провайдеров. Запрос уходит первому включённому; если он не отвечает (нет ключа, лимит, сбой) — " +
                    "следующему, а без интернета команда выполнится на устройстве.",
            )
        }
        val active = s.providers.filter { it.enabled }
        if (active.isNotEmpty()) item(key = "active") {
            SectionLabel("Подключены · по приоритету")
            Group(Modifier.animateContentSize()) {
                active.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(inset = 60.dp)
                    val hasKey = remember(p.type, resumeTick) { c.secrets.contains(KeystoreSecretStore.aiKey(p.type.id)) }
                    RowItem(
                        title = p.type.title,
                        subtitle = p.effectiveModel.ifBlank { "модель не выбрана" } + if (!hasKey && p.type != AIProviderType.CUSTOM) " · нужен ключ" else "",
                        onClick = { openProvider(p.type) }, chevron = true,
                        leading = {
                            Column {
                                IconButton(onClick = { scope.launch { moveAmongEnabled(c, active.map { it.type }, i, -1) } }, enabled = i > 0, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Выше")
                                }
                                IconButton(onClick = { scope.launch { moveAmongEnabled(c, active.map { it.type }, i, 1) } }, enabled = i < active.lastIndex, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Ниже")
                                }
                            }
                        },
                        trailing = { StatusDot(hasKey || p.type == AIProviderType.CUSTOM) },
                    )
                }
            }
        }
        item(key = "all") {
            SectionLabel(if (active.isEmpty()) "Выберите провайдера" else "Другие провайдеры")
            Group {
                val others = s.providers.filter { !it.enabled }
                others.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    RowItem(title = p.type.title, subtitle = p.type.about(), onClick = { openProvider(p.type) }, chevron = true)
                }
            }
        }
        item(key = "emb") {
            SectionLabel("Поиск")
            Group {
                SwitchItem("Поиск по смыслу через AI", "Эмбеддинги OpenAI или Gemini (если подключены). Без них работает локальный поиск с синонимами.",
                    s.embeddingsEnabled) { v -> scope.launch { c.settings.setEmbeddings(v) } }
            }
        }
    }
}

/** Перестановка внутри включённых: меняем местами соседей в общем порядке. */
private suspend fun moveAmongEnabled(c: AppContainer, enabled: List<AIProviderType>, index: Int, delta: Int) {
    val other = enabled.getOrNull(index + delta) ?: return
    val order = c.settings.current().aiOrder
    val from = order.indexOf(enabled[index])
    val to = order.indexOf(other)
    c.settings.moveProvider(enabled[index], to - from)
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Color(0xFF22C55E) else MaterialTheme.colorScheme.error))
}

/** Страница одного провайдера: ключ, модель (со списком с сервера), адрес, проверка. */
@Composable
fun ProviderScreen(c: AppContainer, type: AIProviderType, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val p = s.provider(type)
    val scope = rememberCoroutineScope()
    var keyVersion by remember { mutableIntStateOf(0) }
    val hasKey = remember(type, keyVersion) { c.secrets.contains(KeystoreSecretStore.aiKey(type.id)) }
    var key by remember(type) { mutableStateOf("") }
    var endpoint by remember(type, p.endpoint) { mutableStateOf(p.endpoint) }
    var picker by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val needsKey = type != AIProviderType.CUSTOM

    LoliScreen(title = type.title, subtitle = type.about(), onBack = onBack) {
        item(key = "enable") {
            Group(Modifier.padding(top = 4.dp)) {
                SwitchItem(
                    "Использовать", if (p.enabled) "Участвует в ответах по приоритету" else "Выключен",
                    p.enabled, enabled = hasKey || !needsKey,
                ) { v -> scope.launch { c.settings.setProviderEnabled(type, v); if (v && !s.useAI) c.settings.setUseAI(true) } }
            }
            if (needsKey && !hasKey) Hint("Сначала сохраните API-ключ — после этого провайдер включится сам.")
        }
        if (needsKey) item(key = "key") {
            SectionLabel("API-ключ")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(
                            if (hasKey) "Ключ сохранён в зашифрованном виде" else "Ключ не задан",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    LoliField(key, { key = it }, if (hasKey) "Новый ключ" else "Вставьте ключ", keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Сохранить", {
                            c.secrets.put(KeystoreSecretStore.aiKey(type.id), ai.loli.core.ai.AIConfig.cleanApiKey(key)); key = ""; keyVersion++; result = null
                            testing = true
                            scope.launch {
                                c.settings.setProviderEnabled(type, true); c.settings.setUseAI(true)
                                // Сразу после сохранения: если выбранной модели у этого ключа нет — подбираем подходящую, затем проверяем связь.
                                val ids = runCatching { ai.loli.core.ai.AIProviderFactory.listModels(c.http, c.aiConfig(type)) }.getOrNull()?.map { it.id }.orEmpty()
                                if (ids.isNotEmpty() && p.effectiveModel !in ids) {
                                    val pick = type.suggestedModels.firstOrNull { it in ids } ?: ids.first()
                                    c.settings.setProviderConfig(type, pick, p.endpoint)
                                    kotlinx.coroutines.delay(150)
                                }
                                result = testAiConnection(c, type).fold({ it }, { "Ошибка: ${it.message}" })
                                testing = false
                            }
                        }, enabled = key.isNotBlank())
                        if (hasKey) SecondaryButton("Удалить", {
                            c.secrets.put(KeystoreSecretStore.aiKey(type.id), null); keyVersion++
                            scope.launch { c.settings.setProviderEnabled(type, false) }
                        }, danger = true)
                    }
                }
            }
            Hint("Ключ хранится только на этом телефоне (Android Keystore), не синхронизируется и не показывается повторно.")
        }
        item(key = "model") {
            SectionLabel("Модель")
            Group {
                RowItem(
                    title = p.effectiveModel.ifBlank { "Не выбрана" },
                    subtitle = if (p.model.isBlank() && type.defaultModel.isNotBlank()) "по умолчанию · нажмите, чтобы выбрать другую" else "нажмите, чтобы выбрать другую",
                    icon = Icons.Rounded.Memory, chevron = true, onClick = { picker = true },
                )
            }
        }
        if (type.endpointEditable) item(key = "endpoint") {
            SectionLabel("Адрес API")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoliField(endpoint, { endpoint = it.trim() }, "Адрес", placeholder = type.defaultEndpoint, keyboardType = KeyboardType.Uri,
                        supporting = if (type == AIProviderType.CUSTOM) "Например, http://192.168.1.10:11434/v1 для Ollama. http:// — только в локальной сети" else null)
                    if (endpoint != p.endpoint) PrimaryButton("Сохранить адрес", { scope.launch { c.settings.setProviderConfig(type, p.model, endpoint) } })
                }
            }
        }
        item(key = "test") {
            Group(Modifier.padding(top = 16.dp)) {
                RowItem(
                    title = if (testing) "Проверяю…" else "Проверить подключение",
                    subtitle = result,
                    icon = Icons.Rounded.Link,
                    onClick = if (!testing && (hasKey || !needsKey)) ({
                        testing = true
                        scope.launch {
                            result = testAiConnection(c, type).fold({ it }, { "Ошибка: ${it.message}" })
                            testing = false
                        }
                    }) else null,
                    trailing = { if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                )
            }
        }
        item(key = "last-failure") {
            val tick = ai.loli.app.ui.components.rememberResumeTick()
            val failure = remember(tick, result) { c.engine.lastAiFailure }
            failure?.let { f ->
                val at = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale("ru")).format(f.at.atZone(java.time.ZoneId.systemDefault()))
                Hint("Последний сбой AI ($at): ${f.message}\nИз-за него команда была выполнена на устройстве. Нажмите «Проверить подключение» — проверка делает такой же запрос, как при работе.")
            }
        }
    }

    if (picker) {
        ModelPicker(c, type, current = p.effectiveModel, canLoad = hasKey || !needsKey, onDismiss = { picker = false }) { model ->
            scope.launch { c.settings.setProviderConfig(type, model, p.endpoint) }
            picker = false
        }
    }
}

/** Выбор модели: список с сервера провайдера (по вашему ключу) + известные модели + своя. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(c: AppContainer, type: AIProviderType, current: String, canLoad: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf<List<ModelInfo>?>(c.modelCache[type]) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { AIProviderFactory.listModels(c.http, c.aiConfig(type)) }
                .onSuccess { loaded = it; c.modelCache[type] = it }
                .onFailure { error = it.message ?: "не удалось загрузить список" }
            loading = false
        }
    }
    LaunchedEffect(type) { if (canLoad && loaded == null) load() }

    val known = type.suggestedModels.map { ModelInfo(it) }
    val all = ((loaded ?: emptyList()) + known).distinctBy { it.id }
    val shown = if (query.isBlank()) all else all.filter { it.id.contains(query, true) || it.name.contains(query, true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Модель ${type.title}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            loading -> "Загружаю список с сервера…"
                            loaded != null -> "Доступно по вашему ключу: ${loaded?.size ?: 0}"
                            !canLoad -> "Сохраните ключ, чтобы увидеть все модели"
                            else -> error ?: "Известные модели"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canLoad) IconButton(onClick = { load() }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, contentDescription = "Обновить список")
                }
            }
            SearchBox(query, { query = it }, "Найти или ввести свою модель", Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (query.isNotBlank() && all.none { it.id.equals(query.trim(), true) }) {
                    item(key = "custom") {
                        RowItem(title = "Использовать «${query.trim()}»", subtitle = "Своя модель — как она называется в API провайдера", onClick = { onPick(query.trim()) })
                    }
                }
                items(shown, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.name != m.id) Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (m.id == current) Icon(Icons.Rounded.Check, contentDescription = "Выбрана", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                item(key = "bottom") { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
            if (!canLoad) TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)) { Text("Закрыть") }
        }
    }
}


/** Офлайн-модель: свободный разговор без интернета и ключей (~1 ГБ, скачивается один раз). */
@Composable
private fun OfflineModelSection(c: AppContainer) {
    val state by c.offlineLlm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(c.offlineLlm.enabled) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val metered = remember {
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
    }
    SectionLabel("Офлайн-модель · без интернета")
    Group {
        when (val st = state) {
            ai.loli.app.llm.OfflineLlm.State.Ready -> {
                SwitchItem(
                    "Отвечать на любые вопросы",
                    "Qwen 2.5 1.5B на телефоне: «почему небо голубое», «посоветуй фильм». Если подключён облачный AI — отвечает он",
                    enabled, icon = Icons.Rounded.AutoAwesome,
                ) { v -> enabled = v; c.offlineLlm.enabled = v }
                GroupDivider()
                RowItem(title = "Удалить модель", subtitle = "Освободит ~1 ГБ", onClick = { c.offlineLlm.delete() })
            }
            ai.loli.app.llm.OfflineLlm.State.Missing, is ai.loli.app.llm.OfflineLlm.State.Failed -> RowItem(
                title = "Скачать офлайн-модель (~1 ГБ)",
                subtitle = (st as? ai.loli.app.llm.OfflineLlm.State.Failed)?.let { "Не получилось: ${it.message}" }
                    ?: ("Свободный разговор без интернета и без ключей. Ответ за несколько секунд на современных телефонах" +
                        if (metered) ". Сейчас мобильный интернет — лучше по Wi‑Fi" else ""),
                icon = Icons.Rounded.AutoAwesome,
                trailing = { TextButton(onClick = { scope.launch { c.offlineLlm.download() } }) { Text(if (st is ai.loli.app.llm.OfflineLlm.State.Failed) "Ещё раз" else "Скачать") } },
            )
            is ai.loli.app.llm.OfflineLlm.State.Downloading -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Скачиваю модель · ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Text("Можно закрыть экран — при обрыве загрузка продолжится с того же места.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            ai.loli.app.llm.OfflineLlm.State.Verifying -> RowItem(title = "Проверяю файл…", subtitle = "Контрольная сумма, несколько секунд", icon = Icons.Rounded.AutoAwesome)
            is ai.loli.app.llm.OfflineLlm.State.Unsupported -> RowItem(title = "Недоступна на этом телефоне", subtitle = st.reason.replaceFirstChar { it.uppercase() }, icon = Icons.Rounded.AutoAwesome)
        }
    }
    Hint("Модель Qwen 2.5 (Apache-2.0) работает прямо на телефоне: вопросы никуда не отправляются. По уму она проще облачных AI — для бытовых вопросов и советов.")
}


/** «Облако Лоли»: умный разговор через сервер Лоли, без своего ключа — нужен только вход в аккаунт. */
@Composable
private fun LoliCloudSection(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val signedIn = auth is ai.loli.core.auth.AuthState.SignedIn
    SectionLabel("Облако ${s.assistantName}")
    Group(Modifier.padding(top = 4.dp)) {
        SwitchItem(
            "Облако ${s.assistantName}",
            when {
                !signedIn -> "Войдите в аккаунт («Настройки → Аккаунт») — и ${s.assistantName} ответит на любые вопросы без своего ключа"
                s.loliCloud -> "Включено: любые вопросы и сложные фразы через сервер ${s.assistantName}. Есть дневной лимит"
                else -> "Выключено"
            },
            s.loliCloud && signedIn, enabled = signedIn, icon = Icons.Rounded.AutoAwesome,
        ) { v -> scope.launch { c.settings.setLoliCloud(v) } }
    }
}
