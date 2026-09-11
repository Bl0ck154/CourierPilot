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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class CurrentLocationFix(
    val point: RoutePoint,
    val accuracyMeters: Float?,
    val ageMillis: Long,
    val provider: String,
)

/**
 * Live-offer routing must not let an optimistic NETWORK fix beat real GPS simply because ColorOS
 * reports a small accuracy radius. Google Maps/Wolt use fused location; CourierPilot uses
 * LocationManager directly, so accepting NETWORK first can shift the route origin by kilometres.
 */
internal object RouteLiveLocationPolicy {
    fun shouldReuseCached(fix: CurrentLocationFix): Boolean =
        fix.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) &&
            fix.ageMillis <= LIVE_FIX_MAX_AGE_MS &&
            fix.accuracyMeters != null &&
            fix.accuracyMeters <= LIVE_FIX_MAX_ACCURACY_METERS

    /** Google Maps and Wolt both consume the fused provider. Prefer the same origin for live offers. */
    fun shouldReuseFused(fix: CurrentLocationFix): Boolean =
        fix.ageMillis <= FUSED_FIX_MAX_AGE_MS &&
            fix.accuracyMeters != null &&
            fix.accuracyMeters <= FUSED_FIX_MAX_ACCURACY_METERS

    fun shouldEarlyAccept(provider: String, accuracyMeters: Float?, gpsProviderEnabled: Boolean): Boolean {
        if (accuracyMeters == null || accuracyMeters > EARLY_ACCEPT_ACCURACY_METERS) return false
        return provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) || !gpsProviderEnabled
    }

    const val EARLY_ACCEPT_ACCURACY_METERS = 25f
    const val LIVE_FIX_MAX_AGE_MS = 30_000L
    const val LIVE_FIX_MAX_ACCURACY_METERS = 80f
    const val FUSED_FIX_MAX_AGE_MS = 20_000L
    const val FUSED_FIX_MAX_ACCURACY_METERS = 80f
    const val GPS_FALLBACK_MAX_ACCURACY_METERS = 200f
}

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
        val gpsProviderEnabled = LocationManager.GPS_PROVIDER in providers
        val signals = mutableListOf<CancellationSignal>()
        var remaining = providers.size
        var best: Location? = null
        var bestGps: Location? = null

        fun finish(result: Result<CurrentLocationFix>) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            signals.forEach { runCatching { it.cancel() } }
            callback(result)
        }

        fun finishFromBest(fallback: Throwable? = null) {
            val chosen = bestGps ?: best
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
                    if (location != null) {
                        if (best == null || score(location) > score(best!!)) best = location
                        val usableGps = provider == LocationManager.GPS_PROVIDER &&
                            (!location.hasAccuracy() ||
                                location.accuracy <= RouteLiveLocationPolicy.GPS_FALLBACK_MAX_ACCURACY_METERS)
                        if (usableGps && (bestGps == null || score(location) > score(bestGps!!))) bestGps = location
                    }

                    // Never let NETWORK terminate the race while GPS is enabled. Some ColorOS
                    // builds report a deceptively small NETWORK accuracy radius for a fix that is
                    // kilometres away. NETWORK remains a fallback if GPS fails or times out.
                    if (location != null && RouteLiveLocationPolicy.shouldEarlyAccept(
                            provider = provider,
                            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                            gpsProviderEnabled = gpsProviderEnabled,
                        )
                    ) {
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

    /**
     * Live Wolt routing must start from the same practical device position the courier sees in Wolt
     * and Google Maps. Those apps use Google Play services fused location, while raw LocationManager
     * GPS on the real ColorOS device has occasionally been displaced by well over a kilometre even
     * with a plausible accuracy field. Race a fused high-accuracy request against the legacy raw
     * request, but never let raw GPS win until the short fused preference window has finished.
     */
    fun requestForLiveOffer(context: Context, callback: (Result<CurrentLocationFix>) -> Unit) {
        if (!hasPermission(context)) {
            callback(Result.failure(SecurityException("Location permission is required")))
            return
        }
        val app = context.applicationContext
        val completed = AtomicBoolean(false)
        val lock = Any()
        var rawResult: Result<CurrentLocationFix>? = null
        var fusedFinished = false

        fun finish(result: Result<CurrentLocationFix>) {
            if (completed.compareAndSet(false, true)) callback(result)
        }

        // Start the raw Android path in parallel so a device without Google Play services does not
        // pay a second full GPS timeout. Its result is held briefly while fused location is pending.
        requestCurrent(app) { result ->
            val deliver = synchronized(lock) {
                rawResult = result
                fusedFinished
            }
            if (deliver) finish(result)
        }

        requestFusedForLiveOffer(app) { result ->
            if (result.isSuccess) {
                finish(result)
                return@requestFusedForLiveOffer
            }
            val raw = synchronized(lock) {
                fusedFinished = true
                rawResult
            }
            raw?.let(::finish)
        }
    }

    private fun requestFusedForLiveOffer(context: Context, callback: (Result<CurrentLocationFix>) -> Unit) {
        val completed = AtomicBoolean(false)
        val freshStarted = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val tokenSource = CancellationTokenSource()

        fun finish(result: Result<CurrentLocationFix>) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(FUSED_TIMEOUT_TOKEN)
            tokenSource.cancel()
            callback(result)
        }

        fun requestFresh(client: com.google.android.gms.location.FusedLocationProviderClient) {
            if (!freshStarted.compareAndSet(false, true) || completed.get()) return
            runCatching {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        if (location == null) {
                            finish(Result.failure(IllegalStateException("Fused location returned no fix")))
                        } else {
                            finish(Result.success(location.toFix(providerOverride = "fused")))
                        }
                    }
                    .addOnFailureListener { failure -> finish(Result.failure(failure)) }
            }.onFailure { failure -> finish(Result.failure(failure)) }
        }

        handler.postAtTime(
            {
                finish(Result.failure(IllegalStateException("Fused location timed out")))
            },
            FUSED_TIMEOUT_TOKEN,
            android.os.SystemClock.uptimeMillis() + FUSED_LOCATION_TIMEOUT_MS,
        )

        val client = runCatching { LocationServices.getFusedLocationProviderClient(context) }
            .getOrElse {
                finish(Result.failure(it))
                return
            }
        runCatching {
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (completed.get()) return@addOnSuccessListener
                    val fix = location?.toFix(providerOverride = "fused-cache")
                    if (fix != null && RouteLiveLocationPolicy.shouldReuseFused(fix)) {
                        finish(Result.success(fix))
                    } else {
                        requestFresh(client)
                    }
                }
                .addOnFailureListener { requestFresh(client) }
        }.onFailure { requestFresh(client) }
    }

    private fun bestLastKnownGps(context: Context): CurrentLocationFix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return@runCatching null
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.toFix()
        }.getOrNull()
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

    private fun Location.toFix(providerOverride: String? = null): CurrentLocationFix = CurrentLocationFix(
        point = RoutePoint(latitude, longitude),
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        ageMillis = (System.currentTimeMillis() - time).coerceAtLeast(0L),
        provider = providerOverride ?: provider.orEmpty(),
    )

    private const val CURRENT_LOCATION_TIMEOUT_MS = 8_000L
    private const val FUSED_LOCATION_TIMEOUT_MS = 3_000L
    private val TIMEOUT_TOKEN = Any()
    private val FUSED_TIMEOUT_TOKEN = Any()
}

