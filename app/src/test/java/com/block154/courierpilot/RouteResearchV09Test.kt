package com.block154.courierpilot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteResearchV09Test {

    @Test
    fun decodesPolyline6AndExportsGeoJson() {
        val points = RoutePolyline.decodePolyline6("????")
        assertEquals(2, points.size)
        assertEquals(0.0, points[0].latitude, 0.000001)
        assertEquals(0.0, points[1].longitude, 0.000001)

        val route = RouteResult("valhalla", RouteProfile.PEDESTRIAN_SHORTCUT, 1000, 300, listOf("????"))
        val comparison = RouteComparison(Result.success(route), Result.failure(IllegalStateException("no cycle")))
        val json = JSONObject(RoutePolyline.comparisonGeoJson(comparison))
        assertEquals("FeatureCollection", json.getString("type"))
        assertEquals(1, json.getJSONArray("features").length())
    }

    @Test
    fun offerRouteDraftRefusesUnresolvedWaypoints() {
        val parsed = ParsedOffer(
            priceCents = 500,
            distanceMeters = 2000,
            restaurant = "Test",
            merchantNames = listOf("Test"),
            pickupAddresses = listOf("Pickup 1"),
            customerNames = listOf("Customer"),
            dropoffAddresses = listOf("Drop 2"),
            deliveryCount = 1,
            estimatedMinutesMin = 10,
            estimatedMinutesMax = 15,
        )
        val draft = OfferRouteDraftBuilder.fromParsedOffer(parsed, RoutePoint(54.68, 25.27))
        assertFalse(draft.isRoutable)
        assertEquals(2, draft.unresolved.size)
    }

    @Test
    fun boltTransformRequiresScaleAndRotationEvidence() {
        val current = BoltMarkerEvidence(BoltMarkerKind.CURRENT_LOCATION, ScreenPoint(100.0, 100.0), confidence = 1.0)
        val evidence = BoltMapRecoveryEvidence(RoutePoint(54.68, 25.27), current, emptyList())
        assertFalse(evidence.canProjectCoordinates)

        val transform = LocalMapTransform.fromTwoAnchors(
            KnownMapAnchor(ScreenPoint(0.0, 0.0), RoutePoint(54.68, 25.27)),
            KnownMapAnchor(ScreenPoint(100.0, 0.0), RoutePoint(54.68, 25.271)),
        )
        assertTrue(transform.metersPerPixel > 0.0)
        assertNotNull(transform.screenToGeo(ScreenPoint(50.0, 0.0)))
    }

    @Test
    fun personalSegmentStatsNeedFiveRealSamples() {
        val four = (0 until 4).map { index ->
            MatchedEdgeTraversal("edge", index * 10_000L, index * 10_000L + 5_000L, 100)
        }
        val stats4 = PersonalRouteStatistics.summarize(four).single()
        assertEquals(null, PersonalRouteStatistics.personalizedSeconds(stats4))

        val five = four + MatchedEdgeTraversal("edge", 50_000L, 56_000L, 100)
        val stats5 = PersonalRouteStatistics.summarize(five).single()
        assertNotNull(PersonalRouteStatistics.personalizedSeconds(stats5))
    }

    @Test
    fun economicsUsesRouteDistanceAndTimeTransparently() {
        val route = RouteResult("valhalla", RouteProfile.CYCLEWAY_BIASED, 5000, 1200, emptyList())
        val estimate = OfferEconomics.estimate(600, route, restaurantWaitSeconds = 300)
        assertEquals(1.2, estimate.euroPerKilometer, 0.001)
        assertEquals(14.4, estimate.effectiveEuroPerHour, 0.001)
    }
}
