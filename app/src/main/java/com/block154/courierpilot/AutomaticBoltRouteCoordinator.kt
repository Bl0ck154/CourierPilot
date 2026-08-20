package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class BoltRouteScope {
    PICKUP_ONLY,
    FULL,
}

internal data class AutomaticBoltRouteOutcome(
    val offerId: Long,
    val waypoints: List<ResolvedWaypoint>,
    val comparison: RouteComparison?,
    val scope: BoltRouteScope?,
    val note: String? = null,
    val failureReason: String? = null,
)

internal data class BoltSemanticMarkers(
    val currentLocation: BoltMarkerEvidence?,
    val pickup: BoltMarkerEvidence?,
    val dropoff: BoltMarkerEvidence?,
    val unknown: List<BoltMarkerEvidence>,
)

/**
 * Conservative Accessibility-only map-marker extraction. A node must live in a map-ish subtree or
 * explicitly identify itself as a marker before it can become geographic evidence. Generic screen
 * text outside the map is never treated as a marker.
 */
internal object BoltMarkerSemanticExtractor {
    fun extract(root: AccessibilityNodeInfo, parsed: ParsedOffer): BoltSemanticMarkers {
        val markers = mutableListOf<BoltMarkerEvidence>()
        walk(root, insideMap = false, parsed = parsed, out = markers, depth = 0)
        return BoltSemanticMarkers(
            currentLocation = markers.filter { it.kind == BoltMarkerKind.CURRENT_LOCATION }.maxByOrNull { it.confidence },
            pickup = markers.filter { it.kind == BoltMarkerKind.PICKUP }.maxByOrNull { it.confidence },
            dropoff = markers.filter { it.kind == BoltMarkerKind.DROPOFF }.maxByOrNull { it.confidence },
            unknown = markers.filter { it.kind == BoltMarkerKind.UNKNOWN },
        )
    }

    internal fun classifySemantic(
        semantic: String,
        insideMap: Boolean,
        parsed: ParsedOffer,
    ): Pair<BoltMarkerKind, Double>? {
        val normalized = normalize(semantic)
        if (normalized.isBlank()) return null
        val markerHint = insideMap || MARKER_HINTS.any(normalized::contains)
        if (!markerHint) return null

        if (CURRENT_HINTS.any(normalized::contains)) return BoltMarkerKind.CURRENT_LOCATION to 0.95
        if (DROPOFF_HINTS.any(normalized::contains)) return BoltMarkerKind.DROPOFF to 0.92
        if (PICKUP_HINTS.any(normalized::contains)) return BoltMarkerKind.PICKUP to 0.92

        val merchantTokens = buildList {
            addAll(parsed.merchantNames)
            parsed.restaurant?.let(::add)
        }.flatMap(::meaningfulTokens).distinct()
        if (merchantTokens.any { token -> token.length >= 4 && normalized.contains(token) }) {
            return BoltMarkerKind.PICKUP to 0.88
        }

        val pickupAddressTokens = parsed.pickupAddresses.flatMap(::meaningfulTokens).distinct()
        if (pickupAddressTokens.count { token -> token.length >= 4 && normalized.contains(token) } >= 2) {
            return BoltMarkerKind.PICKUP to 0.84
        }

        return if (MARKER_HINTS.any(normalized::contains)) BoltMarkerKind.UNKNOWN to 0.45 else null
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        insideMap: Boolean,
        parsed: ParsedOffer,
        out: MutableList<BoltMarkerEvidence>,
        depth: Int,
    ) {
        if (depth > 80) return
        val semanticParts = listOfNotNull(
            runCatching { node.text?.toString() }.getOrNull(),
            runCatching { node.contentDescription?.toString() }.getOrNull(),
            runCatching { node.viewIdResourceName }.getOrNull(),
            runCatching { node.className?.toString() }.getOrNull(),
        )
        val semantic = semanticParts.joinToString(" ")
        val normalized = normalize(semantic)
        val nowInsideMap = insideMap || MAP_CONTAINER_HINTS.any(normalized::contains)
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }

        classifySemantic(semantic, nowInsideMap, parsed)?.let { (kind, confidence) ->
            if (!rect.isEmpty && rect.width() >= 4 && rect.height() >= 4) {
                out += BoltMarkerEvidence(
                    kind = kind,
                    screenCenter = ScreenPoint(rect.exactCenterX().toDouble(), rect.exactCenterY().toDouble()),
                    semanticLabel = semanticParts.firstOrNull { it.isNotBlank() }?.take(240),
                    viewId = runCatching { node.viewIdResourceName }.getOrNull(),
                    confidence = confidence,
                )
            }
        }

        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            walk(child, nowInsideMap, parsed, out, depth + 1)
        }
    }

    private fun meaningfulTokens(value: String): List<String> = normalize(value)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in STOP_TOKENS }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ė', 'e')
        .replace('ę', 'e')
        .replace('ą', 'a')
        .replace('į', 'i')
        .replace('ų', 'u')
        .replace('ū', 'u')
        .replace('š', 's')
        .replace('ž', 'z')
        .trim()

    private val MAP_CONTAINER_HINTS = listOf("google map", "mapbox", "map view", "mapview", "map marker", "map_marker", "mapmarker")
    private val MARKER_HINTS = listOf("marker", "map pin", "pin marker", "map_marker")
    private val CURRENT_HINTS = listOf("current location", "my location", "your location", "you are here", "courier location", "dabartine vieta", "mano vieta")
    private val PICKUP_HINTS = listOf("pickup", "pick up", "restaurant marker", "merchant marker", "atsiim")
    private val DROPOFF_HINTS = listOf("dropoff", "drop off", "customer marker", "destination marker", "delivery marker", "client marker")
    private val STOP_TOKENS = setOf("vilnius", "vilniaus", "street", "str", "gatve", "map", "marker")
}

/**
 * Bolt route pipeline. Bolt reliably exposes textual pickup data more often than the customer
 * destination. Therefore current -> pickup is a useful real route even when full map recovery is
 * impossible. A customer point is appended only when the Accessibility map exposes enough marker
 * evidence to derive a two-anchor transform from current GPS + geocoded pickup.
 */