internal object RouteGeocodeQueryPolicy {
    fun candidates(address: String, city: MarketCity?): List<String> {
        val raw = address.trim().replace(Regex("\\s+"), " ")
        if (raw.isBlank()) return emptyList()

        // Wolt commonly renders Lithuanian apartment addresses as `house-unit` (for example
        // `9a-15`). Android's Geocoder is inconsistent with that full form on some OEM backends.
        // Always try the exact address first, then fall back to the building-only form.
        val rawCandidates = listOf(raw, withoutUnitSuffix(raw)).distinct()
        val cityName = city?.name?.trim().orEmpty()
        if (cityName.isBlank() || containsToken(raw, cityName)) return rawCandidates

        val country = city?.countryCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val qualified = rawCandidates.map { candidate ->
            listOf(candidate, cityName, country.takeIf { it.length == 2 })
                .filterNotNull()
                .joinToString(", ")
        }
        return (qualified + rawCandidates).distinct()
    }

    private fun withoutUnitSuffix(value: String): String =
        value.replace(
            Regex("""(?i)(\b\d+\p{L}?)\s*-\s*\d+\p{L}?(?=\s*(?:,|$))"""),
            "$1",
        )

    private fun containsToken(value: String, token: String): Boolean {
        fun normalize(v: String) = v.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        val normalizedValue = normalize(value)
        val normalizedToken = normalize(token)
        return normalizedToken.isNotBlank() && normalizedValue.contains(normalizedToken)
    }
}

internal object RouteResearchGeocoder {
    private data class CachedPoint(val point: RoutePoint, val expiresAt: Long)

