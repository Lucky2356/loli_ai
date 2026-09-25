package ai.loli.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние сети для индикатора на главном экране и запуска синхронизации при появлении связи. */
class NetworkMonitor(context: Context, private val onOnline: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { update() }
                override fun onLost(network: Network) { update() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update() }
            })
        }
    }

    private fun update() {
        val was = _online.value
        val now = isOnlineNow()
        _online.value = now
        if (now && !was) onOnline()
    }

    private fun isOnlineNow(): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
