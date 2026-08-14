package com.block154.courierpilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class CurrentLocationFix(
    val point: RoutePoint,
    val accuracyMeters: Float?,
    val ageMillis: Long,
    val provider: String,
)

internal object RouteResearchLocation {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun requestCurrent(context: Context, callback: (Result<CurrentLocationFix>) -> Unit) {
        if (!hasPermission(context)) {
            callback(Result.failure(SecurityException("Location permission is required")))
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            callback(Result.failure(IllegalStateException("No enabled location provider")))
            return
        }
        val completed = AtomicBoolean(false)
        var remaining = providers.size
        var best: Location? = null
        providers.forEach { provider ->
            runCatching {
                manager.getCurrentLocation(provider, CancellationSignal(), context.mainExecutor) { location ->
                    if (location != null && (best == null || score(location) > score(best!!))) best = location
                    remaining--
                    if (remaining == 0 && completed.compareAndSet(false, true)) {
                        val chosen = best
                        if (chosen == null) callback(Result.failure(IllegalStateException("Could not obtain current location")))
                        else callback(Result.success(chosen.toFix()))
                    }
                }
            }.onFailure {
                remaining--
                if (remaining == 0 && completed.compareAndSet(false, true)) {
                    val chosen = best
                    if (chosen == null) callback(Result.failure(it)) else callback(Result.success(chosen.toFix()))
                }
            }
        }
    }

    fun bestLastKnown(context: Context): CurrentLocationFix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            manager.allProviders.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull(::score)?.toFix()
        }.getOrNull()
    }

    private fun score(location: Location): Double {
        val agePenalty = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 1000.0).coerceAtMost(3600.0)
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 500.0
        return -accuracy - agePenalty * 0.25
    }

    private fun Location.toFix(): CurrentLocationFix = CurrentLocationFix(
        point = RoutePoint(latitude, longitude),
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        ageMillis = (System.currentTimeMillis() - time).coerceAtLeast(0L),
        provider = provider.orEmpty(),
    )
}

internal object RouteResearchGeocoder {
    fun resolve(context: Context, address: String, callback: (Result<RoutePoint>) -> Unit) {
        val query = address.trim()
        if (query.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Address is empty")))
            return
        }
        if (!Geocoder.isPresent()) {
            callback(Result.failure(IllegalStateException("Android geocoder is not available on this device")))
            return
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocationName(query, 1) { results ->
                val first = results.firstOrNull()
                if (first == null) callback(Result.failure(IllegalArgumentException("Address not found")))
                else callback(Result.success(RoutePoint(first.latitude, first.longitude)))
            }
        } else {
            Thread {
                val result = runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 1)?.firstOrNull()
                        ?.let { RoutePoint(it.latitude, it.longitude) }
                        ?: error("Address not found")
                }
                context.mainExecutor.execute { callback(result) }
            }.start()
        }
    }
}
