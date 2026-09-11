package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalAccessHintPolicyTest {
    @Test
    fun notifiesOnlyNearDestinationWithUsableLocation() {
        assertTrue(
            ArrivalAccessHintPolicy.shouldNotify(
                distanceMeters = 72.0,
                accuracyMeters = 14f,
                ageMillis = 2_000L,
            )
        )
        assertTrue(
            ArrivalAccessHintPolicy.shouldNotify(
                distanceMeters = 260.0,
                accuracyMeters = 10f,
                ageMillis = 1_000L,
            )
        )
        assertFalse(
            ArrivalAccessHintPolicy.shouldNotify(
                distanceMeters = 360.0,
                accuracyMeters = 10f,
                ageMillis = 1_000L,
            )
        )
        assertFalse(
            ArrivalAccessHintPolicy.shouldNotify(
                distanceMeters = 40.0,
                accuracyMeters = 120f,
                ageMillis = 1_000L,
            )
        )
        assertFalse(
            ArrivalAccessHintPolicy.shouldNotify(
                distanceMeters = 40.0,
                accuracyMeters = null,
                ageMillis = 1_000L,
            )
        )
        assertFalse(
            ArrivalAccessHintPolicy.shouldNotify(
                distanceMeters = 40.0,
                accuracyMeters = 12f,
                ageMillis = ArrivalAccessHintPolicy.MAX_LOCATION_AGE_MS + 1,
            )
        )
    }

    @Test
    fun pollingAcceleratesAsCourierApproaches() {
        assertEquals(60_000L, ArrivalAccessHintPolicy.nextCheckDelayMs(2_000.0))
        assertEquals(30_000L, ArrivalAccessHintPolicy.nextCheckDelayMs(900.0))
        assertEquals(15_000L, ArrivalAccessHintPolicy.nextCheckDelayMs(400.0))
        assertEquals(8_000L, ArrivalAccessHintPolicy.nextCheckDelayMs(290.0))
    }

    @Test
    fun distanceIsReasonableForNearbyCoordinates() {
        val meters = ArrivalAccessHintPolicy.distanceMeters(
            RoutePoint(54.6872, 25.2797),
            RoutePoint(54.6881, 25.2797),
        )
        assertTrue(meters in 95.0..105.0)
    }
}
