package ai.loli.app.ui.screens

import android.app.KeyguardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.MoreToggle
import ai.loli.app.device.InstalledApp
import ai.loli.app.device.LoliAccessibilityService
import androidx.compose.material.icons.rounded.PowerSettingsNew
import ai.loli.app.settings.KeyTrigger
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SearchBox
import ai.loli.app.ui.components.SectionLabel
import ai.loli.app.ui.components.SwitchItem
import ai.loli.app.ui.components.rememberResumeTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Доступ и безопасность»: что Лоли может делать на заблокированном экране, какие приложения открывать,
 * вызов кнопками громкости и вход в приложение по отпечатку.
 */
@Composable
fun AccessPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val a11y = remember(resumeTick) { LoliAccessibilityService.isEnabled }
    val deviceSecure = remember(resumeTick) { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { c.appAccess.installed() } }
    val p = s.lockPolicy
    var askA11y by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    if (guide) AssistantGuideDialog(s.assistantName) { guide = false }
    if (askA11y) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askA11y = false },
            shape = RoundedCornerShape(24.dp),
            icon = { androidx.compose.material3.Icon(Icons.Rounded.VolumeUp, contentDescription = null) },
            title = { Text("Разрешить кнопки громкости?") },
            text = {
                Text(
                    "Чтобы жест работал на всём телефоне, включите «${s.assistantName}» в спецвозможностях: откроется нужный экран, " +
                        "там переключатель и «Разрешить». ${s.assistantName} видит только нажатия кнопок громкости — не экран и не то, что вы печатаете.\n\n" +
                        "Если телефон пишет «Ограниченная настройка»: Настройки → Приложения → ${s.assistantName} → ⋮ → «Разрешить ограниченные настройки», затем повторите. " +
                        "Или один раз настройте через Shizuku (Настройки ${s.assistantName} → Голос → Ассистент по умолчанию) — тогда всё включится само.",
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askA11y = false; openAccessibility(context) }) { Text("Разрешить") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askA11y = false }) { Text("Позже") } },
        )
    }

    var advanced by rememberSaveable { mutableStateOf(false) }
    LoliScreen(title = "Безопасность", subtitle = "Что ${s.assistantName} разрешено делать", onBack = onBack) {
        item(key = "lock") {
            SectionLabel("Когда телефон заблокирован")
            val level = if (!s.lockScreenEnabled) ai.loli.core.assistant.LockPolicy.Level.NONE else p.level
            Group {
                listOf(
                    Triple(ai.loli.core.assistant.LockPolicy.Level.SAFE, "Только безопасное (рекомендую)", "Записать, таймер, погода, музыка, звонки. Ваши записи и сообщения — только после разблокировки"),
                    Triple(ai.loli.core.assistant.LockPolicy.Level.NONE, "Ничего без разблокировки", "${s.assistantName} попросит сначала разблокировать телефон"),
                    Triple(ai.loli.core.assistant.LockPolicy.Level.ALL, "Всё", "Кто держит телефон, увидит и изменит ваши записи"),
                ).forEachIndexed { i, (lv, title, sub) ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(title, sub, level == lv) {
                        scope.launch {
                            c.settings.setLockScreenEnabled(lv != ai.loli.core.assistant.LockPolicy.Level.NONE)
                            ai.loli.core.assistant.LockPolicy.of(lv)?.let { c.settings.setLockPolicy(it) }
                        }
                    }
                }
                if (level == ai.loli.core.assistant.LockPolicy.Level.CUSTOM) {
                    GroupDivider(inset = 52.dp)
                    RadioRow("Своя настройка", "Выбрана в «Дополнительно» ниже", true) { advanced = true }
                }
            }
        }
        item(key = "applock") {
            SectionLabel("Защита приложения")
            Group {
                SwitchItem(
                    "Вход по отпечатку или PIN-коду",
                    if (deviceSecure) "${s.assistantName} попросит подтвердить личность при открытии (после 1 минуты в фоне)"
                    else "Сначала включите блокировку экрана в настройках телефона",
                    s.appLock, enabled = deviceSecure || s.appLock, icon = Icons.Rounded.Fingerprint,
                ) { v -> scope.launch { c.settings.setAppLock(v) } }
            }
            Hint("Ключи, вход и записи зашифрованы на телефоне. Обновления ставятся только с GitHub, с проверкой подписи.")
        }
        item(key = "perms") { PermissionsGroup(c) }
        item(key = "more") { MoreToggle(advanced, { advanced = !advanced }) }

        if (advanced) item(key = "lock-fine") {
            SectionLabel("Экран блокировки — подробно")
            Group {
                SwitchItem("Работать без разблокировки", "Вызов кнопкой, голосом или жестом, пока телефон заблокирован", s.lockScreenEnabled, icon = Icons.Rounded.Lock) { v ->
                    scope.launch { c.settings.setLockScreenEnabled(v) }
                }
                if (s.lockScreenEnabled) {
                    GroupDivider(inset = 66.dp)
                    SwitchItem("Записывать новое", "Заметки, расходы, задачи, напоминания, «запомни»", p.create, icon = Icons.Rounded.NoteAdd) { v ->
                        scope.launch { c.settings.setLockPolicy(p.copy(create = v)) }
                    }
                    GroupDivider(inset = 66.dp)
                    SwitchItem("Таймеры и управление", "Будильник, таймер, фонарик, музыка, громкость", p.basicDevice, icon = Icons.Rounded.Alarm) { v ->
                        scope.launch { c.settings.setLockPolicy(p.copy(basicDevice = v)) }
                    }
                    GroupDivider(inset = 66.dp)
                    SwitchItem("Звонки и сообщения", "«Позвони маме», «напиши Саше…»", p.calls, icon = Icons.Rounded.Call) { v ->
                        scope.launch { c.settings.setLockPolicy(p.copy(calls = v)) }
                    }
                    GroupDivider(inset = 66.dp)
                    SwitchItem("Показывать записи", "Расходы, задачи, память — видны всем, кто держит телефон", p.view, icon = Icons.Rounded.Visibility) { v ->
                        scope.launch { c.settings.setLockPolicy(p.copy(view = v)) }
                    }
                    GroupDivider(inset = 66.dp)
                    SwitchItem("Изменять и удалять", "Правка и удаление записей", p.edit, icon = Icons.Rounded.Edit) { v ->
                        scope.launch { c.settings.setLockPolicy(p.copy(edit = v)) }
                    }
                    GroupDivider(inset = 66.dp)
                    SwitchItem("Открывать приложения", "Только разрешённые ниже; остальное — после разблокировки", p.apps, icon = Icons.Rounded.Apps) { v ->
                        scope.launch { c.settings.setLockPolicy(p.copy(apps = v)) }
                    }
                }
            }
            Hint("Результат команды на заблокированном экране придёт уведомлением. Его текст виден на экране блокировки, только если это разрешено в настройках уведомлений телефона.")
            Group(Modifier.padding(top = 8.dp)) {
                SwitchItem(
                    "Скрывать экран ${s.assistantName}",
                    "Не показывать содержимое в недавних приложениях, запретить скриншоты и запись экрана",
                    s.secureScreen, icon = Icons.Rounded.Visibility,
                ) { v -> scope.launch { c.settings.setSecureScreen(v) } }
            }
        }

        if (advanced) item(key = "keys") {
            SectionLabel("Вызов кнопками")
            val assistant = remember(resumeTick) { isDefaultAssistant(context) == true }
            Group {
                RowItem(
                    title = "Долгое нажатие питания",
                    subtitle = if (assistant) "Вызывает ${s.assistantName}" else "Сейчас вызывает другой ассистент — нажмите, чтобы выбрать ${s.assistantName}",
                    icon = Icons.Rounded.PowerSettingsNew, chevron = !assistant,
                    onClick = if (assistant) null else ({ guide = true }),
                )
                GroupDivider(inset = 66.dp)
                RowItem(
                    title = if (a11y) "Кнопки громкости: разрешено" else "Кнопки громкости: нет разрешения",
                    subtitle = when {
                        a11y && s.keyTrigger == KeyTrigger.NONE -> "Выберите жест ниже. Работает и на заблокированном экране"
                        a11y -> "Работает: ${s.keyTrigger.title.lowercase()}"
                        s.keyTrigger != KeyTrigger.NONE -> "Жест выбран, но выключен в настройках телефона (Спецвозможности → ${s.assistantName}). Нажмите, чтобы включить"
                        else -> "Нужно включить ${s.assistantName} в спецвозможностях телефона. Экран Лоли не читает"
                    },
                    icon = Icons.Rounded.VolumeUp, chevron = !a11y,
                    onClick = if (a11y) null else ({ openAccessibility(context) }),
                )
                KeyTrigger.entries.forEach { t ->
                    GroupDivider(inset = 52.dp)
                    val hint = if (t != KeyTrigger.NONE && s.keyTrigger == t && !a11y) "Ждёт разрешения в настройках телефона" else t.hint
                    RadioRow(t.title, hint, s.keyTrigger == t) {
                        scope.launch { c.settings.setKeyTrigger(t) }
                        if (t != KeyTrigger.NONE && !a11y) askA11y = true
                    }
                }
            }
            Hint(
                "Настройки телефона и ${s.assistantName} связаны: если выключить ${s.assistantName} в спецвозможностях, жест перестанет работать и здесь это будет видно. " +
                    "Во время звонка кнопки громкости работают как обычно. Если на телефоне тот же жест уже занят (например, двойное нажатие «−» для камеры) — выберите другой.\n\n" +
                    "Ещё способ: в настройках телефона Спецвозможности → «Кнопка/жест спецвозможностей» или «Быстрое включение» (удержание обеих кнопок громкости) → ${s.assistantName}.",
            )
        }

        if (advanced) item(key = "apps-head") {
            SectionLabel("Приложения, которые Лоли может открывать")
            Group {
                SwitchItem(
                    "Обычные приложения — разрешены", "Банки, платёжные, Госуслуги, криптокошельки и менеджеры паролей закрыты всегда, пока вы их не разрешите",
                    s.autoAllowApps,
                ) { v -> scope.launch { c.settings.setAutoAllowApps(v) } }
            }
            SearchBox(query, { query = it }, "Найти приложение", Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        if (advanced) item(key = "apps") {
            val list = apps
            if (list == null) {
                Hint("Загружаю список приложений…")
            } else {
                val shown = if (query.isBlank()) list else list.filter { it.label.contains(query, true) }
                Group(Modifier.padding(top = 8.dp)) {
                    shown.forEachIndexed { i, app ->
                        if (i > 0) GroupDivider()
                        val allowed = c.appAccess.isAllowed(app.packageName, app.label)
                        val subtitle = when {
                            s.appAccess[app.packageName] == true -> "Разрешено вами"
                            s.appAccess[app.packageName] == false -> "Запрещено вами"
                            app.sensitive -> "Финансы и личное — закрыто по умолчанию"
                            allowed -> "Разрешено автоматически"
                            else -> "Закрыто"
                        }
                        val toggle: @Composable () -> Unit = {
                            Switch(checked = allowed, onCheckedChange = { v -> scope.launch { c.settings.setAppAccess(app.packageName, v) } })
                        }
                        if (app.sensitive) RowItem(title = app.label, subtitle = subtitle, leading = { SensitiveBadge() }, trailing = toggle)
                        else RowItem(title = app.label, subtitle = subtitle, trailing = toggle)
                    }
                }
            }
        }
    }
}

/** Экран службы Лоли в спецвозможностях (Android 11+), иначе общий список спецвозможностей. */
fun openAccessibility(context: android.content.Context) {
    // Разрешение WRITE_SECURE_SETTINGS уже выдано (Shizuku/ADB) — включаем сами, без экрана настроек.
    val system = ai.loli.app.device.SystemAccess(context)
    if (system.canWriteSecureSettings() && system.enableAccessibility()) {
        android.widget.Toast.makeText(context, "Кнопки громкости включены", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val component = android.content.ComponentName(context, LoliAccessibilityService::class.java).flattenToString()
    val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SensitiveBadge() {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { Text("₽", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
}

