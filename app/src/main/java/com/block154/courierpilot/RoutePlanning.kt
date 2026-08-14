package com.block154.courierpilot

internal enum class WaypointKind {
    CURRENT_LOCATION,
    PICKUP,
    DROPOFF,
}

internal enum class CoordinateProvenance {
    DEVICE_GPS,
    GEOCODED_ADDRESS,
    ACCESSIBILITY_SEMANTICS,
    BOLT_MAP_RECOVERY,
    TEST_FIXTURE,
}

internal data class ResolvedWaypoint(
    val kind: WaypointKind,
    val point: RoutePoint,
    val label: String? = null,
    val provenance: CoordinateProvenance,
    val confidence: Double = 1.0,
)

internal data class UnresolvedWaypoint(
    val kind: WaypointKind,
    val label: String? = null,
    val address: String? = null,
)

internal data class OfferRouteDraft(
    val resolved: List<ResolvedWaypoint>,
    val unresolved: List<UnresolvedWaypoint>,
) {
    val isRoutable: Boolean
        get() = unresolved.isEmpty() && resolved.size >= 2

    fun asRouteRequest(profile: RouteProfile): RouteRequest {
        require(isRoutable) { "Route draft still contains unresolved waypoints" }
        return RouteRequest(resolved.map { it.point }, profile)
    }
}

internal interface AddressGeocoder {
    fun geocode(address: String): Result<RoutePoint>
}

internal interface CurrentLocationSource {
    fun currentLocation(): Result<RoutePoint>
}

/** Converts fields CourierPilot already parses into a route draft without inventing coordinates. */
internal object OfferRouteDraftBuilder {
    fun fromParsedOffer(parsed: ParsedOffer, currentLocation: RoutePoint?): OfferRouteDraft {
        val resolved = mutableListOf<ResolvedWaypoint>()
        val unresolved = mutableListOf<UnresolvedWaypoint>()

        currentLocation?.let {
            resolved += ResolvedWaypoint(
                kind = WaypointKind.CURRENT_LOCATION,
                point = it,
                label = "Current location",
                provenance = CoordinateProvenance.DEVICE_GPS,
            )
        } ?: run {
            unresolved += UnresolvedWaypoint(WaypointKind.CURRENT_LOCATION, label = "Current location")
        }

        parsed.pickupAddresses.forEachIndexed { index, address ->
            unresolved += UnresolvedWaypoint(
                kind = WaypointKind.PICKUP,
                label = parsed.merchantNames.getOrNull(index) ?: parsed.restaurant,
                address = address,
            )
        }

        parsed.dropoffAddresses.forEachIndexed { index, address ->
            unresolved += UnresolvedWaypoint(
                kind = WaypointKind.DROPOFF,
                label = parsed.customerNames.getOrNull(index),
                address = address,
            )
        }

        return OfferRouteDraft(resolved, unresolved)
    }

    fun appendRecoveredPoint(
        draft: OfferRouteDraft,
        waypoint: UnresolvedWaypoint,
        point: RoutePoint,
        provenance: CoordinateProvenance,
        confidence: Double,
    ): OfferRouteDraft {
        require(waypoint in draft.unresolved) { "Waypoint does not belong to this route draft" }
        require(confidence in 0.0..1.0) { "Confidence must be between 0 and 1" }
        return OfferRouteDraft(
            resolved = draft.resolved + ResolvedWaypoint(
                kind = waypoint.kind,
                point = point,
                label = waypoint.label ?: waypoint.address,
                provenance = provenance,
                confidence = confidence,
            ),
            unresolved = draft.unresolved - waypoint,
        )
    }
}

internal data class RouteComparison(
    val pedestrian: Result<RouteResult>,
    val cycleway: Result<RouteResult>,
)

internal class RouteComparisonEngine(private val provider: RouteProvider) {
    fun compare(points: List<RoutePoint>): RouteComparison {
        val pedestrianRequest = RouteRequest(points, RouteProfile.PEDESTRIAN_SHORTCUT)
        val cycleRequest = RouteRequest(points, RouteProfile.CYCLEWAY_BIASED)
        return RouteComparison(
            pedestrian = provider.route(pedestrianRequest),
            cycleway = provider.route(cycleRequest),
        )
    }
}
