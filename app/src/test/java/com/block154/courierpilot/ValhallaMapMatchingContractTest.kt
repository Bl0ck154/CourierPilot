package com.block154.courierpilot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ValhallaMapMatchingContractTest {

    @Test
    fun buildsMapSnapTraceWithEdgeAndMatchedPointFilters() {
        val samples = listOf(
            GpsTraceSample(1_000_000L, RoutePoint(54.6872, 25.2797), accuracyMeters = 5f),
            GpsTraceSample(1_003_000L, RoutePoint(54.6875, 25.2801), accuracyMeters = 7f),
            GpsTraceSample(1_007_000L, RoutePoint(54.6879, 25.2808), accuracyMeters = 6f),
        )

        val payload = JSONObject(
            ValhallaMapMatchingContract.buildTraceAttributesPayload(
                samples,
                RouteProfile.PEDESTRIAN_SHORTCUT,
            )
        )

        assertEquals("pedestrian", payload.getString("costing"))
        assertEquals("map_snap", payload.getString("shape_match"))
        assertEquals(3, payload.getJSONArray("shape").length())
        assertEquals(0.0, payload.getJSONArray("shape").getJSONObject(0).getDouble("time"), 0.001)
        assertEquals(7.0, payload.getJSONArray("shape").getJSONObject(2).getDouble("time"), 0.001)
        val attrs = payload.getJSONObject("filters").getJSONArray("attributes")
        assertTrue((0 until attrs.length()).map { attrs.getString(it) }.contains("edge.id"))
        assertTrue((0 until attrs.length()).map { attrs.getString(it) }.contains("matched.edge_index"))
    }

    @Test
    fun parsesMatchedEdgesAndPoints() {
        val response = """
            {
              "edges": [
                {"id": 12345, "way_id": 98765, "length": 0.120, "use": "cycleway", "surface": "paved", "cycle_lane": "shared"},
                {"id": 12346, "way_id": 98766, "length": 0.080, "use": "footway", "surface": "paved"}
              ],
              "matched_points": [
                {"lat":54.6872,"lon":25.2797,"type":"matched","edge_index":0,"distance_from_trace_point":1.4},
                {"lat":54.6879,"lon":25.2808,"type":"matched","edge_index":1,"distance_from_trace_point":2.1}
              ],
              "shape":"encoded-six-digit-shape"
            }
        """.trimIndent()

        val matched = ValhallaMapMatchingContract.parseTraceAttributesResponse(response)

        assertEquals(2, matched.edges.size)
        assertEquals("12345", matched.edges[0].edgeId)
        assertEquals("98765", matched.edges[0].osmWayId)
        assertEquals(120, matched.edges[0].lengthMeters)
        assertEquals("cycleway", matched.edges[0].use)
        assertEquals(2, matched.matchedPoints.size)
        assertEquals(1, matched.matchedPoints[1].edgeIndex)
        assertEquals("encoded-six-digit-shape", matched.encodedShape)
    }
}