internal object AutomaticBoltRouteCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    fun start(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        onComplete: (AutomaticBoltRouteOutcome) -> Unit,
    ) {
        val app = context.applicationContext
        if (!platform.equals("Bolt", ignoreCase = true)) return
        if (!LiveAdvisorSettings.automaticBoltRouting(app)) return
        if (!inFlight.add(offerId)) return

        val config = runCatching { RouteEndpointSettings.load(app).validated() }.getOrElse {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "route endpoint disabled", onComplete)
            return
        }
        if (!RouteResearchLocation.hasPermission(app)) {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "location permission missing", onComplete)
            return
        }

        val pickupAddress = parsed.pickupAddresses.firstOrNull()?.takeIf { it.isNotBlank() }
        if (pickupAddress == null) {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "Bolt pickup address unavailable", onComplete)
            return
        }

        // Preserve marker geometry while the priced Bolt offer is still the active window. GPS and
        // pickup geocoding below are asynchronous, so reading rootInActiveWindow only afterwards can
        // race with Bolt UI changes and unnecessarily degrade a valid full route to pickup-only.
        val initialMapMarkers = captureMapMarkers(context, parsed)

        RouteResearchLocation.requestCurrent(app) { locationResult ->
            val fix = locationResult.getOrElse {
                completeFailure(app, offerId, platform, parsed, emptyList(), null, "current location unavailable", onComplete)
                return@requestCurrent
            }
            resolveStopWithTimeout(app, pickupAddress) { pickupResult ->
                val pickup = pickupResult.getOrElse {
                    completeFailure(app, offerId, platform, parsed, currentOnly(fix), fix.accuracyMeters, "Bolt pickup geocoding failed", onComplete)
                    return@resolveStopWithTimeout
                }

                val baseWaypoints = listOf(
                    ResolvedWaypoint(
                        kind = WaypointKind.CURRENT_LOCATION,
                        point = fix.point,
                        label = "Current location",
                        provenance = CoordinateProvenance.DEVICE_GPS,
                        confidence = locationConfidence(fix),
                    ),
                    ResolvedWaypoint(
                        kind = WaypointKind.PICKUP,
                        point = pickup,
                        label = parsed.restaurant ?: parsed.merchantNames.firstOrNull() ?: pickupAddress,
                        provenance = CoordinateProvenance.GEOCODED_ADDRESS,
                        confidence = 0.85,
                    ),
                )

                val mapMarkers = initialMapMarkers ?: captureMapMarkers(context, parsed)
                val recoveredDropoff = recoverDropoff(mapMarkers, fix.point, pickup)
                val waypoints = if (recoveredDropoff != null) baseWaypoints + recoveredDropoff else baseWaypoints
                val scope = if (recoveredDropoff != null) BoltRouteScope.FULL else BoltRouteScope.PICKUP_ONLY
                val note = if (scope == BoltRouteScope.FULL) {
                    "customer marker recovered from Bolt map semantics"
                } else {
                    "customer marker not recoverable yet; showing route to pickup only"
                }

                executor.execute {
                    val comparison = runCatching {
                        RouteComparisonEngine(ValhallaRouteProvider(config)).compare(waypoints.map { it.point })
                    }.getOrElse { failure ->
                        RouteComparison(Result.failure(failure), Result.failure(failure))
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
                            failureReason = if (anySuccess) note else reason,
                        )
                    }
                    inFlight.remove(offerId)
                    app.mainExecutor.execute {
                        onComplete(
                            AutomaticBoltRouteOutcome(
                                offerId = offerId,
                                waypoints = waypoints,
                                comparison = comparison.takeIf { anySuccess },
                                scope = scope.takeIf { anySuccess },
                                note = note.takeIf { anySuccess },
                                failureReason = reason,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun captureMapMarkers(
        context: Context,
        parsed: ParsedOffer,
    ): BoltSemanticMarkers? {
        val service = context as? AccessibilityService ?: return null
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != CourierSignals.BOLT_PACKAGE) return null
        return runCatching { BoltMarkerSemanticExtractor.extract(root, parsed) }.getOrNull()
    }

    private fun recoverDropoff(
        markers: BoltSemanticMarkers?,
        current: RoutePoint,
        pickup: RoutePoint,
    ): ResolvedWaypoint? {
        val evidence = markers ?: return null
        val currentMarker = evidence.currentLocation ?: return null
        val pickupMarker = evidence.pickup ?: return null
        val dropoffMarker = evidence.dropoff ?: return null

        val transform = runCatching {
            LocalMapTransform.fromTwoAnchors(
                KnownMapAnchor(currentMarker.screenCenter, current),
                KnownMapAnchor(pickupMarker.screenCenter, pickup),
            )
        }.getOrNull() ?: return null
        if (transform.metersPerPixel !in 0.05..250.0) return null

        val projected = runCatching { transform.screenToGeo(dropoffMarker.screenCenter) }.getOrNull() ?: return null
        val fromCurrent = distanceMeters(current, projected)
        val fromPickup = distanceMeters(pickup, projected)
        if (fromCurrent !in 20.0..40_000.0 || fromPickup !in 20.0..40_000.0) return null

        val confidence = minOf(currentMarker.confidence, pickupMarker.confidence, dropoffMarker.confidence, 0.78)
        return ResolvedWaypoint(
            kind = WaypointKind.DROPOFF,
            point = projected,
            label = "Bolt customer map marker",
            provenance = CoordinateProvenance.BOLT_MAP_RECOVERY,
            confidence = confidence,
        )
    }

    private fun currentOnly(fix: CurrentLocationFix) = listOf(
        ResolvedWaypoint(
            kind = WaypointKind.CURRENT_LOCATION,
            point = fix.point,
            label = "Current location",
            provenance = CoordinateProvenance.DEVICE_GPS,
            confidence = locationConfidence(fix),
        )
    )

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
        reason: String,
        onComplete: (AutomaticBoltRouteOutcome) -> Unit,
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
        context.mainExecutor.execute {
            onComplete(AutomaticBoltRouteOutcome(offerId, waypoints, null, null, failureReason = reason))
        }
    }

    private fun locationConfidence(fix: CurrentLocationFix): Double = when {
        fix.accuracyMeters == null -> 0.65
        fix.accuracyMeters <= 20f -> 1.0
        fix.accuracyMeters <= 50f -> 0.9
        fix.accuracyMeters <= 100f -> 0.75
        else -> 0.6
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
        val radius = 6_371_000.0
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return radius * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    private const val GEOCODER_TIMEOUT_MS = 7_000L
}
