package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
    val pickups: List<BoltMarkerEvidence>,
    val dropoffs: List<BoltMarkerEvidence>,
    val unknown: List<BoltMarkerEvidence>,
) {
    val pickup: BoltMarkerEvidence?
        get() = pickups.maxByOrNull { it.confidence }
    val dropoff: BoltMarkerEvidence?
        get() = dropoffs.maxByOrNull { it.confidence }
}

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
            pickups = markers.filter { it.kind == BoltMarkerKind.PICKUP }.sortedByDescending { it.confidence },
            dropoffs = markers.filter { it.kind == BoltMarkerKind.DROPOFF }.sortedByDescending { it.confidence },
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

internal object BoltPickupAddressPlanner {
    /** Active pickups go first: an add-on offer must not make us forget the task already in hand. */
    fun merge(active: List<String>, offered: List<String>): List<String> {
        val result = mutableListOf<String>()
        (active + offered).filter(String::isNotBlank).forEach { candidate ->
            if (result.none { sameAddress(it, candidate) }) result += candidate.trim()
        }
        return result
    }

    fun sameAddress(first: String, second: String): Boolean {
        val firstIdentity = DeliveryAddressNormalizer.identity(first)
        val secondIdentity = DeliveryAddressNormalizer.identity(second)
        if (firstIdentity != null && secondIdentity != null) return firstIdentity.key == secondIdentity.key
        return first.trim().equals(second.trim(), ignoreCase = true)
    }
}

internal data class BoltMapStopRecovery(
    val orderedPickups: List<ResolvedWaypoint>,
    val orderedDropoffs: List<ResolvedWaypoint>,
    val transform: LocalMapTransform,
    val matchedPickupMarkerIndices: Set<Int>,
)

/** Pure multi-marker recovery, separated from Android I/O so real stacked Bolt cases can be tested. */
internal object BoltMultiStopMapRecovery {
    private data class Candidate(
        val transform: LocalMapTransform,
        val anchorKnownIndex: Int,
        val anchorMarkerIndex: Int,
        val score: Double,
        val residualMeters: Double?,
    )

    fun recover(
        markers: BoltSemanticMarkers?,
        current: RoutePoint,
        knownPickups: List<ResolvedWaypoint>,
        expectedDropoffs: Int?,
    ): BoltMapStopRecovery? {
        val evidence = markers ?: return null
        val currentMarker = evidence.currentLocation ?: return null
        if (knownPickups.isEmpty() || evidence.pickups.isEmpty()) return null

        val selected = selectTransform(currentMarker, current, knownPickups, evidence.pickups) ?: return null
        val transform = selected.transform
        val matched = matchKnownPickups(
            transform = transform,
            knownPickups = knownPickups,
            pickupMarkers = evidence.pickups,
            anchorKnownIndex = selected.anchorKnownIndex,
            anchorMarkerIndex = selected.anchorMarkerIndex,
        )

        val inferredPickups = evidence.pickups.mapIndexedNotNull { index, marker ->
            if (index in matched.values) return@mapIndexedNotNull null
            val projected = projectValidated(transform, marker.screenCenter, current, knownPickups.map { it.point })
                ?: return@mapIndexedNotNull null
            if (knownPickups.any { distanceMeters(it.point, projected) < DUPLICATE_STOP_METERS }) {
                return@mapIndexedNotNull null
            }
            ResolvedWaypoint(
                kind = WaypointKind.PICKUP,
                point = projected,
                label = "Bolt pickup map marker",
                provenance = CoordinateProvenance.BOLT_MAP_RECOVERY,
                confidence = minOf(marker.confidence, 0.72),
            )
        }.dedupeByDistance()

        val allPickups = if (inferredPickups.isEmpty()) {
            knownPickups
        } else {
            nearestNeighborOrder(current, knownPickups + inferredPickups)
        }

        val projectedDropoffs = evidence.dropoffs.mapNotNull { marker ->
            val projected = projectValidated(transform, marker.screenCenter, current, allPickups.map { it.point })
                ?: return@mapNotNull null
            ResolvedWaypoint(
                kind = WaypointKind.DROPOFF,
                point = projected,
                label = "Bolt customer map marker",
                provenance = CoordinateProvenance.BOLT_MAP_RECOVERY,
                confidence = minOf(marker.confidence, 0.72),
            )
        }
        // Every surviving screen marker is separate evidence. For a stacked order, never collapse
        // two customer pins merely because their projected coordinates are <35 m apart: two flats
        // or neighbouring buildings can legitimately be almost on top of each other. If Bolt says
        // there is only one delivery, tiny projected duplicates are still removed.
        var dropoffs = if ((expectedDropoffs ?: 0) > 1) {
            projectedDropoffs
        } else {
            projectedDropoffs.dedupeByDistance()
        }

        expectedDropoffs?.takeIf { it > 0 }?.let { expected ->
            if (dropoffs.size > expected) dropoffs = dropoffs.take(expected)
        }
        val startForDropoffs = allPickups.lastOrNull()?.point ?: current
        dropoffs = nearestNeighborOrder(startForDropoffs, dropoffs)

        return BoltMapStopRecovery(
            orderedPickups = allPickups,
            orderedDropoffs = dropoffs,
            transform = transform,
            matchedPickupMarkerIndices = matched.values.toSet(),
        )
    }

