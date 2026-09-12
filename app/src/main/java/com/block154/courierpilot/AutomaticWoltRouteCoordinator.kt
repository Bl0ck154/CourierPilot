package com.block154.courierpilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal data class AutomaticRouteOutcome(
    val offerId: Long,
    val waypoints: List<ResolvedWaypoint>,
    val comparison: RouteComparison?,
    val failureReason: String? = null,
    val locationAccuracyMeters: Float? = null,
    val locationAgeMillis: Long? = null,
    val directChainMeters: Int? = null,
    val preparedBeforePrice: Boolean = false,
)

internal data class PreparedWoltRoute(
    val fingerprint: String,
    val waypoints: List<ResolvedWaypoint>,
    val comparison: RouteComparison?,
    val failureReason: String?,
    val locationAccuracyMeters: Float?,
    val locationAgeMillis: Long?,
    val directChainMeters: Int?,
    val platformDistanceMeters: Int? = null,
)

internal object WoltRoutePlausibility {
    /**
     * The straight-line chain through current -> pickup(s) -> drop-off(s) is a mathematical lower
     * bound for the real route. If it is already far longer than Wolt's displayed full distance,
     * at least one resolved coordinate is wrong. Keep a generous allowance for Wolt rounding and a
     * slightly different location fix, but never publish multi-kilometre geocoder jumps as €/km.
     */
    fun coordinateMismatchReason(platformMeters: Int?, directChainMeters: Int?): String? {
        val platform = platformMeters?.takeIf { it >= MIN_PLATFORM_DISTANCE_METERS } ?: return null
        val chain = directChainMeters?.takeIf { it > 0 } ?: return null
        val allowance = maxOf(ABSOLUTE_ALLOWANCE_METERS, (platform * RELATIVE_ALLOWANCE).roundToInt())
        val maximumPlausibleChain = platform + allowance
        return if (chain > maximumPlausibleChain) {
            "resolved stop chain ${chain}m exceeds Wolt ${platform}m sanity bound ${maximumPlausibleChain}m"
        } else null
    }

    private const val MIN_PLATFORM_DISTANCE_METERS = 500
    private const val ABSOLUTE_ALLOWANCE_METERS = 700
    private const val RELATIVE_ALLOWANCE = 0.35
}

/**
 * Wolt route pipeline. A complete address set can be routed before Wolt exposes the price; the
 * priced offer then consumes that exact prepared result instead of launching a second route job.
 */
