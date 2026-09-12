package com.block154.courierpilot

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure, privacy-safe geometry helpers for route diagnostics and plausibility checks. */
internal object RouteGeometryMetrics {
    fun directChainMeters(waypoints: List<ResolvedWaypoint>): Int? {
        if (waypoints.size < 2) return null
        return directLegMeters(waypoints).sum()
    }

    fun directLegMeters(waypoints: List<ResolvedWaypoint>): List<Int> =
        waypoints.zipWithNext().map { (from, to) -> haversineMeters(from.point, to.point) }

    /** Only waypoint kinds + distances; never addresses, labels or coordinates. */
    fun directLegSummary(waypoints: List<ResolvedWaypoint>): String =
        waypoints.zipWithNext().joinToString(",") { (from, to) ->
            "${kindCode(from.kind)}-${kindCode(to.kind)}:${haversineMeters(from.point, to.point)}"
        }

    private fun kindCode(kind: WaypointKind): String = when (kind) {
        WaypointKind.CURRENT_LOCATION -> "C"
        WaypointKind.PICKUP -> "P"
        WaypointKind.DROPOFF -> "D"
    }

    private fun haversineMeters(a: RoutePoint, b: RoutePoint): Int {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return (2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))).roundToInt()
    }
}