    private fun selectTransform(
        currentMarker: BoltMarkerEvidence,
        current: RoutePoint,
        knownPickups: List<ResolvedWaypoint>,
        pickupMarkers: List<BoltMarkerEvidence>,
    ): Candidate? {
        val candidates = mutableListOf<Candidate>()
        knownPickups.forEachIndexed { knownIndex, known ->
            pickupMarkers.forEachIndexed { markerIndex, marker ->
                val transform = runCatching {
                    LocalMapTransform.fromTwoAnchors(
                        KnownMapAnchor(currentMarker.screenCenter, current),
                        KnownMapAnchor(marker.screenCenter, known.point),
                    )
                }.getOrNull() ?: return@forEachIndexed
                if (transform.metersPerPixel !in MIN_METERS_PER_PIXEL..MAX_METERS_PER_PIXEL) return@forEachIndexed
                if (abs(transform.clockwiseRotationDegrees) > MAX_ABS_ROTATION_DEGREES) return@forEachIndexed

                val residual = validationResidual(
                    transform,
                    knownPickups,
                    pickupMarkers,
                    knownIndex,
                    markerIndex,
                )
                if (residual != null && residual > MAX_KNOWN_PICKUP_RESIDUAL_METERS) return@forEachIndexed

                val scalePenalty = when {
                    transform.metersPerPixel < 0.10 -> 500.0
                    transform.metersPerPixel > 50.0 -> 500.0
                    else -> 0.0
                }
                val score = abs(transform.clockwiseRotationDegrees) * ROTATION_PENALTY_PER_DEGREE +
                    (residual ?: 0.0) + scalePenalty
                candidates += Candidate(transform, knownIndex, markerIndex, score, residual)
            }
        }
        return candidates.minByOrNull { it.score }
    }

    private fun validationResidual(
        transform: LocalMapTransform,
        knownPickups: List<ResolvedWaypoint>,
        pickupMarkers: List<BoltMarkerEvidence>,
        anchorKnownIndex: Int,
        anchorMarkerIndex: Int,
    ): Double? {
        if (knownPickups.size < 2 || pickupMarkers.size < 2) return null
        val remainingMarkers = pickupMarkers.indices.filter { it != anchorMarkerIndex }.toMutableSet()
        var total = 0.0
        var matchedCount = 0
        knownPickups.indices
            .filter { it != anchorKnownIndex }
            .forEach { knownIndex ->
                val best = remainingMarkers
                    .map { markerIndex ->
                        val projected = runCatching { transform.screenToGeo(pickupMarkers[markerIndex].screenCenter) }
                            .getOrNull() ?: return@map markerIndex to Double.POSITIVE_INFINITY
                        markerIndex to distanceMeters(knownPickups[knownIndex].point, projected)
                    }
                    .minByOrNull { it.second }
                    ?: return@forEach
                if (!best.second.isFinite()) return@forEach
                total += best.second
                matchedCount++
                remainingMarkers.remove(best.first)
            }
        return if (matchedCount > 0) total / matchedCount else null
    }

