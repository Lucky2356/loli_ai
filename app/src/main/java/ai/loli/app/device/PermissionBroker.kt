package ai.loli.app.device

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Разрешения «по ходу дела»: команда сама просит нужный доступ, а не отправляет человека в настройки.
 * «Позвони маме» без доступа к контактам → системный вопрос «Разрешить?» → звонок сразу после «Разрешить».
 * Вопрос показывает открытый экран Лоли (главный или окно ассистента); если экрана нет — просто false.
 */
class PermissionBroker(private val context: Context) {
    class Request(val permissions: List<String>, val result: CompletableDeferred<Boolean>)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    fun granted(vararg permissions: String): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** true — всё разрешено (уже было или пользователь согласился сейчас). */
    suspend fun ensure(vararg permissions: String): Boolean {
        if (granted(*permissions)) return true
        if (_requests.subscriptionCount.value == 0) return false // нет открытого экрана, спросить некому
        val request = Request(permissions.toList(), CompletableDeferred())
        if (!_requests.tryEmit(request)) return false
        return withTimeoutOrNull(120_000) { request.result.await() } ?: false
    }
}