    private val lock = Any()
    private val cache = mutableMapOf<String, CachedPoint>()
    private val inFlight = mutableMapOf<String, MutableList<(Result<RoutePoint>) -> Unit>>()
    private val androidFailures = mutableMapOf<String, Throwable>()
    private val photonFailures = mutableMapOf<String, Throwable>()
    private val photonExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "CourierPilot-PhotonGeocoder").apply { isDaemon = true }
    }
    private val registryExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "CourierPilot-LtAddressRegistry").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun prewarm(context: Context, addresses: List<String>) {
        addresses
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { cacheKey(context, it) }
            .forEach { address -> resolve(context, address) { /* populate cache */ } }
    }

    /**
     * Recovery path for an implausible live route. It intentionally bypasses the hybrid cache and
     * Android Geocoder, because either can be the source of a bad coordinate. Photon still receives
     * only the textual address + city; the phone coordinate is used locally to rank returned places.
     */
    fun resolveStrictPhoton(
        context: Context,
        address: String,
        reference: RoutePoint?,
        callback: (Result<RoutePoint>) -> Unit,
    ) {
        val query = address.trim()
        if (query.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Address is empty")))
            return
        }
        val app = context.applicationContext
        val city = MarketCityResolver.cached(app)
        photonExecutor.execute {
            val point = PhotonAddressGeocoder.resolve(query, city, reference)
            app.mainExecutor.execute {
                if (point != null) callback(Result.success(point))
                else callback(Result.failure(IllegalArgumentException("Strict Photon address lookup failed")))
            }
        }
    }

    fun resolve(context: Context, address: String, callback: (Result<RoutePoint>) -> Unit) {
        val query = address.trim()
        if (query.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Address is empty")))
            return
        }

        val app = context.applicationContext
        val city = MarketCityResolver.cached(app)
        val candidates = RouteGeocodeQueryPolicy.candidates(query, city)
        val key = cacheKey(app, query)
        RouteGeocodePersistentCache.get(app, key)?.let { cached ->
            callback(Result.success(cached))
            return
        }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cache[key]?.takeIf { it.expiresAt > now }?.let { cached ->
                callback(Result.success(cached.point))
                return
            }
            cache.remove(key)
            val waiters = inFlight[key]
            if (waiters != null) {
                waiters += callback
                return
            }
            inFlight[key] = mutableListOf(callback)
            androidFailures.remove(key)
            photonFailures.remove(key)
        }

        // ColorOS/Android 16 occasionally never calls the platform Geocoder callback. Race it
        // against Photon so one broken backend cannot make an otherwise trivial Wolt route fail.
        val reference = RouteResearchLocation.bestLastKnown(app)?.point
        startLithuanianRegistryLookup(app, key, query, city)
        startPhotonLookup(app, key, query, city, reference)
        mainHandler.postDelayed({
            if (isInFlight(key)) {
                complete(key, Result.failure(IllegalStateException("Address geocoding timed out")))
            }
        }, HYBRID_TIMEOUT_MS)

        if (!Geocoder.isPresent()) {
            markBackendFailure(app, key, "android", IllegalStateException("Android geocoder is not available on this device"))
            return
        }

        val geocoder = Geocoder(app, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= 33) {
            fun attempt(index: Int) {
                if (!isInFlight(key)) return
                if (index >= candidates.size) {
                    markBackendFailure(app, key, "android", IllegalArgumentException("Address not found"))
                    return
                }
                runCatching {
                    geocoder.getFromLocationName(candidates[index], MAX_RESULTS) { results ->
                        if (!isInFlight(key)) return@getFromLocationName
                        val chosen = chooseBest(results, reference, city)
                        if (chosen == null) attempt(index + 1)
                        else app.mainExecutor.execute {
                            if (isInFlight(key)) {
                                val point = RoutePoint(chosen.latitude, chosen.longitude)
                                RouteGeocodePersistentCache.put(app, key, point)
                                complete(key, Result.success(point))
                            }
                        }
                    }
                }.onFailure { failure ->
                    if (index + 1 < candidates.size) attempt(index + 1)
                    else markBackendFailure(app, key, "android", failure)
                }
            }
            attempt(0)
        } else {
            Thread {
                val result = runCatching {
                    @Suppress("DEPRECATION")
                    candidates.asSequence().mapNotNull { candidate ->
                        val results = geocoder.getFromLocationName(candidate, MAX_RESULTS).orEmpty()
                        chooseBest(results, reference, city)
                    }.firstOrNull()?.let { RoutePoint(it.latitude, it.longitude) }
                        ?: error("Address not found")
                }
                app.mainExecutor.execute {
                    result.onSuccess { point ->
                        if (isInFlight(key)) {
                            RouteGeocodePersistentCache.put(app, key, point)
                            complete(key, Result.success(point))
                        }
                    }.onFailure { failure ->
                        markBackendFailure(app, key, "android", failure)
                    }
                }
            }.apply {
                name = "CourierPilotGeocoder"
                isDaemon = true
                start()
            }
        }
    }

    private fun startLithuanianRegistryLookup(
        context: Context,
        key: String,
        query: String,
        city: MarketCity?,
    ) {
        if (!city?.countryCode.equals("LT", ignoreCase = true)) return
        if (LithuanianAddressRegistryGeocoder.parseHouseAndPostcode(query) == null) return
        registryExecutor.execute {
            val resolution = LithuanianAddressRegistryGeocoder.resolve(query, city)
            context.mainExecutor.execute {
                if (!isInFlight(key) || resolution == null) return@execute
                RouteGeocodePersistentCache.put(context, key, resolution.point)
                CaptureEventLog.append(
                    context,
                    stage = "geocode_lt_registry",
                    platform = "",
                    message = "Lithuanian Address Register resolved house + postcode directly",
                    dedupeWindowMs = 1_000L,
                )
                complete(key, Result.success(resolution.point))
            }
        }
    }

    private fun startPhotonLookup(
        context: Context,
        key: String,
        query: String,
        city: MarketCity?,
        reference: RoutePoint?,
    ) {
        photonExecutor.execute {
            val point = PhotonAddressGeocoder.resolve(query, city, reference)
            context.mainExecutor.execute {
                if (!isInFlight(key)) return@execute
                if (point != null) {
                    RouteGeocodePersistentCache.put(context, key, point)
                    CaptureEventLog.append(
                        context,
                        stage = "geocode_photon_fallback",
                        platform = "",
                        message = "Photon resolved an address while Android Geocoder was unavailable or slow",
                        dedupeWindowMs = 1_000L,
                    )
                    complete(key, Result.success(point))
                } else {
                    markBackendFailure(context, key, "photon", IllegalArgumentException("Photon address lookup failed"))
                }
            }
        }
    }

    private fun markBackendFailure(context: Context, key: String, backend: String, failure: Throwable) {
        val bothFailed = synchronized(lock) {
            if (key !in inFlight) return
            if (backend == "android") androidFailures[key] = failure else photonFailures[key] = failure
            androidFailures.containsKey(key) && photonFailures.containsKey(key)
        }
        if (bothFailed) {
            val chosen = synchronized(lock) { androidFailures[key] ?: photonFailures[key] ?: failure }
            context.mainExecutor.execute {
                if (isInFlight(key)) complete(key, Result.failure(chosen))
            }
        }
    }

    private fun isInFlight(key: String): Boolean = synchronized(lock) { key in inFlight }

    private fun chooseBest(
        results: List<android.location.Address>,
        reference: RoutePoint?,
        city: MarketCity?,
    ): android.location.Address? {
        if (results.isEmpty()) return null
        val country = city?.countryCode?.uppercase(Locale.ROOT)
        val countryFiltered = results.filter { candidate ->
            country == null || candidate.countryCode.isNullOrBlank() || candidate.countryCode.equals(country, ignoreCase = true)
        }.ifEmpty { results }
        if (reference == null) return countryFiltered.firstOrNull()
        return countryFiltered.minByOrNull { candidate ->
            distanceSquared(reference, RoutePoint(candidate.latitude, candidate.longitude))
        }
    }

    private fun distanceSquared(a: RoutePoint, b: RoutePoint): Double {
        val latScale = 111_320.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(a.latitude))
        val dy = (a.latitude - b.latitude) * latScale
        val dx = (a.longitude - b.longitude) * lonScale
        return dx * dx + dy * dy
    }

    private fun complete(key: String, result: Result<RoutePoint>) {
        val callbacks = synchronized(lock) {
            result.getOrNull()?.let { cache[key] = CachedPoint(it, System.currentTimeMillis() + CACHE_TTL_MS) }
            androidFailures.remove(key)
            photonFailures.remove(key)
            inFlight.remove(key).orEmpty().toList()
        }
        callbacks.forEach { it(result) }
    }

    private fun cacheKey(context: Context, value: String): String {
        val city = MarketCityResolver.cached(context)
        return listOf(city?.key.orEmpty(), value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " "))
            .joinToString("|")
    }

    private const val MAX_RESULTS = 5
    private const val HYBRID_TIMEOUT_MS = 6_200L
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
}
