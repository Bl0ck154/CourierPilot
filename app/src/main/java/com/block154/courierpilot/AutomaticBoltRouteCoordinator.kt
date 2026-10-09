package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    val etaEstimateMeters: Int? = null,
    val recoveryConfidence: Double? = null,
    val diagnostics: BoltRecoveryDiagnostics? = null,
    val etaToCustomerMinutes: Int? = null,
)

internal data class BoltSemanticMarkers(
    val currentLocation: BoltMarkerEvidence?,
    val pickups: List<BoltMarkerEvidence>,
    val dropoffs: List<BoltMarkerEvidence>,
    val unknown: List<BoltMarkerEvidence>,
    val bitmapWidthPx: Int? = null,
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

    /**
     * Geocoded route anchors must come from the offer currently visible on screen. The durable
     * active-pickup cache is only a last-resort anchor when Bolt exposes no pickup address at all.
     * Extra active/add-on pickups that are really present are recovered from the current map pins.
     * This prevents stale cached restaurants from becoming real sequential route stops.
     */
    fun routeAnchors(active: List<String>, offered: List<String>): List<String> {
        val current = merge(emptyList(), offered)
        if (current.isNotEmpty()) return current
        return active.asReversed()
            .firstOrNull(String::isNotBlank)
            ?.trim()
            ?.let(::listOf)
            .orEmpty()
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

/**
 * Conservative north-up map recovery.
 *
 * Translation always comes from a pickup pin matched to its geocoded text address. The blue
 * current-location dot is only a *scale* hint: Mapbox POI icons (shops, stations) share its colour
 * and real 0.16.0 offers anchored the whole map on a POI next to the customer, shrinking a ~3 km
 * delivery to 0.55 km. The dot's scale is therefore used only when it is strong and agrees with
 * Bolt's own customer-leg ETA; otherwise the ETA alone sets the scale, or recovery fails closed.
 */
internal object BoltMultiStopMapRecovery {
    private data class Candidate(
        val transform: LocalMapTransform,
        val fit: NorthUpAnchorFit?,
        val anchorKnownIndex: Int,
        val anchorMarkerIndex: Int,
        val weak: Boolean,
        val etaConflict: Boolean,
        val weakReason: String?,
        val score: Double,
    )

    fun recover(
        markers: BoltSemanticMarkers?,
        current: RoutePoint,
        knownPickups: List<ResolvedWaypoint>,
        expectedDropoffs: Int?,
        bitmapWidthPx: Int? = null,
        etaToCustomerMeters: Int? = null,
    ): BoltMapStopRecovery? = recoverDetailed(
        markers, current, knownPickups, expectedDropoffs, bitmapWidthPx, etaToCustomerMeters
    ).recovery

    fun recoverDetailed(
        markers: BoltSemanticMarkers?,
        current: RoutePoint,
        knownPickups: List<ResolvedWaypoint>,
        expectedDropoffs: Int?,
        bitmapWidthPx: Int? = null,
        etaToCustomerMeters: Int? = null,
    ): BoltRecoveryResult {
        val emptyDiagnostics = BoltRecoveryDiagnostics(
            pickupMarkerCount = markers?.pickups?.size ?: 0,
            dropoffMarkerCount = markers?.dropoffs?.size ?: 0,
        )
        if (markers == null || knownPickups.isEmpty() || markers.pickups.isEmpty()) {
            return BoltRecoveryResult(null, emptyDiagnostics.copy(weakAnchorReason = "pickup_anchor_missing"), null)
        }
        val currentMarker = markers.currentLocation
        val etaPrior = etaToCustomerMeters?.takeIf { it > 0 }
        if (currentMarker == null && etaPrior == null) {
            return BoltRecoveryResult(null, emptyDiagnostics.copy(weakAnchorReason = "current_marker_missing"), null)
        }

        val candidates = mutableListOf<Candidate>()
        var bestRejectedFit: NorthUpAnchorFit? = null
        var rejectedRotation = false
        var rejectedRotationDegrees: Double? = null
        var rejectedWeak = false
        val minimumBaselinePx = maxOf(MIN_STRONG_BASELINE_PX, (bitmapWidthPx ?: 0) * STRONG_WIDTH_FRACTION)
        knownPickups.forEachIndexed { knownIndex, known ->
            markers.pickups.forEachIndexed { markerIndex, marker ->
                // Bolt's to-customer ETA covers restaurant -> customers, so measure that screen chain
                // from this pickup pin; the courier dot is not part of it (and may be a POI).
                val pickupChainPx = screenRouteLength(
                    marker.screenCenter,
                    markers.pickups.filterIndexed { index, _ -> index != markerIndex },
                    markers.dropoffs,
                )
                val etaScale = etaPrior
                    ?.takeIf { pickupChainPx > 1.0 }
                    ?.let { prior -> prior / (pickupChainPx * DETOUR_FACTOR) }
                    ?.takeIf { it.isFinite() && it in MIN_METERS_PER_PIXEL..MAX_METERS_PER_PIXEL }

                var fit: NorthUpAnchorFit? = null
                var dotWeakReason: String? = null
                if (currentMarker == null) {
                    dotWeakReason = "current_marker_missing"
                } else {
                    // Measure pairing rotation before solving the constrained scale. At ~90 degrees
                    // the least-squares dot product approaches zero and may fail positivity first.
                    val rawAngle = runCatching {
                        LocalMapTransform.fromTwoAnchors(
                            KnownMapAnchor(currentMarker.screenCenter, current),
                            KnownMapAnchor(marker.screenCenter, known.point),
                        ).clockwiseRotationDegrees
                    }.getOrNull()
                    fit = runCatching {
                        LocalMapTransform.fitNorthUp(
                            KnownMapAnchor(currentMarker.screenCenter, current),
                            KnownMapAnchor(marker.screenCenter, known.point),
                        )
                    }.getOrNull()
                    fit?.let { candidate ->
                        if (bestRejectedFit == null || candidate.baselinePx > bestRejectedFit!!.baselinePx) {
                            bestRejectedFit = candidate
                        }
                    }
                    val measuredRotation = rawAngle ?: fit?.measuredRotationDegrees
                    dotWeakReason = when {
                        measuredRotation != null && abs(measuredRotation) > MAX_ABS_ROTATION_DEGREES -> {
                            rejectedRotation = true
                            rejectedRotationDegrees = measuredRotation
                            "anchor_rotation_mismatch"
                        }
                        fit == null -> "anchor_transform_unreliable"
                        fit.baselinePx < minimumBaselinePx || fit.baselineMeters < MIN_STRONG_BASELINE_METERS ->
                            "anchor_baseline_too_short"
                        fit.transform.metersPerPixel !in MIN_METERS_PER_PIXEL..MAX_METERS_PER_PIXEL ->
                            "anchor_transform_unreliable"
                        else -> null
                    }
                }
                val dotScale = fit?.transform?.metersPerPixel?.takeIf { dotWeakReason == null }
                val dotAgreesWithEta = dotScale != null &&
                    (etaScale == null || abs(dotScale - etaScale) / etaScale <= ETA_CONFLICT_FRACTION)
                val etaConflict = dotScale != null && !dotAgreesWithEta
                val scale = when {
                    dotAgreesWithEta -> dotScale!!
                    etaScale != null -> etaScale
                    else -> {
                        if (dotWeakReason == "anchor_baseline_too_short") rejectedWeak = true
                        return@forEachIndexed
                    }
                }
                val weak = !dotAgreesWithEta
                val transform = LocalMapTransform(KnownMapAnchor(marker.screenCenter, known.point), scale)
                val residual = validationResidual(
                    transform, knownPickups, markers.pickups, knownIndex, markerIndex
                )
                if (residual != null && residual > MAX_KNOWN_PICKUP_RESIDUAL_METERS) {
                    return@forEachIndexed
                }
                val rotationPenalty = if (weak) 0.0 else abs(fit!!.measuredRotationDegrees) * ROTATION_PENALTY_PER_DEGREE
                val score = (if (weak) 1_000.0 else 0.0) + rotationPenalty + (residual ?: 0.0) +
                    (if (etaConflict) 100.0 else 0.0)
                val weakReason = when {
                    !weak -> null
                    etaConflict -> "anchor_eta_conflict_eta_prior"
                    else -> "${dotWeakReason ?: "anchor_transform_unreliable"}_eta_prior"
                }
                candidates += Candidate(transform, fit, knownIndex, markerIndex, weak, etaConflict, weakReason, score)
            }
        }
        val chosen = candidates.minByOrNull { it.score }
        if (chosen == null) {
            val why = when {
                rejectedWeak -> "anchor_baseline_too_short"
                rejectedRotation -> "anchor_rotation_mismatch"
                currentMarker == null -> "current_marker_missing"
                else -> "anchor_transform_unreliable"
            }
            return BoltRecoveryResult(null, emptyDiagnostics.copy(
                scaleMetersPerPixel = bestRejectedFit?.transform?.metersPerPixel,
                measuredRotationDegrees = bestRejectedFit?.measuredRotationDegrees ?: rejectedRotationDegrees,
                anchorBaselinePx = bestRejectedFit?.baselinePx,
                anchorBaselineMeters = bestRejectedFit?.baselineMeters,
                weakAnchorReason = why,
            ), null)
        }
        val transform = chosen.transform
        val matched = matchKnownPickups(
            transform, knownPickups, markers.pickups, chosen.anchorKnownIndex, chosen.anchorMarkerIndex
        )
        val inferredPickups = markers.pickups.mapIndexedNotNull { index, marker ->
            if (index in matched.values) return@mapIndexedNotNull null
            val projected = projectValidated(transform, marker.screenCenter, current, knownPickups.map { it.point })
                ?: return@mapIndexedNotNull null
            if (knownPickups.any { distanceMeters(it.point, projected) < DUPLICATE_STOP_METERS }) {
                return@mapIndexedNotNull null
            }
            ResolvedWaypoint(
                WaypointKind.PICKUP, projected, "Bolt pickup map marker",
                CoordinateProvenance.BOLT_MAP_RECOVERY, minOf(marker.confidence, if (chosen.weak) 0.45 else 0.72)
            )
        }.dedupeByDistance()
        val allPickups = if (inferredPickups.isEmpty()) knownPickups
            else nearestNeighborOrder(current, knownPickups + inferredPickups)
        val projectedDropoffs = markers.dropoffs.mapNotNull { marker ->
            val projected = projectValidated(transform, marker.screenCenter, current, allPickups.map { it.point })
                ?: return@mapNotNull null
            ResolvedWaypoint(
                WaypointKind.DROPOFF, projected, "Bolt customer map marker",
                CoordinateProvenance.BOLT_MAP_RECOVERY, minOf(marker.confidence, if (chosen.weak) 0.45 else 0.72)
            )
        }
        var dropoffs = if ((expectedDropoffs ?: 0) > 1) {
            projectedDropoffs
        } else {
            projectedDropoffs.dedupeByDistance()
        }
        expectedDropoffs?.takeIf { it > 0 }?.let { expected ->
            if (dropoffs.size > expected) dropoffs = dropoffs.take(expected)
        }
        dropoffs = nearestNeighborOrder(allPickups.lastOrNull()?.point ?: current, dropoffs)
        val confidence = when {
            chosen.etaConflict -> 0.4
            chosen.weak -> 0.45
            else -> 0.72
        }
        val diagnostics = emptyDiagnostics.copy(
            scaleMetersPerPixel = transform.metersPerPixel,
            measuredRotationDegrees = chosen.fit?.measuredRotationDegrees,
            anchorBaselinePx = chosen.fit?.baselinePx,
            anchorBaselineMeters = chosen.fit?.baselineMeters,
            projectedPickups = inferredPickups.map { it.point },
            projectedDropoffs = dropoffs.map { it.point },
            weakAnchorReason = chosen.weakReason,
            etaConflict = chosen.etaConflict,
        )
        return BoltRecoveryResult(
            BoltMapStopRecovery(allPickups, dropoffs, transform, matched.values.toSet()),
            diagnostics,
            confidence,
        )
    }

    /** The ETA prior applies to the full visible pin chain including extra pickups and customers. */
    private fun screenRouteLength(
        current: ScreenPoint,
        pickups: List<BoltMarkerEvidence>,
        dropoffs: List<BoltMarkerEvidence>,
    ): Double {
        var cursor = current
        var length = 0.0
        for (group in listOf(pickups, dropoffs)) {
            val left = group.map { it.screenCenter }.toMutableList()
            while (left.isNotEmpty()) {
                val next = left.minByOrNull { kotlin.math.hypot(it.x - cursor.x, it.y - cursor.y) } ?: break
                length += kotlin.math.hypot(next.x - cursor.x, next.y - cursor.y)
                cursor = next
                left.remove(next)
            }
        }
        return length
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
        knownPickups.indices.filter { it != anchorKnownIndex }.forEach { knownIndex ->
            val best = remainingMarkers.map { index ->
                val projected = runCatching { transform.screenToGeo(pickupMarkers[index].screenCenter) }
                    .getOrNull() ?: return@map index to Double.POSITIVE_INFINITY
                index to distanceMeters(knownPickups[knownIndex].point, projected)
            }.minByOrNull { it.second } ?: return@forEach
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
        val unused = pickupMarkers.indices.filter { it != anchorMarkerIndex }.toMutableSet()
        knownPickups.indices.filter { it != anchorKnownIndex }.forEach { knownIndex ->
            val best = unused.map { index ->
                val projected = runCatching { transform.screenToGeo(pickupMarkers[index].screenCenter) }
                    .getOrNull() ?: return@map index to Double.POSITIVE_INFINITY
                index to distanceMeters(knownPickups[knownIndex].point, projected)
            }.minByOrNull { it.second } ?: return@forEach
            if (best.second <= MAX_KNOWN_PICKUP_RESIDUAL_METERS) {
                result[knownIndex] = best.first
                unused.remove(best.first)
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

    private fun nearestNeighborOrder(start: RoutePoint, points: List<ResolvedWaypoint>): List<ResolvedWaypoint> {
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
    private const val MAX_ABS_ROTATION_DEGREES = 20.0
    private const val ROTATION_PENALTY_PER_DEGREE = 8.0
    private const val MIN_STRONG_BASELINE_PX = 120.0
    private const val STRONG_WIDTH_FRACTION = 0.11
    private const val MIN_STRONG_BASELINE_METERS = 250.0
    private const val MAX_KNOWN_PICKUP_RESIDUAL_METERS = 400.0
    private const val MIN_PROJECTED_DISTANCE_METERS = 20.0
    private const val MAX_PROJECTED_DISTANCE_METERS = 40_000.0
    private const val DETOUR_FACTOR = 1.3
    private const val ETA_CONFLICT_FRACTION = 0.45
    private const val DUPLICATE_STOP_METERS = 3.0
}

/**
 * Bolt route pipeline. Textual pickup rows are geocoded first. Map pixels then add any hidden pickup
 * (for example an add-on offered while another order is already active) and all customer markers.
 * If the screenshot cannot recover the full expected drop-off set, routing fails closed to known
 * pickups rather than inventing a partial customer route.
 */
internal object BoltPickupResolutionOrder {
    /** Parallel callbacks must never reorder pickup stops in the paid route. */
    fun <T : Any> successfulInRequestOrder(completed: List<T?>): List<T> = completed.filterNotNull()
}

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
        val startedAt = SystemClock.elapsedRealtime()
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

        val mapBottomScreenPx = captureMapBottomPx(context, parsed)
        val eta = etaFor(app, offerId, parsed)
        val etaModel = BoltEtaDistanceModel(app)
        val toCustomerEtaMeters = (eta.toCustomerMin ?: eta.totalMin)?.let(etaModel::estimateMeters)
        val etaEstimateMeters = (eta.toCustomerMin ?: eta.totalMin ?: eta.toPickupMin ?: parsed.estimatedMinutesMin)
            ?.let(etaModel::estimateMeters)
        val initialSemanticMarkers = captureMapMarkers(context, parsed)?.takeIf(::hasUsefulMarkers)

        // Bolt keeps location hot while a courier is online. Reuse a very fresh accurate fix instead
        // of waiting up to eight seconds for a new GPS callback on every incoming offer.
        RouteResearchLocation.requestForLiveOffer(app) { locationResult ->
            val gpsMs = SystemClock.elapsedRealtime() - startedAt
            val fix = locationResult.getOrElse {
                completeFailure(app, offerId, platform, parsed, emptyList(), null, "current location unavailable", onComplete)
                return@requestForLiveOffer
            }
            resolvePickups(app, parsed, supplementalPickupAddresses) { knownPickups, geocodeTimes ->
                val geocodeDoneAt = SystemClock.elapsedRealtime()
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
                    val markersStarted = SystemClock.elapsedRealtime()
                    val screenshotMarkers = if (lateSemanticMarkers == null) loadScreenshotMarkers(app, offerId, mapBottomScreenPx) else null
                    val mapMarkers = lateSemanticMarkers ?: screenshotMarkers
                    val markerSource = when {
                        lateSemanticMarkers != null -> "Accessibility semantics"
                        screenshotMarkers != null -> "offer screenshot"
                        else -> null
                    }

                    val recovered = BoltMultiStopMapRecovery.recoverDetailed(
                        markers = mapMarkers,
                        current = fix.point,
                        knownPickups = knownPickups,
                        expectedDropoffs = parsed.deliveryCount,
                        bitmapWidthPx = mapMarkers?.bitmapWidthPx ?: app.resources.displayMetrics.widthPixels,
                        etaToCustomerMeters = toCustomerEtaMeters,
                    )
                    val markersMs = SystemClock.elapsedRealtime() - markersStarted
                    val recovery = recovered.recovery
                    val expectedDropoffs = parsed.deliveryCount?.takeIf { it > 0 }
                    val fullDropoffSet = recovery?.orderedDropoffs?.takeIf { recovered ->
                        recovered.isNotEmpty() && (expectedDropoffs == null || recovered.size >= expectedDropoffs)
                    }

                    val currentWaypoint = currentOnly(fix).first()
                    val canHaveExtraPickup = (parsed.deliveryCount ?: 1) > 1 || supplementalPickupAddresses.isNotEmpty()
                    // A normal single Bolt offer already has a trusted textual pickup address. Do not
                    // let map zoom/POI marker noise invent a second pickup and inflate the distance.
                    val routedPickups = if (canHaveExtraPickup) {
                        recovery?.orderedPickups ?: knownPickups
                    } else {
                        knownPickups
                    }
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
                    CaptureEventLog.append(
                        app,
                        stage = "bolt_route_plan",
                        platform = platform,
                        message = "scope=$scope; waypoints=${waypoints.size}; offered_pickups=${parsed.pickupAddresses.count(String::isNotBlank)}; cached_pickups=${supplementalPickupAddresses.count(String::isNotBlank)}; map_pickups=${mapMarkers?.pickups?.size ?: 0}; routed_pickups=$pickupCount; dropoffs=$dropoffCount",
                        dedupeWindowMs = 2_000L,
                    )

                    val valhallaStarted = SystemClock.elapsedRealtime()
                    val comparison = runCatching {
                        RouteComparisonEngine(ValhallaRouteProvider(config)).compare(waypoints.map { it.point })
                    }.getOrElse { failure ->
                        RouteComparison(Result.failure(failure), Result.failure(failure))
                    }
                    val valhallaMs = SystemClock.elapsedRealtime() - valhallaStarted
                    CaptureEventLog.append(
                        app,
                        stage = "bolt_route_timing",
                        platform = "Bolt",
                        message = "gps_ms=$gpsMs; geocode_ms=${geocodeTimes.joinToString(",")}; " +
                            "markers_ms=$markersMs; valhalla_ms=$valhallaMs; " +
                            "total_ms=${SystemClock.elapsedRealtime() - startedAt}",
                        dedupeWindowMs = 500L,
                    )
                    val anySuccess = comparison.pedestrian.isSuccess || comparison.cycleway.isSuccess
                    val reason = if (anySuccess) null else comparison.pedestrian.exceptionOrNull()?.javaClass?.simpleName ?: "route failed"
                    val routeMeters = (comparison.cycleway.getOrNull() ?: comparison.pedestrian.getOrNull())?.distanceMeters
                    val etaTotalMinutes = BoltRouteEtaPlausibility.totalMinutes(eta)
                    val etaImplausible = scope == BoltRouteScope.FULL && anySuccess &&
                        !BoltRouteEtaPlausibility.isPlausible(routeMeters, etaTotalMinutes, etaModel)
                    if (etaImplausible) {
                        CaptureEventLog.append(
                            app,
                            stage = "bolt_route_eta_implausible",
                            platform = "Bolt",
                            message = "route_m=${routeMeters ?: -1}; eta_total_min=${etaTotalMinutes ?: -1}; " +
                                "expected_m=${etaTotalMinutes?.let(etaModel::estimateMeters) ?: -1}; " +
                                "anchor=${recovered.diagnostics.weakAnchorReason ?: "strong"}; showing ETA estimate instead",
                            dedupeWindowMs = 1_000L,
                        )
                    }
                    // An implausible full route is shown as the ETA estimate, never as a verdict.
                    val reportedScope = if (etaImplausible) BoltRouteScope.PICKUP_ONLY else scope
                    runCatching {
                        RouteResearchDatabase.get(app).recordLiveAdvisorRun(
                            offerId = offerId,
                            platform = platform,
                            parsed = parsed,
                            waypoints = waypoints,
                            locationAccuracyMeters = fix.accuracyMeters,
                            // History restore replays successful runs; never persist a rejected route as one.
                            comparison = comparison.takeIf { anySuccess && !etaImplausible },
                            failureReason = when {
                                etaImplausible -> "$note; route rejected: incompatible with Bolt ETA"
                                anySuccess -> note
                                else -> reason
                            },
                        )
                    }
                    inFlight.remove(offerId)
                    app.mainExecutor.execute {
                        onComplete(
                            AutomaticBoltRouteOutcome(
                                offerId = offerId,
                                waypoints = waypoints,
                                comparison = comparison.takeIf { anySuccess },
                                scope = reportedScope.takeIf { anySuccess },
                                note = note.takeIf { anySuccess },
                                failureReason = reason,
                                etaEstimateMeters = etaEstimateMeters,
                                recoveryConfidence = recovered.confidence,
                                diagnostics = recovered.diagnostics,
                                etaToCustomerMinutes = eta.toCustomerMin ?: eta.totalMin,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Parallel geocoding with stable source order, seven-second per-address limit and an eight-
     * second global deadline. Each callback can finish only once, even on late geocoder responses.
     */
    private fun resolvePickups(
        context: Context,
        parsed: ParsedOffer,
        supplementalPickupAddresses: List<String>,
        callback: (List<ResolvedWaypoint>, List<Long>) -> Unit,
    ) {
        val addresses = BoltPickupAddressPlanner.routeAnchors(supplementalPickupAddresses, parsed.pickupAddresses)
        if (addresses.isEmpty()) { callback(emptyList(), emptyList()); return }
        val handler = Handler(Looper.getMainLooper())
        val startedAt = SystemClock.elapsedRealtime()
        val lock = Any()
        val resolved = arrayOfNulls<ResolvedWaypoint>(addresses.size)
        val durations = LongArray(addresses.size) { -1L }
        var outstanding = addresses.size
        var delivered = false

        fun emit() {
            val snapshot = synchronized(lock) {
                if (delivered) null else {
                    delivered = true
                    BoltPickupResolutionOrder.successfulInRequestOrder(resolved.toList()) to durations.toList()
                }
            }
            snapshot?.let { (waypoints, times) ->
                handler.post { callback(waypoints, times) }
            }
        }
        val timeout = Runnable { emit() }
        handler.postDelayed(timeout, PICKUP_BATCH_TIMEOUT_MS)

        addresses.forEachIndexed { index, address ->
            val sentAt = SystemClock.elapsedRealtime()
            resolveStopWithTimeout(context, address) { result ->
                val ready = synchronized(lock) {
                    if (delivered || durations[index] >= 0L) false else {
                        durations[index] = SystemClock.elapsedRealtime() - sentAt
                        result.getOrNull()?.let { point ->
                            val offeredIndex = parsed.pickupAddresses.indexOfFirst {
                                BoltPickupAddressPlanner.sameAddress(it, address)
                            }
                            val label = if (offeredIndex >= 0) {
                                parsed.merchantNames.getOrNull(offeredIndex) ?: address
                            } else {
                                "Active Bolt pickup · $address"
                            }
                            resolved[index] = ResolvedWaypoint(
                                kind = WaypointKind.PICKUP,
                                point = point,
                                label = label,
                                provenance = CoordinateProvenance.GEOCODED_ADDRESS,
                                confidence = if (offeredIndex >= 0) 0.85 else 0.82,
                            )
                        }
                        outstanding -= 1
                        outstanding == 0
                    }
                }
                if (ready) {
                    handler.removeCallbacks(timeout)
                    emit()
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

    private fun loadScreenshotMarkers(
        context: Context,
        offerId: Long,
        mapBottomScreenPx: Int?,
    ): BoltSemanticMarkers? {
        val screenshotUri = runCatching { OfferDatabase.get(context).findById(offerId)?.screenshotUri }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(screenshotUri))?.use(BitmapFactory::decodeStream)
        }.getOrNull() ?: return null
        return try {
            val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val bottom = mapBottomScreenPx?.let { (it.toLong() * bitmap.height / screenHeight).toInt() }
            BoltScreenshotMarkerExtractor.extract(bitmap, mapBottomPx = bottom)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Accessibility's offer-sheet header gives a much more reliable map boundary than 72%
     * of the bitmap. Retain the legacy crop when the sheet is not semantically exposed.
     */
    private fun captureMapBottomPx(context: Context, parsed: ParsedOffer): Int? {
        val service = context as? AccessibilityService ?: return null
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != CourierSignals.BOLT_PACKAGE) return null
        val height = context.resources.displayMetrics.heightPixels
        val pickups = parsed.pickupAddresses.filter { it.length >= 5 }
            .map { it.lowercase(Locale.ROOT).take(24) }
        var best: Int? = null
        var inspected = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || inspected++ > 400) return
            val label = listOfNotNull(
                runCatching { node.text?.toString() }.getOrNull(),
                runCatching { node.contentDescription?.toString() }.getOrNull(),
            ).joinToString(" ").lowercase(Locale.ROOT)
            val isSheet = pickups.any { label.contains(it) } ||
                (label.contains("min") && (label.contains("€") || label.contains("accept") || label.contains("priim")))
            if (isSheet) {
                val rect = Rect()
                runCatching { node.getBoundsInScreen(rect) }
                if (!rect.isEmpty && rect.top in (height * 0.30).toInt()..height) {
                    best = minOf(best ?: rect.top, rect.top)
                }
            }
            for (index in 0 until node.childCount) {
                if (inspected > 400) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return best
    }

    private fun etaFor(context: Context, offerId: Long, parsed: ParsedOffer): BoltEtas {
        // Text comes from the local offer record only; never emit its address rows into telemetry.
        val rawText = runCatching {
            OfferDatabase.get(context).findById(offerId)?.rawText.orEmpty()
        }.getOrDefault("")
        val etas = BoltEtaExtractor.extract(rawText)
        return etas.copy(totalMin = etas.totalMin ?: parsed.estimatedMinutesMin)
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
            val eta = etaFor(context, offerId, parsed)
            val etaMinutes = eta.toCustomerMin ?: eta.totalMin ?: eta.toPickupMin
            onComplete(
                AutomaticBoltRouteOutcome(
                    offerId, waypoints, null, null, failureReason = reason,
                    etaEstimateMeters = etaMinutes?.let { BoltEtaDistanceModel(context).estimateMeters(it) },
                    diagnostics = BoltRecoveryDiagnostics(weakAnchorReason = reason),
                )
            )
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
    private const val PICKUP_BATCH_TIMEOUT_MS = 8_000L
}
