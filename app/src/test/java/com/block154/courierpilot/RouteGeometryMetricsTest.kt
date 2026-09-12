package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGeometryMetricsTest {
    private fun waypoint(kind: WaypointKind, lat: Double, lon: Double) = ResolvedWaypoint(
        kind = kind,
        point = RoutePoint(lat, lon),
        provenance = CoordinateProvenance.TEST_FIXTURE,
    )

    @Test
    fun reportsPrivacySafeDirectLegBreakdownInRouteOrder() {
        val waypoints = listOf(
            waypoint(WaypointKind.CURRENT_LOCATION, 54.6872, 25.2797),
            waypoint(WaypointKind.PICKUP, 54.6900, 25.2800),
            waypoint(WaypointKind.PICKUP, 54.7000, 25.2900),
            waypoint(WaypointKind.DROPOFF, 54.7100, 25.3000),
            waypoint(WaypointKind.DROPOFF, 54.7200, 25.3100),
        )

        val legs = RouteGeometryMetrics.directLegMeters(waypoints)
        assertEquals(4, legs.size)
        assertEquals(legs.sum(), RouteGeometryMetrics.directChainMeters(waypoints))
        val summary = RouteGeometryMetrics.directLegSummary(waypoints)
        val entries = summary.split(",")
        assertEquals(4, entries.size)
        assertEquals(listOf("C-P", "P-P", "P-D", "D-D"), entries.map { it.substringBefore(":") })
        assertEquals(legs, entries.map { it.substringAfter(":").toInt() })
    }
}
