package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoltRouteEtaPlausibilityTest {
    private val model = BoltEtaDistanceModel()

    @Test fun realHesburgerRouteIsRejectedAgainstEighteenMinuteEta() {
        // 0.16.0: 0.62 km cycling route for "18 min, 3,41 €" was shown as €5.8/km 🔥.
        assertFalse(BoltRouteEtaPlausibility.isPlausible(620, 18, model))
    }

    @Test fun realisticCyclingWalkingAndCarRoutesPass() {
        assertTrue(BoltRouteEtaPlausibility.isPlausible(3_900, 18, model))
        assertTrue(BoltRouteEtaPlausibility.isPlausible(18 * 80, 18, model))
        assertTrue(BoltRouteEtaPlausibility.isPlausible(18 * 420, 18, model))
    }

    @Test fun missingEtaOrRouteNeverBlocksAVerdict() {
        assertTrue(BoltRouteEtaPlausibility.isPlausible(620, null, model))
        assertTrue(BoltRouteEtaPlausibility.isPlausible(null, 18, model))
    }

    @Test fun totalMinutesPrefersAcceptButtonThenSumsLegs() {
        assertEquals(18, BoltRouteEtaPlausibility.totalMinutes(BoltEtas(4, 14, 18)))
        assertEquals(18, BoltRouteEtaPlausibility.totalMinutes(BoltEtas(4, 14, null)))
        assertNull(BoltRouteEtaPlausibility.totalMinutes(BoltEtas(4, null, null)))
    }
}
