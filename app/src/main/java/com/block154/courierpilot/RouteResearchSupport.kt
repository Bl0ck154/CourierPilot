package com.block154.courierpilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
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
        val handler = Handler(Looper.getMainLooper())
        val signals = mutableListOf<CancellationSignal>()
        var remaining = providers.size
        var best: Location? = null

        fun finish(result: Result<CurrentLocationFix>) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            signals.forEach { runCatching { it.cancel() } }
            callback(result)
        }

        fun finishFromBest(fallback: Throwable? = null) {
            val chosen = best
            if (chosen != null) finish(Result.success(chosen.toFix()))
            else finish(Result.failure(fallback ?: IllegalStateException("Could not obtain current location")))
        }

        handler.postAtTime(
            {
                if (!completed.get()) {
                    finishFromBest(IllegalStateException("Current location timed out after ${CURRENT_LOCATION_TIMEOUT_MS / 1000}s"))
                }
            },
            TIMEOUT_TOKEN,
            android.os.SystemClock.uptimeMillis() + CURRENT_LOCATION_TIMEOUT_MS,
        )

        providers.forEach { provider ->
            val signal = CancellationSignal()
            signals += signal
            runCatching {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (completed.get()) return@getCurrentLocation
                    if (location != null && (best == null || score(location) > score(best!!))) best = location

                    // Good fresh GPS is more useful to a time-sensitive offer than waiting for a
                    // second provider merely to shave a few meters off an already solid fix.
                    if (location != null && location.hasAccuracy() && location.accuracy <= EARLY_ACCEPT_ACCURACY_METERS) {
                        finish(Result.success(location.toFix()))
                        return@getCurrentLocation
                    }

                    remaining--
                    if (remaining == 0) finishFromBest()
                }
            }.onFailure { failure ->
                context.mainExecutor.execute {
                    if (completed.get()) return@execute
                    remaining--
                    if (remaining == 0) finishFromBest(failure)
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

    private const val CURRENT_LOCATION_TIMEOUT_MS = 8_000L
    private const val EARLY_ACCEPT_ACCURACY_METERS = 25f
    private val TIMEOUT_TOKEN = Any()
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
                val result = if (first == null) {
                    Result.failure(IllegalArgumentException("Address not found"))
                } else {
                    Result.success(RoutePoint(first.latitude, first.longitude))
                }
                context.mainExecutor.execute { callback(result) }
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