    private fun matchKnownPickups(
        transform: LocalMapTransform,
        knownPickups: List<ResolvedWaypoint>,
        pickupMarkers: List<BoltMarkerEvidence>,
        anchorKnownIndex: Int,
        anchorMarkerIndex: Int,
    ): Map<Int, Int> {
        val result = mutableMapOf(anchorKnownIndex to anchorMarkerIndex)
        val unusedMarkers = pickupMarkers.indices.filter { it != anchorMarkerIndex }.toMutableSet()
        knownPickups.indices
            .filter { it != anchorKnownIndex }
            .forEach { knownIndex ->
                val best = unusedMarkers
                    .map { markerIndex ->
                        val projected = runCatching { transform.screenToGeo(pickupMarkers[markerIndex].screenCenter) }
                            .getOrNull() ?: return@map markerIndex to Double.POSITIVE_INFINITY
                        markerIndex to distanceMeters(knownPickups[knownIndex].point, projected)
                    }
                    .minByOrNull { it.second }
                    ?: return@forEach
                if (best.second <= MAX_KNOWN_PICKUP_RESIDUAL_METERS) {
                    result[knownIndex] = best.first
                    unusedMarkers.remove(best.first)
                }
            }
        return result
    }

    private fun projectValidated(
        transform: LocalMapTransform,
        screen: ScreenPoint,
        current: RoutePoint,
        anchors: List<RoutePoint>,
    ): RoutePoint? {
        val projected = runCatching { transform.screenToGeo(screen) }.getOrNull() ?: return null
        if (distanceMeters(current, projected) !in MIN_PROJECTED_DISTANCE_METERS..MAX_PROJECTED_DISTANCE_METERS) return null
        if (anchors.isNotEmpty() && anchors.minOf { distanceMeters(it, projected) } > MAX_PROJECTED_DISTANCE_METERS) return null
        return projected
    }

    private fun List<ResolvedWaypoint>.dedupeByDistance(): List<ResolvedWaypoint> {
        val result = mutableListOf<ResolvedWaypoint>()
        for (candidate in this) {
            if (result.none { distanceMeters(it.point, candidate.point) < DUPLICATE_STOP_METERS }) result += candidate
        }
        return result
    }

    private fun nearestNeighborOrder(
        start: RoutePoint,
        points: List<ResolvedWaypoint>,
    ): List<ResolvedWaypoint> {
        if (points.size <= 1) return points
        val remaining = points.toMutableList()
        val ordered = mutableListOf<ResolvedWaypoint>()
        var cursor = start
        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { distanceMeters(cursor, it.point) } ?: break
            ordered += next
            remaining.remove(next)
            cursor = next.point
        }
        return ordered
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

    private const val MIN_METERS_PER_PIXEL = 0.05
    private const val MAX_METERS_PER_PIXEL = 100.0
    private const val MAX_ABS_ROTATION_DEGREES = 45.0
    private const val ROTATION_PENALTY_PER_DEGREE = 8.0
    private const val MAX_KNOWN_PICKUP_RESIDUAL_METERS = 400.0
    private const val MIN_PROJECTED_DISTANCE_METERS = 20.0
    private const val MAX_PROJECTED_DISTANCE_METERS = 40_000.0
    // Only collapse effectively identical projected points. Screen-marker identity is stronger
    // evidence than geographic proximity for stacked orders.
    private const val DUPLICATE_STOP_METERS = 3.0
}

/**
 * Bolt route pipeline. Textual pickup rows are geocoded first. Map pixels then add any hidden pickup
 * (for example an add-on offered while another order is already active) and all customer markers.
 * If the screenshot cannot recover the full expected drop-off set, routing fails closed to known
 * pickups rather than inventing a partial customer route.
 */
