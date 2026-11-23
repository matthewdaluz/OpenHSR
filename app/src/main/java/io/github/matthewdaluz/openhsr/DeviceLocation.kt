package io.github.matthewdaluz.openhsr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.matthewdaluz.openhsr.data.Coordinates
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

fun Context.hasLocationPermission(): Boolean = canUse(Manifest.permission.ACCESS_COARSE_LOCATION) ||
    canUse(Manifest.permission.ACCESS_FINE_LOCATION)

private fun Context.canUse(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Coordinates? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    if (!context.hasLocationPermission()) return null
    val providers = buildList {
        if (context.canUse(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter(manager::isProviderEnabled)
    if (providers.isEmpty()) return null

    val cached = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= TimeUnit.SECONDS.toNanos(30) }
        .minByOrNull { it.accuracy }
    if (cached != null) return Coordinates(cached.latitude, cached.longitude)

    return withTimeoutOrNull(12_000) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(Coordinates(location.latitude, location.longitude))
                }

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
            }
            val registered = providers.map { provider ->
                runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            if (registered.none { it.isSuccess } && continuation.isActive) continuation.resume(null)
        }
    }
}
