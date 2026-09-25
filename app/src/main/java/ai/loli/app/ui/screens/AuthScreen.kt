package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import ai.loli.app.ui.components.OrbMode
import ai.loli.core.auth.AuthException
import ai.loli.core.auth.SignUpResult
import kotlinx.coroutines.launch

/** Вход и регистрация по email/паролю через Supabase Auth. Пароль в приложении не хранится. */
@Composable
fun AuthScreen(c: AppContainer, onDone: () -> Unit) {
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AssistantOrb(if (busy) OrbMode.THINKING else OrbMode.IDLE, 0f, size = 150.dp)
        Text("Лоли", style = MaterialTheme.typography.headlineLarge)
        Text("Ваш личный AI-ассистент. Войдите, чтобы память синхронизировалась между устройствами.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!configured) {
            Text(
                "Сервер синхронизации не настроен в этой сборке. Можно работать без аккаунта или указать свой Supabase ниже.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.tertiary,
            )
            ServerConfig(c)
        }
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, enabled = configured,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, enabled = configured,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { if (register) Text("Минимум 8 символов") }, modifier = Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Button(
            enabled = configured && !busy && email.contains("@") && password.length >= (if (register) 8 else 1),
            onClick = {
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
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Подождите…" else if (register) "Зарегистрироваться" else "Войти") }
        TextButton(enabled = configured, onClick = { register = !register; message = null }) {
            Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
        }
        if (!register) {
            TextButton(enabled = configured && email.contains("@") && !busy, onClick = {
                submit { c.auth.resetPassword(email); message = "Письмо для сброса пароля отправлено на $email." }
            }) { Text("Забыли пароль?") }
        }
        OutlinedButton(onClick = { scope.launch { c.settings.setLocalOnly(true); c.auth.useLocalOnly(); onDone() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Продолжить без аккаунта")
        }
        Text("Без аккаунта всё работает на устройстве; войти можно позже в настройках.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