internal object AutomaticWoltRouteCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CourierPilot-WoltRoute").apply { isDaemon = true }
    }
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())
    private val preparationLock = Any()
    private val preparations = mutableMapOf<String, PreparationState>()

    private class PreparationState(
        val fingerprint: String,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        val callbacks = mutableListOf<(PreparedWoltRoute) -> Unit>()
        var result: PreparedWoltRoute? = null
        var previewDelivered = false
    }

    /** Start the full location + geocode + Valhalla pipeline while Wolt is still loading price. */
    fun prepare(
        context: Context,
        key: String,
        parsed: ParsedOffer,
        onReady: (PreparedWoltRoute) -> Unit,
    ): Boolean {
        val app = context.applicationContext
        val fingerprint = routeFingerprint(parsed) ?: return false
        if (!LiveAdvisorSettings.automaticWoltRouting(app)) return false
        val config = runCatching { RouteEndpointSettings.load(app).validated() }.getOrNull() ?: return false
        if (!RouteResearchLocation.hasPermission(app)) return false

        var startNew: PreparationState? = null
        var ready: PreparedWoltRoute? = null
        synchronized(preparationLock) {
            prunePreparationsLocked()
            val existing = preparations[key]?.takeIf {
                System.currentTimeMillis() - it.startedAt <= PREPARED_REUSE_MS
            }
            if (existing == null) preparations.remove(key)
            if (existing != null && existing.fingerprint == fingerprint) {
                if (existing.result != null && !existing.previewDelivered) {
                    existing.previewDelivered = true
                    ready = existing.result
                } else if (existing.result == null && existing.callbacks.isEmpty()) {
                    existing.callbacks += onReady
                }
            } else {
                val state = PreparationState(fingerprint)
                state.callbacks += onReady
                preparations[key] = state
                startNew = state
            }
        }
        ready?.let {
            app.mainExecutor.execute { onReady(it) }
            return false
        }
        // Existing in-flight/prepared work is reuse, not a new route start. Returning false keeps
        // diagnostics truthful and avoids repeated "route_prepare_start" churn on every UI refresh.
        val state = startNew ?: return false
        computeRoute(app, parsed, config) { prepared ->
            val callbacks = synchronized(preparationLock) {
                val current = preparations[key]
                if (current !== state) return@computeRoute
                state.result = prepared
                if (state.callbacks.isNotEmpty()) state.previewDelivered = true
                state.callbacks.toList().also { state.callbacks.clear() }
            }
            callbacks.forEach { it(prepared) }
        }
        return true
    }

    fun start(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        preparedKey: String? = null,
        onComplete: (AutomaticRouteOutcome) -> Unit,
    ) {
        val app = context.applicationContext
        if (!platform.equals("Wolt", ignoreCase = true)) return
        if (!LiveAdvisorSettings.automaticWoltRouting(app)) return
        if (!inFlight.add(offerId)) return

        val config = runCatching { RouteEndpointSettings.load(app).validated() }.getOrElse {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, null, null, "route endpoint disabled", onComplete)
            return
        }
        if (!RouteResearchLocation.hasPermission(app)) {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, null, null, "location permission missing", onComplete)
            return
        }

        val fingerprint = routeFingerprint(parsed)
        if (fingerprint == null) {
            completeFailure(
                app,
                offerId,
                platform,
                parsed,
                emptyList(),
                null,
                null,
                null,
                "incomplete textual Wolt route; pickups=${parsed.pickupAddresses.size}; " +
                    "dropoffs=${parsed.dropoffAddresses.size}; deliveries=${parsed.deliveryCount ?: 0}",
                onComplete,
            )
            return
        }

        if (preparedKey != null && attachPreparedResult(
                app = app,
                key = preparedKey,
                fingerprint = fingerprint,
                onReady = { prepared ->
                    if (prepared.comparison != null) {
                        finalizePrepared(app, offerId, platform, parsed, prepared, onComplete)
                    } else {
                        computeFresh(app, offerId, platform, parsed, config, onComplete)
                    }
                },
            )
        ) {
            return
        }

        computeFresh(app, offerId, platform, parsed, config, onComplete)
    }

    private fun attachPreparedResult(
        app: Context,
        key: String,
        fingerprint: String,
        onReady: (PreparedWoltRoute) -> Unit,
    ): Boolean {
        var ready: PreparedWoltRoute? = null
        synchronized(preparationLock) {
            val state = preparations[key] ?: return false
            if (System.currentTimeMillis() - state.startedAt > PREPARED_REUSE_MS) {
                preparations.remove(key)
                return false
            }
            if (state.fingerprint != fingerprint) {
                preparations.remove(key)
                return false
            }
            ready = state.result
            if (ready == null) state.callbacks += { result ->
                synchronized(preparationLock) { preparations.remove(key) }
                onReady(result)
            } else {
                preparations.remove(key)
            }
        }
        ready?.let { result -> app.mainExecutor.execute { onReady(result) } }
        return true
    }

    private fun computeFresh(
        app: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        config: RouteEndpointConfig,
        onComplete: (AutomaticRouteOutcome) -> Unit,
    ) {
        computeRoute(app, parsed, config) { prepared ->
            if (prepared.comparison == null) {
                completeFailure(
                    app, offerId, platform, parsed, prepared.waypoints,
                    prepared.locationAccuracyMeters, prepared.locationAgeMillis, prepared.directChainMeters,
                    prepared.failureReason ?: "route failed", onComplete,
                )
            } else {
                finalizePrepared(app, offerId, platform, parsed, prepared, onComplete, preparedBeforePrice = false)
            }
        }
    }

    private fun finalizePrepared(
        app: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        prepared: PreparedWoltRoute,
        onComplete: (AutomaticRouteOutcome) -> Unit,
        preparedBeforePrice: Boolean = true,
    ) {
        val comparison = prepared.comparison ?: run {
            computeFresh(
                app, offerId, platform, parsed,
                runCatching { RouteEndpointSettings.load(app).validated() }.getOrElse {
                    completeFailure(app, offerId, platform, parsed, prepared.waypoints, prepared.locationAccuracyMeters,
                        prepared.locationAgeMillis, prepared.directChainMeters, "route endpoint disabled", onComplete)
                    return
                },
                onComplete,
            )
            return
        }
        recordRun(
            app, offerId, platform, parsed, prepared.waypoints,
            prepared.locationAccuracyMeters, comparison, null,
        )
        logRoutePlan(app, platform, prepared, preparedBeforePrice)
        inFlight.remove(offerId)
        app.mainExecutor.execute {
            onComplete(
                AutomaticRouteOutcome(
                    offerId = offerId,
                    waypoints = prepared.waypoints,
                    comparison = comparison,
                    failureReason = null,
                    locationAccuracyMeters = prepared.locationAccuracyMeters,
                    locationAgeMillis = prepared.locationAgeMillis,
                    directChainMeters = prepared.directChainMeters,
                    preparedBeforePrice = preparedBeforePrice,
                )
            )
        }
    }

    private fun computeRoute(
        app: Context,
        parsed: ParsedOffer,
        config: RouteEndpointConfig,
        callback: (PreparedWoltRoute) -> Unit,
    ) {
        val fingerprint = routeFingerprint(parsed) ?: run {
            app.mainExecutor.execute {
                callback(PreparedWoltRoute("", emptyList(), null, "incomplete textual Wolt route", null, null, null))
            }
            return
        }
        val stopSpecs = buildStopSpecs(parsed)

        // GPS acquisition and address geocoding are independent. Running them serially made the
        // live card pay both waits before the first Valhalla request (several seconds on ColorOS).
        // Start both immediately and join only when both inputs are ready.
        var locationResult: Result<CurrentLocationFix>? = null
        var stopResult: Result<List<ResolvedWaypoint>>? = null
        var joined = false

        fun continueWithInputs(fix: CurrentLocationFix, stops: List<ResolvedWaypoint>) {
            CaptureEventLog.append(
                app,
                stage = "route_location_fix",
                platform = "Wolt",
                message = "provider=${fix.provider.ifBlank { "unknown" }}; age_ms=${fix.ageMillis}; " +
                    "accuracy_m=${fix.accuracyMeters ?: -1f}",
                dedupeWindowMs = 500L,
            )
            val current = ResolvedWaypoint(
                kind = WaypointKind.CURRENT_LOCATION,
                point = fix.point,
                label = "Current location",
                provenance = CoordinateProvenance.DEVICE_GPS,
                confidence = locationConfidence(fix),
            )

            fun finishWithWaypoints(waypoints: List<ResolvedWaypoint>, strictRecovery: Boolean) {
                val chainMeters = RouteGeometryMetrics.directChainMeters(waypoints)
                // Wolt add-ons expose an *incremental* distance (for example "+2.3 km extra") while
                // the waypoint chain is the full remaining route. Comparing those values would
                // reject correct coordinates by construction, so the full-distance sanity bound is
                // only valid for ordinary offers.
                val sanityPlatformMeters = parsed.distanceMeters.takeUnless { parsed.isIncrementalOffer }
                val mismatch = WoltRoutePlausibility.coordinateMismatchReason(sanityPlatformMeters, chainMeters)
                if (mismatch != null && !strictRecovery) {
                    CaptureEventLog.append(
                        app,
                        stage = "route_geocode_sanity_retry",
                        platform = "Wolt",
                        message = "primary_direct_m=${chainMeters ?: -1}; platform_m=${parsed.distanceMeters ?: -1}; retrying all stops with strict Photon",
                        dedupeWindowMs = 500L,
                    )
                    resolveAllStrictPhoton(app, stopSpecs, listOf(current)) { strictResult ->
                        strictResult.onFailure { failure ->
                            callback(
                                PreparedWoltRoute(
                                    fingerprint, listOf(current), null,
                                    "geocoder sanity recovery failed: ${failure.message ?: failure.javaClass.simpleName}",
                                    fix.accuracyMeters, fix.ageMillis, chainMeters,
                                )
                            )
                        }.onSuccess { recovered -> finishWithWaypoints(recovered, strictRecovery = true) }
                    }
                    return
                }
                if (mismatch != null) {
                    CaptureEventLog.append(
                        app,
                        stage = "route_geocode_sanity_failed",
                        platform = "Wolt",
                        message = "strict_direct_m=${chainMeters ?: -1}; platform_m=${parsed.distanceMeters ?: -1}; route rejected",
                        dedupeWindowMs = 500L,
                    )
                    callback(
                        PreparedWoltRoute(
                            fingerprint, waypoints, null,
                            "resolved stop coordinates are inconsistent with Wolt distance",
                            fix.accuracyMeters, fix.ageMillis, chainMeters, parsed.distanceMeters,
                        )
                    )
                    return
                }
                if (strictRecovery) {
                    CaptureEventLog.append(
                        app,
                        stage = "route_geocode_sanity_recovered",
                        platform = "Wolt",
                        message = "strict_direct_m=${chainMeters ?: -1}; platform_m=${parsed.distanceMeters ?: -1}; recovered=true",
                        dedupeWindowMs = 500L,
                    )
                }

                executor.execute {
                    val comparison = runCatching {
                        RouteComparisonEngine(ValhallaRouteProvider(config)).compare(waypoints.map { it.point })
                    }.getOrElse { failure ->
                        RouteComparison(Result.failure(failure), Result.failure(failure))
                    }
                    val anySuccess = comparison.pedestrian.isSuccess || comparison.cycleway.isSuccess
                    val reason = if (anySuccess) null
                    else comparison.pedestrian.exceptionOrNull()?.javaClass?.simpleName ?: "route failed"
                    val prepared = PreparedWoltRoute(
                        fingerprint = fingerprint,
                        waypoints = waypoints,
                        comparison = comparison.takeIf { anySuccess },
                        failureReason = reason,
                        locationAccuracyMeters = fix.accuracyMeters,
                        locationAgeMillis = fix.ageMillis,
                        directChainMeters = chainMeters,
                        platformDistanceMeters = parsed.distanceMeters,
                    )
                    app.mainExecutor.execute { callback(prepared) }
                }
            }

            finishWithWaypoints(listOf(current) + stops, strictRecovery = false)
        }

        fun joinInputsIfReady() {
            if (joined) return
            val location = locationResult ?: return
            val stops = stopResult ?: return
            joined = true
            val fix = location.getOrElse { failure ->
                callback(PreparedWoltRoute(fingerprint, emptyList(), null, failure.message ?: "current location unavailable", null, null, null))
                return
            }
            val resolvedStops = stops.getOrElse { failure ->
                callback(
                    PreparedWoltRoute(
                        fingerprint,
                        emptyList(),
                        null,
                        failure.message ?: "stop geocoding failed",
                        fix.accuracyMeters,
                        fix.ageMillis,
                        null,
                    )
                )
                return
            }
            continueWithInputs(fix, resolvedStops)
        }

        RouteResearchLocation.requestForLiveOffer(app) { result ->
            locationResult = result
            joinInputsIfReady()
        }
        resolveAll(app, stopSpecs, emptyList()) { result ->
            stopResult = result
            joinInputsIfReady()
        }
    }

    internal fun routeFingerprint(parsed: ParsedOffer): String? {
        val specs = buildStopSpecs(parsed)
        val pickupCount = specs.count { it.kind == WaypointKind.PICKUP }
        val dropoffCount = specs.count { it.kind == WaypointKind.DROPOFF }
        val expectedDropoffs = (parsed.deliveryCount ?: 1).coerceAtLeast(1)
        if (pickupCount < 1 || dropoffCount < expectedDropoffs) return null
        val payload = specs.joinToString("|") { "${it.kind}:${normalizeAddress(it.address)}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private data class StopSpec(
        val kind: WaypointKind,
        val address: String,
        val label: String?,
    )

    private fun buildStopSpecs(parsed: ParsedOffer): List<StopSpec> {
        val ordered = parsed.orderedRouteStops.map { stop ->
            StopSpec(
                kind = if (stop.kind == ParsedRouteStopKind.PICKUP) WaypointKind.PICKUP else WaypointKind.DROPOFF,
                address = stop.address,
                label = stop.name,
            )
        }
        val fallback = buildList {
            parsed.pickupAddresses.forEachIndexed { index, address ->
                add(StopSpec(WaypointKind.PICKUP, address, parsed.merchantNames.getOrNull(index) ?: parsed.restaurant))
            }
            parsed.dropoffAddresses.forEachIndexed { index, address ->
                add(StopSpec(WaypointKind.DROPOFF, address, parsed.customerNames.getOrNull(index)))
            }
        }
        return ordered.ifEmpty { fallback }
            .filter { it.address.isNotBlank() }
            .distinctBy { "${it.kind}|${normalizeAddress(it.address)}" }
    }

    private fun normalizeAddress(value: String): String =
        DeliveryAddressNormalizer.key(value)
            ?: value
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")

    private fun resolveAll(
        context: Context,
        specs: List<StopSpec>,
        current: List<ResolvedWaypoint>,
        complete: (Result<List<ResolvedWaypoint>>) -> Unit,
    ) {
        if (specs.isEmpty()) {
            complete(Result.success(current))
            return
        }
        val results = arrayOfNulls<Result<RoutePoint>>(specs.size)
        val remaining = AtomicInteger(specs.size)
        specs.forEachIndexed { index, spec ->
            resolveStopWithTimeout(context, spec.address) { result ->
                results[index] = result
                if (remaining.decrementAndGet() != 0) return@resolveStopWithTimeout

                val failureIndex = results.indexOfFirst { it == null || it.isFailure }
                if (failureIndex >= 0) {
                    val failedSpec = specs[failureIndex]
                    complete(Result.failure(IllegalStateException(
                        "Could not geocode ${failedSpec.kind.name.lowercase()} stop",
                        results[failureIndex]?.exceptionOrNull(),
                    )))
                    return@resolveStopWithTimeout
                }

                val ordered = specs.indices.map { i ->
                    val item = specs[i]
                    ResolvedWaypoint(
                        kind = item.kind,
                        point = results[i]!!.getOrThrow(),
                        label = item.label ?: item.address,
                        provenance = CoordinateProvenance.GEOCODED_ADDRESS,
                        confidence = 0.85,
                    )
                }
                complete(Result.success(current + ordered))
            }
        }
    }

    private fun resolveAllStrictPhoton(
        context: Context,
        specs: List<StopSpec>,
        current: List<ResolvedWaypoint>,
        complete: (Result<List<ResolvedWaypoint>>) -> Unit,
    ) {
        if (specs.isEmpty()) {
            complete(Result.success(current))
            return
        }
        val results = arrayOfNulls<Result<RoutePoint>>(specs.size)
        val remaining = AtomicInteger(specs.size)
        val reference = current.firstOrNull()?.point
        specs.forEachIndexed { index, spec ->
            RouteResearchGeocoder.resolveStrictPhoton(context, spec.address, reference) { result ->
                results[index] = result
                if (remaining.decrementAndGet() != 0) return@resolveStrictPhoton

                val failureIndex = results.indexOfFirst { it == null || it.isFailure }
                if (failureIndex >= 0) {
                    val failedSpec = specs[failureIndex]
                    complete(Result.failure(IllegalStateException(
                        "Could not strictly geocode ${failedSpec.kind.name.lowercase()} stop",
                        results[failureIndex]?.exceptionOrNull(),
                    )))
                    return@resolveStrictPhoton
                }

                val ordered = specs.indices.map { i ->
                    val item = specs[i]
                    ResolvedWaypoint(
                        kind = item.kind,
                        point = results[i]!!.getOrThrow(),
                        label = item.label ?: item.address,
                        provenance = CoordinateProvenance.GEOCODED_ADDRESS,
                        confidence = 0.9,
                    )
                }
                complete(Result.success(current + ordered))
            }
        }
    }

    private fun resolveStopWithTimeout(
        context: Context,
        address: String,
        callback: (Result<RoutePoint>) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                callback(Result.failure(IllegalStateException("Geocoder timed out after ${GEOCODER_TIMEOUT_MS / 1000}s")))
            }
        }
        handler.postDelayed(timeout, GEOCODER_TIMEOUT_MS)
        RouteResearchGeocoder.resolve(context, address) { result ->
            if (!completed.compareAndSet(false, true)) return@resolve
            handler.removeCallbacks(timeout)
            callback(result)
        }
    }

    private fun completeFailure(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        waypoints: List<ResolvedWaypoint>,
        accuracy: Float?,
        locationAgeMillis: Long?,
        directChainMeters: Int?,
        reason: String,
        onComplete: (AutomaticRouteOutcome) -> Unit,
    ) {
        recordRun(context, offerId, platform, parsed, waypoints, accuracy, null, reason)
        inFlight.remove(offerId)
        context.mainExecutor.execute {
            onComplete(
                AutomaticRouteOutcome(
                    offerId, waypoints, null, reason, accuracy, locationAgeMillis, directChainMeters, false,
                )
            )
        }
    }

    private fun recordRun(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        waypoints: List<ResolvedWaypoint>,
        accuracy: Float?,
        comparison: RouteComparison?,
        failureReason: String?,
    ) {
        runCatching {
            RouteResearchDatabase.get(context).recordLiveAdvisorRun(
                offerId = offerId,
                platform = platform,
                parsed = parsed,
                waypoints = waypoints,
                locationAccuracyMeters = accuracy,
                comparison = comparison,
                failureReason = failureReason,
            )
        }
    }

    private fun logRoutePlan(context: Context, platform: String, prepared: PreparedWoltRoute, early: Boolean) {
        val walking = prepared.comparison?.pedestrian?.getOrNull()?.distanceMeters
        val cycling = prepared.comparison?.cycleway?.getOrNull()?.distanceMeters
        val average = OfferDecisionEngine.averageValhallaDistanceMeters(
            prepared.comparison?.pedestrian?.getOrNull(), prepared.comparison?.cycleway?.getOrNull(),
        )
        CaptureEventLog.append(
            context,
            stage = "route_plan_ready",
            platform = platform,
            message = "points=${prepared.waypoints.size}; pickups=${prepared.waypoints.count { it.kind == WaypointKind.PICKUP }}; " +
                "dropoffs=${prepared.waypoints.count { it.kind == WaypointKind.DROPOFF }}; walk_m=${walking ?: -1}; " +
                "cycle_m=${cycling ?: -1}; avg_m=${average ?: -1}; platform_m=${prepared.platformDistanceMeters ?: -1}; " +
                "direct_chain_m=${prepared.directChainMeters ?: -1}; direct_legs_m=${RouteGeometryMetrics.directLegSummary(prepared.waypoints)}; " +
                "gps_age_ms=${prepared.locationAgeMillis ?: -1}; gps_accuracy_m=${prepared.locationAccuracyMeters ?: -1f}; prepared=$early",
            dedupeWindowMs = 500L,
        )
    }

    private fun prunePreparationsLocked(now: Long = System.currentTimeMillis()) {
        preparations.entries.removeAll { now - it.value.startedAt > PREPARED_TTL_MS }
        while (preparations.size >= MAX_PREPARATIONS) {
            val oldest = preparations.minByOrNull { it.value.startedAt }?.key ?: break
            preparations.remove(oldest)
        }
    }

    private fun locationConfidence(fix: CurrentLocationFix): Double = when {
        fix.accuracyMeters == null -> 0.65
        fix.accuracyMeters <= 20f -> 1.0
        fix.accuracyMeters <= 50f -> 0.9
        fix.accuracyMeters <= 100f -> 0.75
        else -> 0.6
    }

    private const val GEOCODER_TIMEOUT_MS = 7_000L
    private const val PREPARED_REUSE_MS = 30_000L
    private const val PREPARED_TTL_MS = 3L * 60L * 1000L
    private const val MAX_PREPARATIONS = 6
}
