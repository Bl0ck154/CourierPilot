package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue(RouteGeometryMetrics.directLegSummary(waypoints).matches(
            Regex("C-P:\d+,P-P:\d+,P-D:\d+,D-D:\d+")
        ))
    }
}
