package com.block154.courierpilot

internal data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

internal enum class RouteProfile {
    PEDESTRIAN_SHORTCUT,
    CYCLEWAY_BIASED,
}

internal data class RouteRequest(
    val points: List<RoutePoint>,
    val profile: RouteProfile,
)

internal data class RouteResult(
    val provider: String,
    val profile: RouteProfile,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val legShapes: List<String>,
    val httpStatus: Int? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Provider-neutral contract. The capture pipeline must never depend directly on Valhalla so route
 * intelligence can fail, be disabled or be swapped without blocking offer persistence.
 */
internal interface RouteProvider {
    fun route(request: RouteRequest): Result<RouteResult>
}

internal object RouteIntelligencePolicy {
    const val MIN_POINTS = 2
    const val MAX_POINTS = 20

    /**
     * "Production" means a calculated route is trusted enough to replace/drive normal product
     * behavior, select a preferred route, influence an accept/reject decision or become a capture
     * dependency. That remains false until real Vilnius validation exists.
     *
     * 0.10 may still run an explicitly enabled POST-CAPTURE research comparison and display both
     * candidates with provenance; that experiment must not be mistaken for production activation.
     */
    const val PRODUCTION_ENABLED = false

    fun validate(request: RouteRequest) {
        require(request.points.size in MIN_POINTS..MAX_POINTS) {
            "Route requires $MIN_POINTS..$MAX_POINTS points"
        }
        request.points.forEach { point ->
            require(point.latitude in -90.0..90.0) { "Invalid latitude: ${point.latitude}" }
            require(point.longitude in -180.0..180.0) { "Invalid longitude: ${point.longitude}" }
        }
    }
}
