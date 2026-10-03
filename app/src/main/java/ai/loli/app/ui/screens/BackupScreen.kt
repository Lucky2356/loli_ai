package ai.loli.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.backup.BackupNudge
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.app.ui.components.SectionLabel
import ai.loli.core.auth.AuthState
import ai.loli.core.backup.BackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * «Резервная копия»: все записи, секретные заметки, привычки и места — в один файл, зашифрованный вашим паролем.
 * Файл можно положить в облако или на компьютер и восстановить на новом телефоне. Пароль нигде не хранится.
 */
@Composable
fun BackupPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun done(text: String, error: Boolean) { message = text; failed = error; busy = false }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = c.backup.export(password.toCharArray(), includeChat = s.syncChat)
                    (context.contentResolver.openOutputStream(uri, "w") ?: error("не удалось открыть файл")).use { it.write(bytes) }
                }
                BackupNudge.markDone(context)
                done("Готово. Положите файл туда, где он переживёт этот телефон (облако, компьютер). Пароль не потеряйте: без него файл не открыть.", false)
            } catch (e: Exception) {
                done("Не удалось сохранить: ${e.message ?: e::class.simpleName}", true)
            }
        }
    }

    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val report = withContext(Dispatchers.IO) {
                    val bytes = readLimited(context, uri)
                    c.backup.restore(bytes, password.toCharArray())
                }
                // Будильники, зоны мест и синхронизация подхватывают восстановленное.
                c.rescheduleReminders()
                if (auth is AuthState.SignedIn) c.syncScheduler.requestSoon(1)
                val skipped = if (report.skippedNewerLocal > 0) " Пропущено ${report.skippedNewerLocal}: на телефоне уже есть более новые версии." else ""
                done("Восстановлено записей: ${report.total}.$skipped", false)
            } catch (e: BackupCodec.BackupException) {
                done(e.message ?: "Не удалось восстановить.", true)
            } catch (e: Exception) {
                done("Не удалось восстановить: ${e.message ?: e::class.simpleName}", true)
            }
        }
    }

    val validNew = password.length >= BackupCodec.MIN_PASSWORD && password == repeat
    LoliScreen(title = "Резервная копия", subtitle = "Перенос на новый телефон", onBack = onBack) {
        item(key = "intro") {
            Text(
                "Без аккаунта ваши записи живут только в этом телефоне: потеряли или сбросили телефон — они пропадут. " +
                    "Копия — один файл, зашифрованный паролем. ${s.assistantName} не запоминает пароль и не отправляет файл никуда сама.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        item(key = "save") {
            SectionLabel("Сохранить копию")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoliField(password, { password = it }, "Пароль для файла", keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(), supporting = "Не меньше ${BackupCodec.MIN_PASSWORD} знаков. Забудете — файл не открыть.")
                    LoliField(repeat, { repeat = it }, "Повторите пароль", keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                        supporting = if (repeat.isNotEmpty() && repeat != password) "Пароли не совпадают" else null)
                    PrimaryButton(if (busy) "Подождите…" else "Сохранить в файл", { message = null; save.launch("loli-${LocalDate.now()}.${BackupCodec.EXTENSION}") },
                        enabled = validNew && !busy)
                    Text(
                        if (s.syncChat) "В копию войдёт и переписка с ${s.assistantName} (вы включили её синхронизацию)."
                        else "Переписка с ${s.assistantName} в копию не входит. API-ключи и вход в аккаунт — тоже никогда.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "restore") {
            SectionLabel("Восстановить из файла")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Введите пароль выше и выберите файл. Записи добавятся к тем, что уже есть: более новые версии на телефоне не затираются.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryButton(if (busy) "Подождите…" else "Выбрать файл копии", { message = null; restore.launch(arrayOf("*/*")) },
                        enabled = password.isNotEmpty() && !busy)
                }
            }
        }
        message?.let { text ->
            item(key = "message") {
                Text(
                    text, style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        item(key = "last") {
            val last = BackupNudge.lastBackupAt(context)
            if (last > 0) Text(
                "Последняя копия с этого телефона: " + java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", java.util.Locale("ru"))
                    .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(last)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

/** Читает файл копии, но не больше предела: чужой огромный файл не должен забить память. */
private fun readLimited(context: android.content.Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("не удалось открыть файл")
    input.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > BackupCodec.MAX_FILE_BYTES) throw BackupCodec.NotABackup()
        }
        return out.toByteArray()
    }
}
