package com.block154.courierpilot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ValhallaContractTest {

    private val points = listOf(
        RoutePoint(54.6872, 25.2797),
        RoutePoint(54.7005, 25.3030),
    )

    @Test
    fun pedestrianPayloadStronglyPenalizesSteps() {
        val payload = JSONObject(
            ValhallaContract.buildRoutePayload(
                RouteRequest(points, RouteProfile.PEDESTRIAN_SHORTCUT)
            )
        )

        assertEquals("pedestrian", payload.getString("costing"))
        val pedestrian = payload.getJSONObject("costing_options").getJSONObject("pedestrian")
        assertEquals(3600, pedestrian.getInt("step_penalty"))
        assertEquals(2, payload.getJSONArray("locations").length())
    }

    @Test
    fun bicyclePayloadCarriesCyclewayBiasedResearchDefaults() {
        val payload = JSONObject(
            ValhallaContract.buildRoutePayload(
                RouteRequest(points, RouteProfile.CYCLEWAY_BIASED)
            )
        )

        assertEquals("bicycle", payload.getString("costing"))
        val bicycle = payload.getJSONObject("costing_options").getJSONObject("bicycle")
        assertEquals("hybrid", bicycle.getString("bicycle_type"))
        assertEquals(0.2, bicycle.getDouble("use_roads"), 0.0001)
        assertEquals(25, bicycle.getInt("cycling_speed"))
    }

    @Test
    fun parsesDistanceDurationAndPerLegShapes() {
        val response = """
            {
              "trip": {
                "summary": {"length": 4.321, "time": 812.4},
                "legs": [
                  {"shape": "shape-a"},
                  {"shape": "shape-b"}
                ]
              }
            }
        """.trimIndent()

        val result = ValhallaContract.parseRouteResponse(RouteProfile.PEDESTRIAN_SHORTCUT, response)

        assertEquals(4321, result.distanceMeters)
        assertEquals(812, result.durationSeconds)
        assertEquals(listOf("shape-a", "shape-b"), result.legShapes)
        assertEquals("valhalla", result.provider)
    }

    @Test
    fun productionRoutingRemainsDisabledDuringResearch() {
        assertFalse(RouteIntelligencePolicy.PRODUCTION_ENABLED)
        assertTrue(RouteIntelligencePolicy.MAX_POINTS >= 5)
    }
}