internal object AutomaticBoltRouteCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    fun start(
        context: Context,
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        supplementalPickupAddresses: List<String> = emptyList(),
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
        if (parsed.pickupAddresses.none { it.isNotBlank() } && supplementalPickupAddresses.none { it.isNotBlank() }) {
            completeFailure(app, offerId, platform, parsed, emptyList(), null, "Bolt pickup address unavailable", onComplete)
            return
        }

        val initialSemanticMarkers = captureMapMarkers(context, parsed)?.takeIf(::hasUsefulMarkers)

        RouteResearchLocation.requestCurrent(app) { locationResult ->
            val fix = locationResult.getOrElse {
                completeFailure(app, offerId, platform, parsed, emptyList(), null, "current location unavailable", onComplete)
                return@requestCurrent
            }
            resolvePickups(app, parsed, supplementalPickupAddresses) { knownPickups ->
                if (knownPickups.isEmpty()) {
                    completeFailure(
                        app,
                        offerId,
                        platform,
                        parsed,
                        currentOnly(fix),
                        fix.accuracyMeters,
                        "Bolt pickup geocoding failed",
                        onComplete,
                    )
                    return@resolvePickups
                }

                val lateSemanticMarkers = initialSemanticMarkers
                    ?: captureMapMarkers(context, parsed)?.takeIf(::hasUsefulMarkers)

                executor.execute {
                    val screenshotMarkers = if (lateSemanticMarkers == null) loadScreenshotMarkers(app, offerId) else null
                    val mapMarkers = lateSemanticMarkers ?: screenshotMarkers
                    val markerSource = when {
                        lateSemanticMarkers != null -> "Accessibility semantics"
                        screenshotMarkers != null -> "offer screenshot"
                        else -> null
                    }

                    val recovery = BoltMultiStopMapRecovery.recover(
                        markers = mapMarkers,
                        current = fix.point,
                        knownPickups = knownPickups,
                        expectedDropoffs = parsed.deliveryCount,
                    )
                    val expectedDropoffs = parsed.deliveryCount?.takeIf { it > 0 }
                    val fullDropoffSet = recovery?.orderedDropoffs?.takeIf { recovered ->
                        recovered.isNotEmpty() && (expectedDropoffs == null || recovered.size >= expectedDropoffs)
                    }

                    val currentWaypoint = currentOnly(fix).first()
                    val routedPickups = recovery?.orderedPickups ?: knownPickups
                    val waypoints = buildList {
                        add(currentWaypoint)
                        addAll(routedPickups)
                        if (fullDropoffSet != null) addAll(fullDropoffSet)
                    }
                    val scope = if (fullDropoffSet != null) BoltRouteScope.FULL else BoltRouteScope.PICKUP_ONLY
                    val pickupCount = routedPickups.size
                    val dropoffCount = fullDropoffSet?.size ?: 0
                    val note = if (scope == BoltRouteScope.FULL) {
                        "$pickupCount pickup${if (pickupCount == 1) "" else "s"} + $dropoffCount drop-off${if (dropoffCount == 1) "" else "s"} recovered from Bolt ${markerSource ?: "map"}"
                    } else {
                        val expectedLabel = expectedDropoffs?.let { " ($it expected)" }.orEmpty()
                        "showing $pickupCount pickup${if (pickupCount == 1) "" else "s"}; customer marker set incomplete$expectedLabel"
                    }

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

    private fun resolvePickups(
        context: Context,
        parsed: ParsedOffer,
        supplementalPickupAddresses: List<String>,
        callback: (List<ResolvedWaypoint>) -> Unit,
    ) {
        val addresses = BoltPickupAddressPlanner.merge(supplementalPickupAddresses, parsed.pickupAddresses)
        val result = mutableListOf<ResolvedWaypoint>()

        fun next(index: Int) {
            if (index >= addresses.size) {
                callback(result.toList())
                return
            }
            val address = addresses[index]
            resolveStopWithTimeout(context, address) { resolution ->
                resolution.getOrNull()?.let { point ->
                    val offeredIndex = parsed.pickupAddresses.indexOfFirst { offered ->
                        BoltPickupAddressPlanner.sameAddress(offered, address)
                    }
                    val label = if (offeredIndex >= 0) {
                        parsed.merchantNames.getOrNull(offeredIndex) ?: address
                    } else {
                        "Active Bolt pickup · $address"
                    }
                    result += ResolvedWaypoint(
                        kind = WaypointKind.PICKUP,
                        point = point,
                        label = label,
                        provenance = CoordinateProvenance.GEOCODED_ADDRESS,
                        confidence = if (offeredIndex >= 0) 0.85 else 0.82,
                    )
                }
                next(index + 1)
            }
        }
        next(0)
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

    private fun loadScreenshotMarkers(context: Context, offerId: Long): BoltSemanticMarkers? {
        val screenshotUri = runCatching { OfferDatabase.get(context).findById(offerId)?.screenshotUri }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(screenshotUri))?.use(BitmapFactory::decodeStream)
        }.getOrNull() ?: return null
        return try {
            BoltScreenshotMarkerExtractor.extract(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasUsefulMarkers(markers: BoltSemanticMarkers): Boolean =
        markers.currentLocation != null && markers.pickups.isNotEmpty() && markers.dropoffs.isNotEmpty()

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

    private const val GEOCODER_TIMEOUT_MS = 7_000L
}
