package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteIntelligenceScaffoldingTest {

    @Test
    fun parsedOfferBecomesExplicitlyUnresolvedRouteDraft() {
        val parsed = ParsedOffer(
            priceCents = 640,
            distanceMeters = 4700,
            restaurant = "Venue",
            merchantNames = listOf("Venue"),
            pickupAddresses = listOf("Gedimino pr. 1, Vilnius"),
            customerNames = listOf("Customer"),
            dropoffAddresses = listOf("Žirmūnų g. 10, Vilnius"),
            deliveryCount = 1,
        )

        val draft = OfferRouteDraftBuilder.fromParsedOffer(parsed, RoutePoint(54.6872, 25.2797))

        assertFalse(draft.isRoutable)
        assertEquals(1, draft.resolved.size)
        assertEquals(CoordinateProvenance.DEVICE_GPS, draft.resolved.single().provenance)
        assertEquals(listOf(WaypointKind.PICKUP, WaypointKind.DROPOFF), draft.unresolved.map { it.kind })
    }

    @Test
    fun boltProjectionRequiresScaleAndOrientationEvidence() {
        val currentMarker = BoltMarkerEvidence(
            kind = BoltMarkerKind.CURRENT_LOCATION,
            screenCenter = ScreenPoint(500.0, 900.0),
            confidence = 1.0,
        )
        val evidence = BoltMapRecoveryEvidence(
            currentLocation = RoutePoint(54.6872, 25.2797),
            currentLocationMarker = currentMarker,
            targetMarkers = emptyList(),
        )

        assertFalse(evidence.canProjectCoordinates)
    }

    @Test
    fun localMapTransformProjectsKnownPixelOffsetNearVilnius() {
        val transform = LocalMapTransform(
            anchor = KnownMapAnchor(
                screen = ScreenPoint(500.0, 500.0),
                geo = RoutePoint(54.6872, 25.2797),
            ),
            metersPerPixel = 2.0,
            clockwiseRotationDegrees = 0.0,
        )

        val projected = transform.screenToGeo(ScreenPoint(600.0, 500.0))

        // 100 px * 2 m/px = about 200 m east; latitude should remain effectively unchanged.
        assertTrue(abs(projected.latitude - 54.6872) < 0.00001)
        assertTrue(projected.longitude > 25.282)
        assertTrue(projected.longitude < 25.284)
    }

    @Test
    fun routeComparisonKeepsOneCandidateWhenOtherFails() {
        val provider = object : RouteProvider {
            override fun route(request: RouteRequest): Result<RouteResult> = when (request.profile) {
                RouteProfile.PEDESTRIAN_SHORTCUT -> Result.success(
                    RouteResult("fake", request.profile, 4200, 800, emptyList())
                )
                RouteProfile.CYCLEWAY_BIASED -> Result.failure(IllegalStateException("bike unavailable"))
            }
        }

        val result = RouteComparisonEngine(provider).compare(
            listOf(RoutePoint(54.68, 25.27), RoutePoint(54.70, 25.30))
        )

        assertEquals(4200, result.pedestrian.getOrThrow().distanceMeters)
        assertTrue(result.cycleway.isFailure)
    }

    @Test
    fun personalSegmentTimeNeedsMinimumRealSamples() {
        val four = (0 until 4).map { index ->
            MatchedEdgeTraversal("edge-1", index * 100_000L, index * 100_000L + 20_000L, 120)
        }
        val fourStats = PersonalRouteStatistics.summarize(four).single()
        assertNull(PersonalRouteStatistics.personalizedSeconds(fourStats))

        val five = four + MatchedEdgeTraversal("edge-1", 500_000L, 525_000L, 120)
        val fiveStats = PersonalRouteStatistics.summarize(five).single()
        assertNotNull(PersonalRouteStatistics.personalizedSeconds(fiveStats))
        assertEquals(20.0, fiveStats.medianSeconds, 0.001)
    }

    @Test
    fun offerEconomicsUsesRouteAndWaitWithoutVerdict() {
        val route = RouteResult(
            provider = "valhalla",
            profile = RouteProfile.PEDESTRIAN_SHORTCUT,
            distanceMeters = 5000,
            durationSeconds = 900,
            legShapes = emptyList(),
        )

        val estimate = OfferEconomics.estimate(
            priceCents = 600,
            route = route,
            restaurantWaitSeconds = 300,
            handoffSeconds = 60,
            personalizedWaitApplied = true,
        )

        assertEquals(1.2, estimate.euroPerKilometer, 0.001)
        assertEquals(17.142, estimate.effectiveEuroPerHour, 0.01)
        assertTrue(estimate.personalizedWaitApplied)
    }
}
