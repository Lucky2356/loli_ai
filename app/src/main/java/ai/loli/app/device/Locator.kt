package ai.loli.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Где телефон — через системный LocationManager: работает и без Google-сервисов (китайские прошивки). */
class Locator(private val context: Context) {
    private val lm get() = context.getSystemService(LocationManager::class.java)

    fun hasCoarse() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasFine() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackground() = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Последнее известное место не старше [maxAgeMs], иначе — свежее (до 8 секунд). */
    @SuppressLint("MissingPermission")
    suspend fun current(maxAgeMs: Long = 30 * 60_000L, precise: Boolean = false): Location? {
        if (!hasCoarse()) return null
        val m = lm ?: return null
        val providers = m.getProviders(true)
        val last = providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < maxAgeMs && (!precise || last.accuracy < 200)) return last
        val provider = when {
            precise && hasFine() && LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            hasFine() && LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        } ?: return last
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching { m.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) } }
                        .onFailure { cont.resume(null) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    cont.invokeOnCancellation { runCatching { m.removeUpdates(listener) } }
                    @Suppress("DEPRECATION")
                    runCatching { m.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.onFailure { cont.resume(null) }
                }
            }
        }
        return fresh ?: last
    }
}
