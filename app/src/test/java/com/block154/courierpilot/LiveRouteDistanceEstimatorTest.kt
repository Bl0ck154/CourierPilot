package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRouteDistanceEstimatorTest {
    @Test
    fun visiblePlatformDistanceIsNeverRewritten() {
        val estimate = LiveRouteDistanceEstimator.estimate("Wolt", 9100)

        assertEquals(9100, estimate!!.distanceMeters)
        assertEquals("platform_distance", estimate.source)
        assertEquals(0, estimate.sampleCount)
    }

    @Test
    fun missingOrUnknownPlatformDistanceHasNoEstimate() {
        assertNull(LiveRouteDistanceEstimator.estimate("Wolt", null))
        assertNull(LiveRouteDistanceEstimator.estimate("Other", 9100))
    }
}
