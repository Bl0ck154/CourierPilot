package com.block154.courierpilot

import android.content.Context
import java.util.Collections
import java.util.concurrent.Executors

internal data class AutomaticRouteOutcome(
    val offerId: Long,
    val waypoints: List<ResolvedWaypoint>,
    val comparison: RouteComparison?,
    val failureReason: String? = null,
)

/**
 * Experimental Wolt route pipeline. It starts only after the priced offer was persisted and therefore
 * cannot block or corrupt core capture. Every unresolved stop fails closed instead of being skipped.
 */
internal object AutomaticWoltRouteCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    fun start(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        onComplete: (AutomaticRouteOutcome) -> Unit,
    ) {
        val app = context.applicationContext
        if (!platform.equals("Wolt", ignoreCase = true)) return
        if (!LiveAdvisorSettings.automaticWoltRouting(app)) return
        if (!inFlight.add(offerId)) return

        val config = runCatching { RouteEndpointSettings.load(app).validated() }.getOrElse {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "route endpoint disabled", onComplete)
            return
        }
        if (!RouteResearchLocation.hasPermission(app)) {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "location permission missing", onComplete)
            return
        }

        val stopSpecs = buildStopSpecs(parsed)
        if (stopSpecs.isEmpty()) {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "no textual Wolt stops", onComplete)
            return
        }

        RouteResearchLocation.requestCurrent(app) { locationResult ->
            val fix = locationResult.getOrElse {
                completeFailure(app, offerId, platform, parsed, emptyList(), null, "current location unavailable", onComplete)
                return@requestCurrent
            }
            val resolved = mutableListOf(
                ResolvedWaypoint(
                    kind = WaypointKind.CURRENT_LOCATION,
                    point = fix.point,
                    label = "Current location",
                    provenance = CoordinateProvenance.DEVICE_GPS,
                    confidence = locationConfidence(fix),
                )
            )
            resolveNext(app, stopSpecs, 0, resolved) { resolution ->
                resolution.onFailure {
                    completeFailure(
                        app,
                        offerId,
                        platform,
                        parsed,
                        resolved.toList(),
                        fix.accuracyMeters,
                        it.message ?: "stop geocoding failed",
                        onComplete,
                    )
                }.onSuccess { waypoints ->
                    executor.execute {
                        val comparison = runCatching {
                            RouteComparisonEngine(ValhallaRouteProvider(config)).compare(waypoints.map { it.point })
                        }.getOrElse { failure ->
                            val failed = RouteComparison(
                                Result.failure(failure),
                                Result.failure(failure),
                            )
                            failed
                        }
                        val anySuccess = comparison.pedestrian.isSuccess || comparison.cycleway.isSuccess
                        val reason = if (anySuccess) null else comparison.pedestrian.exceptionOrNull()?.javaClass?.simpleName ?: "route failed"
                        runCatching {
                            RouteResearchDatabase.get(app).recordLiveAdvisorRun(
                                offerId = offerId,
                                platform = platform,
                                parsed = parsed,
                                waypoints = waypoints,
                                locationAccuracyMeters = fix.accuracyMeters,
                                comparison = comparison.takeIf { anySuccess },
                                failureReason = reason,
                            )
                        }
                        inFlight.remove(offerId)
                        app.mainExecutor.execute {
                            onComplete(AutomaticRouteOutcome(offerId, waypoints, comparison.takeIf { anySuccess }, reason))
                        }
                    }
                }
            }
        }
    }

    private data class StopSpec(
        val kind: WaypointKind,
        val address: String,
        val label: String?,
    )

    private fun buildStopSpecs(parsed: ParsedOffer): List<StopSpec> = buildList {
        parsed.pickupAddresses.forEachIndexed { index, address ->
            add(StopSpec(WaypointKind.PICKUP, address, parsed.merchantNames.getOrNull(index) ?: parsed.restaurant))
        }
        parsed.dropoffAddresses.forEachIndexed { index, address ->
            add(StopSpec(WaypointKind.DROPOFF, address, parsed.customerNames.getOrNull(index)))
        }
    }.filter { it.address.isNotBlank() }
        .distinctBy { "${it.kind}|${it.address.trim().lowercase()}" }

    private fun resolveNext(
        context: Context,
        specs: List<StopSpec>,
        index: Int,
        resolved: MutableList<ResolvedWaypoint>,
        complete: (Result<List<ResolvedWaypoint>>) -> Unit,
    ) {
        if (index >= specs.size) {
            complete(Result.success(resolved.toList()))
            return
        }
        val spec = specs[index]
        RouteResearchGeocoder.resolve(context, spec.address) { result ->
            result.onFailure {
                complete(Result.failure(IllegalStateException("Could not geocode ${spec.kind.name.lowercase()} stop", it)))
            }.onSuccess { point ->
                resolved += ResolvedWaypoint(
                    kind = spec.kind,
                    point = point,
                    label = spec.label ?: spec.address,
                    provenance = CoordinateProvenance.GEOCODED_ADDRESS,
                    confidence = 0.85,
                )
                resolveNext(context, specs, index + 1, resolved, complete)
            }
        }
    }

    private fun completeFailure(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        waypoints: List<ResolvedWaypoint>,
        accuracy: Float?,
        reason: String,
        onComplete: (AutomaticRouteOutcome) -> Unit,
    ) {
        runCatching {
            RouteResearchDatabase.get(context).recordLiveAdvisorRun(
                offerId = offerId,
                platform = platform,
                parsed = parsed,
                waypoints = waypoints,
                locationAccuracyMeters = accuracy,
                comparison = null,
                failureReason = reason,
            )
        }
        inFlight.remove(offerId)
        context.mainExecutor.execute { onComplete(AutomaticRouteOutcome(offerId, waypoints, null, reason)) }
    }

    private fun locationConfidence(fix: CurrentLocationFix): Double = when {
        fix.accuracyMeters == null -> 0.65
        fix.accuracyMeters <= 20f -> 1.0
        fix.accuracyMeters <= 50f -> 0.9
        fix.accuracyMeters <= 100f -> 0.75
        else -> 0.6
    }
}
