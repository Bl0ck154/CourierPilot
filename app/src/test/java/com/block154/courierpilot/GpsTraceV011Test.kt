package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTraceV011Test {
    @Test
    fun rejectsVeryPoorAccuracyAndImpossibleJump() {
        val start = GpsTracePoint(1_000L, 54.6872, 25.2797, 10f, null)
        val poor = GpsTracePoint(3_000L, 54.6873, 25.2798, 150f, null)
        val jump = GpsTracePoint(3_000L, 55.0, 26.0, 5f, null)
        assertFalse(GpsTracePolicy.accept(start, poor))
        assertFalse(GpsTracePolicy.accept(start, jump))
    }

    @Test
    fun acceptsNormalCourierMovement() {
        val start = GpsTracePoint(1_000L, 54.6872, 25.2797, 8f, 4f)
        val next = GpsTracePoint(4_000L, 54.68735, 25.2799, 9f, 5f)
        assertTrue(GpsTracePolicy.accept(start, next))
        assertTrue(GpsTracePolicy.distanceMeters(start, next) in 10.0..30.0)
    }

    @Test
    fun richGeoJsonPreservesGeometryTimeAccuracyAndSpeed() {
        val body = GpsTraceDetailedExport.geoJson(
            7,
            listOf(
                GpsTracePoint(1_000L, 54.6872, 25.2797, 5f, 4.2f),
                GpsTracePoint(2_000L, 54.6880, 25.2805, 6f, null),
            ),
        )
        assertTrue(body.contains("\"session_id\":7"))
        assertTrue(body.contains("[25.2797,54.6872]"))
        assertTrue(body.contains("\"sample_count\":2"))
        assertTrue(body.contains("\"timestamps_ms\":[1000,2000]"))
        assertTrue(body.contains("\"accuracy_m\":[5.0,6.0]"))
        assertTrue(body.contains("\"speed_mps\":[4.2,null]"))
        assertTrue(body.contains("\"source\":\"courierpilot_gps_trace_v1\""))
    }
}
