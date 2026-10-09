package com.block154.courierpilot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

class BoltNorthUpRecoveryTest {
    private val current = RoutePoint(54.68, 25.28)

    @Test fun strongNorthUpAnchorProjectsUnderOneMetreError() {
        val c = ScreenPoint(100.0, 120.0)
        val p = ScreenPoint(280.0, 150.0)
        val target = ScreenPoint(430.0, 330.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, target), current, listOf(pickup(geo(c, p, 4.0))),
            1, bitmapWidthPx = 700
        )
        assertNotNull(result.recovery)
        val point = result.recovery!!.orderedDropoffs.single().point
        val expected = geo(c, target, 4.0)
        val error = hypot(
            (point.latitude - expected.latitude) * 111_320.0,
            (point.longitude - expected.longitude) * 111_320.0 * cos(Math.toRadians(current.latitude))
        )
        assertTrue(error < 1.0)
        assertEquals(0.0, result.recovery.transform.clockwiseRotationDegrees, 0.00001)
        assertEquals(4.0, result.diagnostics.scaleMetersPerPixel!!, 0.01)
    }

    @Test fun weakShortAnchorFailsClosedWithoutEta() {
        val c = ScreenPoint(100.0, 100.0)
        val p = ScreenPoint(130.0, 100.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, ScreenPoint(320.0, 220.0)),
            current, listOf(pickup(geo(c, p, 3.0))), 1, bitmapWidthPx = 700
        )
        assertNull(result.recovery)
        assertEquals("anchor_baseline_too_short", result.diagnostics.weakAnchorReason)
        assertTrue(result.diagnostics.projectedDropoffs.isEmpty())
    }

    @Test fun weakGeoBaselineFailsClosedEvenWithWideScreenBaseline() {
        val c = ScreenPoint(100.0, 100.0)
        val p = ScreenPoint(300.0, 100.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, ScreenPoint(360.0, 280.0)),
            current, listOf(pickup(geo(c, p, 0.8))), 1, bitmapWidthPx = 700
        )
        assertNull(result.recovery)
        assertEquals("anchor_baseline_too_short", result.diagnostics.weakAnchorReason)
    }

    @Test fun shortAnchorEtaPriorCapsConfidence() {
        val c = ScreenPoint(100.0, 100.0)
        val p = ScreenPoint(145.0, 100.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, ScreenPoint(330.0, 250.0)),
            current, listOf(pickup(geo(c, p, 3.0))), 1,
            bitmapWidthPx = 700, etaToCustomerMeters = 2_300
        )
        assertNotNull(result.recovery)
        assertEquals(1, result.recovery!!.orderedDropoffs.size)
        assertTrue(result.confidence!! <= 0.45)
        assertEquals("anchor_baseline_too_short_eta_prior", result.diagnostics.weakAnchorReason)
    }

    @Test fun rotatedPairCannotRotateNorthUpMapWithoutEta() {
        val c = ScreenPoint(100.0, 100.0)
        val p = ScreenPoint(280.0, 100.0)
        val rotatedGeo = geo(c, ScreenPoint(100.0, -100.0), 4.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, ScreenPoint(380.0, 220.0)), current,
            listOf(pickup(rotatedGeo)), 1, bitmapWidthPx = 700
        )
        assertNull(result.recovery)
        assertEquals("anchor_rotation_mismatch", result.diagnostics.weakAnchorReason)
    }

    @Test fun rotatedPairFallsBackToPickupAnchoredEtaScale() {
        val c = ScreenPoint(100.0, 100.0)
        val p = ScreenPoint(280.0, 100.0)
        val rotatedGeo = geo(c, ScreenPoint(100.0, -100.0), 4.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, ScreenPoint(380.0, 220.0)), current,
            listOf(pickup(rotatedGeo)), 1, bitmapWidthPx = 700, etaToCustomerMeters = 1_000
        )
        assertNotNull(result.recovery)
        assertEquals("anchor_rotation_mismatch_eta_prior", result.diagnostics.weakAnchorReason)
        assertTrue(result.confidence!! <= 0.45)
    }

    /**
     * Real 0.16.0 Bolt offer: the courier stood at the restaurant, the detector took a blue shop
     * POI next to the customer as the courier dot, and the customer landed ~150 m away (0.55 km
     * route for a ~14 min ride). The pickup pin + ETA must place the customer kilometres away.
     */
    @Test fun poiMistakenForCourierDotCannotPullCustomerNextToCourier() {
        val restaurantScreen = ScreenPoint(800.0, 620.0)
        val customerScreen = ScreenPoint(107.0, 835.0)
        val poiNearCustomer = ScreenPoint(50.0, 915.0)
        val restaurant = RoutePoint(54.6807, 25.2836)
        val courierAtRestaurant = RoutePoint(54.68075, 25.28365)
        val etaToCustomer = 14 * 230
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(poiNearCustomer, restaurantScreen, customerScreen), courierAtRestaurant,
            listOf(pickup(restaurant)), 1, bitmapWidthPx = 1080, etaToCustomerMeters = etaToCustomer
        )
        assertNotNull(result.recovery)
        val customer = result.recovery!!.orderedDropoffs.single().point
        val straightLine = metres(restaurant, customer)
        val expected = etaToCustomer / 1.3
        assertTrue("customer only ${straightLine.toInt()} m away", straightLine > expected * 0.8)
        assertTrue(straightLine < expected * 1.2)
    }

    @Test fun missingCourierDotStillRecoversFromPickupAndEta() {
        val p = ScreenPoint(300.0, 200.0)
        val d = ScreenPoint(500.0, 400.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            BoltSemanticMarkers(
                null,
                listOf(BoltMarkerEvidence(BoltMarkerKind.PICKUP, p, confidence = 0.85)),
                listOf(BoltMarkerEvidence(BoltMarkerKind.DROPOFF, d, confidence = 0.85)),
                emptyList(),
            ),
            current, listOf(pickup(RoutePoint(54.681, 25.281))), 1,
            bitmapWidthPx = 700, etaToCustomerMeters = 2_000
        )
        assertNotNull(result.recovery)
        assertEquals("current_marker_missing_eta_prior", result.diagnostics.weakAnchorReason)
    }

    @Test fun missingCourierDotWithoutEtaFailsClosed() {
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            BoltSemanticMarkers(
                null,
                listOf(BoltMarkerEvidence(BoltMarkerKind.PICKUP, ScreenPoint(300.0, 200.0), confidence = 0.85)),
                listOf(BoltMarkerEvidence(BoltMarkerKind.DROPOFF, ScreenPoint(500.0, 400.0), confidence = 0.85)),
                emptyList(),
            ),
            current, listOf(pickup(RoutePoint(54.681, 25.281))), 1, bitmapWidthPx = 700
        )
        assertNull(result.recovery)
        assertEquals("current_marker_missing", result.diagnostics.weakAnchorReason)
    }

    @Test fun strongTransformWithConflictingEtaIsMarkedUncertain() {
        val c = ScreenPoint(100.0, 100.0)
        val p = ScreenPoint(280.0, 100.0)
        val result = BoltMultiStopMapRecovery.recoverDetailed(
            markers(c, p, ScreenPoint(440.0, 330.0)), current,
            listOf(pickup(geo(c, p, 5.0))), 1, bitmapWidthPx = 700, etaToCustomerMeters = 150
        )
        assertNotNull(result.recovery)
        assertTrue(result.diagnostics.etaConflict)
        assertTrue(result.confidence!! <= 0.4)
    }

    private fun metres(a: RoutePoint, b: RoutePoint): Double = hypot(
        (a.latitude - b.latitude) * 111_320.0,
        (a.longitude - b.longitude) * 111_320.0 * cos(Math.toRadians(a.latitude)),
    )

    private fun markers(c: ScreenPoint, p: ScreenPoint, d: ScreenPoint) = BoltSemanticMarkers(
        BoltMarkerEvidence(BoltMarkerKind.CURRENT_LOCATION, c, confidence = 0.85),
        listOf(BoltMarkerEvidence(BoltMarkerKind.PICKUP, p, confidence = 0.85)),
        listOf(BoltMarkerEvidence(BoltMarkerKind.DROPOFF, d, confidence = 0.85)),
        emptyList(),
    )
    private fun pickup(p: RoutePoint) = ResolvedWaypoint(
        WaypointKind.PICKUP, p, "Test", CoordinateProvenance.GEOCODED_ADDRESS, 0.85
    )
    private fun geo(c: ScreenPoint, p: ScreenPoint, scale: Double) = RoutePoint(
        current.latitude + (c.y - p.y) * scale / 111_320.0,
        current.longitude + (p.x - c.x) * scale / (111_320.0 * cos(Math.toRadians(current.latitude))),
    )
}
