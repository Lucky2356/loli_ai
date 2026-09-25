package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.AssistantOrb
import ai.loli.app.ui.components.LoliField
import ai.loli.app.ui.components.OrbMode
import ai.loli.app.ui.components.PrimaryButton
import ai.loli.app.ui.components.SecondaryButton
import ai.loli.core.auth.AuthException
import ai.loli.core.auth.SignUpResult
import kotlinx.coroutines.launch

/** Вход и регистрация по email/паролю через Supabase Auth. Пароль в приложении не хранится. */
@Composable
fun AuthScreen(c: AppContainer, onClose: (() -> Unit)?, onDone: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val configured = c.supabaseConfig().isConfigured

    fun submit(block: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            try { block() } catch (e: AuthException) { message = e.message } catch (e: Exception) { message = "Ошибка: ${e.message}" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
        }
        AssistantOrb(if (busy) OrbMode.THINKING else OrbMode.IDLE, 0f, size = 150.dp)
        Text(if (register) "Создать аккаунт" else "Вход", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Аккаунт нужен только для синхронизации между устройствами и резервной копии в облаке. Без него всё работает на телефоне.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!configured) {
            Text(
                "Сервер синхронизации не настроен в этой сборке. Укажите свой Supabase в «Настройки → Аккаунт» или продолжайте без аккаунта.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        LoliField(email, { email = it.trim() }, "Email", keyboardType = KeyboardType.Email, enabled = configured)
        LoliField(password, { password = it }, "Пароль", keyboardType = KeyboardType.Password, enabled = configured,
            visualTransformation = PasswordVisualTransformation(), supporting = if (register) "Минимум 8 символов" else null)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
        PrimaryButton(
            if (busy) "Подождите…" else if (register) "Зарегистрироваться" else "Войти",
            {
                submit {
                    if (register) {
                        when (val r = c.auth.signUp(email, password)) {
                            is SignUpResult.SignedIn -> { c.settings.setLocalOnly(false); c.onSignedIn(); onDone() }
                            is SignUpResult.ConfirmationRequired -> { message = "Мы отправили письмо на ${r.email}. Подтвердите адрес и войдите."; register = false }
                        }
                    } else {
                        c.auth.signIn(email, password)
                        c.settings.setLocalOnly(false)
                        c.onSignedIn()
                        onDone()
                    }
                }
            },
            enabled = configured && !busy && email.contains("@") && password.length >= (if (register) 8 else 1),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = configured, onClick = { register = !register; message = null }) {
            Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
        }
        if (!register) {
            TextButton(enabled = configured && email.contains("@") && !busy, onClick = {
                submit { c.auth.resetPassword(email); message = "Письмо для сброса пароля отправлено на $email." }
            }) { Text("Забыли пароль?") }
        }
        SecondaryButton("Продолжить без аккаунта", { scope.launch { c.settings.setLocalOnly(true); c.auth.useLocalOnly(); onDone() } }, modifier = Modifier.fillMaxWidth())
    }
}
