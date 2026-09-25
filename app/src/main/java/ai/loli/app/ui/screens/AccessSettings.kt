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
import ai.loli.app.device.InstalledApp
import ai.loli.app.device.LoliAccessibilityService
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

    LoliScreen(title = "Доступ", subtitle = "Что Лоли разрешено делать", onBack = onBack) {
        item(key = "lock") {
            SectionLabel("На заблокированном экране")
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
        }

        item(key = "keys") {
            SectionLabel("Вызов кнопками")
            Group {
                RowItem(
                    title = if (a11y) "Спецвозможности включены" else "Включите «Лоли» в спецвозможностях",
                    subtitle = if (a11y) "Кнопки громкости и системные команды голосом работают"
                    else "Нужно для вызова кнопками громкости и команд «назад», «домой», «скриншот», «заблокируй экран». Лоли не читает экран.",
                    icon = Icons.Rounded.VolumeUp, chevron = !a11y,
                    onClick = if (a11y) null else ({ runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }),
                )
                KeyTrigger.entries.forEach { t ->
                    GroupDivider(inset = 52.dp)
                    RadioRow(t.title, t.hint, s.keyTrigger == t) {
                        scope.launch { c.settings.setKeyTrigger(t) }
                        if (t != KeyTrigger.NONE && !a11y) runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }
                }
            }
            Hint(
                "Кнопку питания Android приложениям перехватывать не даёт. Чтобы вызывать Лоли долгим нажатием питания — выберите её ассистентом по умолчанию " +
                    "(Настройки Лоли → Голос). На Samsung: Настройки → Дополнительные функции → Боковая клавиша → Двойное нажатие → Лоли.",
            )
        }

        item(key = "applock") {
            SectionLabel("Защита приложения")
            Group {
                SwitchItem(
                    "Вход по отпечатку или PIN-коду",
                    if (deviceSecure) "Лоли попросит подтвердить личность при открытии (после 1 минуты в фоне)"
                    else "Сначала включите блокировку экрана в настройках телефона",
                    s.appLock, enabled = deviceSecure || s.appLock, icon = Icons.Rounded.Fingerprint,
                ) { v -> scope.launch { c.settings.setAppLock(v) } }
            }
        }

        item(key = "apps-head") {
            SectionLabel("Приложения, которые Лоли может открывать")
            Group {
                SwitchItem(
                    "Обычные приложения — разрешены", "Банки, платёжные, Госуслуги, криптокошельки и менеджеры паролей закрыты всегда, пока вы их не разрешите",
                    s.autoAllowApps,
                ) { v -> scope.launch { c.settings.setAutoAllowApps(v) } }
            }
            SearchBox(query, { query = it }, "Найти приложение", Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        item(key = "apps") {
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

@Composable
private fun SensitiveBadge() {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { Text("₽", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
}

