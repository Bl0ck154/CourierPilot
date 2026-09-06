package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRouteDistanceEstimatorTest {
    @Test
    fun historicalFactorUsesMedianInsteadOfSingleOutlier() {
        val factor = LiveRouteDistanceEstimator.historicalFactor(
            listOf(0.54, 0.56, 0.55, 2.80, 0.53),
        )

        assertEquals(0.55, factor!!, 0.0001)
    }

    @Test
    fun historicalFactorRejectsImplausibleRatios() {
        assertNull(LiveRouteDistanceEstimator.historicalFactor(listOf(0.05, 4.5)))
    }
}
