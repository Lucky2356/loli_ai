package ai.loli.app.ui.components

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.loli.app.AppContainer
import ai.loli.app.device.BackgroundLauncher
import ai.loli.app.device.LoliAccessibilityService
import ai.loli.app.ui.screens.AssistantGuideDialog
import ai.loli.app.ui.screens.isBatteryExempt
import ai.loli.app.ui.screens.isDefaultAssistant
import ai.loli.app.ui.screens.openAccessibility
import ai.loli.app.ui.screens.requestBatteryExemption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class SetupStep(
    val id: String,
    val icon: ImageVector,
    val title: String,
    /** Что нажать в системном окне — с учётом производителя телефона. */
    val hint: String,
    /** Необязательный шаг: нужен только для отдельных функций, его можно пройти позже. */
    val optional: Boolean = false,
    val action: () -> Unit,
)

/**
 * «Настройте Лоли»: чего не хватает для полной работы. Кнопка «Настроить всё» проходит шаги сама:
 * открывает системный экран, а когда человек возвращается — сразу следующий. Если запущен Shizuku,
 * большую часть Лоли включает сама, без экранов.
 */
@Composable
fun SetupCard(c: AppContainer, name: String, onVisible: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("loli_ui", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("setup_dismissed", false)) }
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    var guide by remember { mutableStateOf(false) }
    var auto by rememberSaveable { mutableStateOf(false) }
    var attemptedCsv by rememberSaveable { mutableStateOf("") }
    val attempted = attemptedCsv.split(',').filter { it.isNotEmpty() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val basePerms = remember(tick, refresh) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.all { granted(it) }
    }
    val overlay = remember(tick) { c.launcher.canLaunchFromBackground() }
    val a11y = remember(tick) { LoliAccessibilityService.isEnabled }
    val assistant = remember(tick, guide) { isDefaultAssistant(context) != false }
    val battery = remember(tick) { isBatteryExempt(context) }
    val exact = remember(tick) { Build.VERSION.SDK_INT !in 31..32 || c.reminderScheduler.canScheduleExact() }

    // Одним окном: микрофон, уведомления, а заодно контакты и календарь — для звонков и событий голосом.
    val permsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val allPerms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
        }.toTypedArray()
    }

    val oem = remember { OemHints.current() }
    // Шаги производителя (автозапуск Xiaomi/Huawei/Oppo) нельзя проверить — отмечаем пройденными после посещения.
    var oemDone by remember { mutableStateOf(prefs.getBoolean("oem_autostart_done", false)) }
    val todo = listOfNotNull(
        if (!basePerms) SetupStep("perms", Icons.Rounded.Mic, "Микрофон и уведомления",
            "В окне «Разрешить?» нажимайте «Разрешить» или «При использовании приложения».") { permsLauncher.launch(allPerms) } else null,
        if (!assistant) SetupStep("assistant", Icons.Rounded.TouchApp, "Сделать $name ассистентом",
            "Чтобы вызывать $name долгим нажатием кнопки «Домой» или жестом.", optional = true) { guide = true } else null,
        if (!overlay) SetupStep("overlay", Icons.Rounded.Layers, "Работа поверх приложений",
            oem.overlay, optional = true) {
            runCatching { context.startActivity(BackgroundLauncher.overlaySettings(context)) }
        } else null,
        if (!a11y) SetupStep("a11y", Icons.Rounded.VolumeUp, "Кнопки громкости и системные команды",
            "Найдите в списке «$name» (иногда в разделе «Установленные приложения» или «Скачанные службы») и включите.", optional = true) { openAccessibility(context) } else null,
        if (!battery) SetupStep("battery", Icons.Rounded.BatteryChargingFull, "Не усыплять ради батареи", oem.battery) { requestBatteryExemption(context) } else null,
        if (!exact) SetupStep("exact", Icons.Rounded.Alarm, "Точные напоминания",
            "Включите переключатель «Разрешить» напротив $name.") {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")))
            }
        } else null,
        if (oem.autostart != null && !oemDone) SetupStep("autostart", Icons.Rounded.BatteryChargingFull, "Автозапуск (${oem.brand})", oem.autostart) {
            oemDone = true
            prefs.edit().putBoolean("oem_autostart_done", true).apply()
            OemHints.openAutostart(context)
        } else null,
    )
    val required = todo.filter { !it.optional }
    val optional = todo.filter { it.optional }
    var showOptional by rememberSaveable { mutableStateOf(false) }

    // Мастер: после возврата из системного экрана сразу открывает следующий шаг.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(auto, tick, refresh, guide, busy, todo.size, attemptedCsv, showOptional) {
        if (!auto || guide || busy) return@LaunchedEffect
        delay(900) // системный экран успевает открыться — тогда Лоли уже не на экране и ждёт возврата
        // Пока открыт системный экран или окно разрешения — ждём возвращения человека в Лоли.
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@LaunchedEffect
        val next = (if (showOptional) todo else required).firstOrNull { it.id !in attempted }
        if (next == null) {
            auto = false
            Toast.makeText(context, if (todo.isEmpty()) "$name настроена полностью" else if (required.isEmpty()) "Главное настроено. Дополнительные шаги — по желанию" else "Готово. Оставшиеся шаги можно пройти позже", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        attemptedCsv = (attempted + next.id).joinToString(",")
        next.action()
    }

    fun startAuto() {
        attemptedCsv = ""
        // Shizuku уже запущен — ассистент, спецвозможности и «поверх приложений» включаются без экранов.
        if (c.systemAccess.shizukuRunning()) {
            busy = true
            scope.launch {
                runCatching { c.systemAccess.setupWithShizuku() }
                refresh++
                busy = false
                auto = true
            }
        } else {
            auto = true
        }
    }

    if (guide) AssistantGuideDialog(name) { guide = false }
    val visible = !dismissed && todo.isNotEmpty()
    LaunchedEffect(visible) { onVisible(visible) }
    AnimatedVisibility(visible, exit = shrinkVertically() + fadeOut()) {
        Surface(shape = MaterialTheme.shapes.large, color = groupColor(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Настройте $name полностью", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                auto || busy -> "Настраиваю — после каждого экрана просто вернитесь назад"
                                required.isEmpty() -> "Главное готово. Остальное — по желанию"
                                else -> "Нужно ещё ${ai.loli.core.assistant.RuFormat.count(required.size, "шаг", "шага", "шагов")}"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { dismissed = true; auto = false; prefs.edit().putBoolean("setup_dismissed", true).apply() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Скрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PrimaryButton(
                    if (busy) "Включаю через Shizuku…" else if (auto) "Продолжить настройку" else "Настроить всё",
                    { if (auto) { attemptedCsv = ""; refresh++ } else startAuto() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    enabled = !busy,
                )
                required.forEach { step ->
                    RowItem(title = step.title, subtitle = step.hint, icon = step.icon, chevron = true, maxSubtitleLines = 3, onClick = step.action)
                }
                if (optional.isNotEmpty()) {
                    RowItem(
                        title = if (showOptional) "Скрыть дополнительные" else "Дополнительно (${optional.size})",
                        subtitle = if (showOptional) null else "Для вызова кнопками, жестом и поверх других приложений",
                        onClick = { showOptional = !showOptional },
                    )
                    if (showOptional) optional.forEach { step ->
                        RowItem(title = step.title, subtitle = step.hint, icon = step.icon, chevron = true, maxSubtitleLines = 3, onClick = step.action)
                    }
                }
            }
        }
    }
}
