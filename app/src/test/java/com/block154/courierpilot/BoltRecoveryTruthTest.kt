package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoltRecoveryTruthTest {
    @Test fun groundTruthErrorIsGeodesic() {
        val current = RoutePoint(54.68, 25.28)
        assertEquals(0.0, BoltRecoveryTruthMath.errorMeters(current, current), 0.001)
        assertEquals(111.2,
            BoltRecoveryTruthMath.errorMeters(current, RoutePoint(54.681, 25.28)), 0.5)
    }

    @Test fun multipleCustomerPinsPairToClosestTruth() {
        val actual = RoutePoint(54.68, 25.28)
        val closest = BoltRecoveryTruthMath.nearest(
            listOf(RoutePoint(54.70, 25.28), RoutePoint(54.6801, 25.28)), actual,
        )
        assertEquals(54.6801, closest!!.latitude, 1e-6)
        assertNull(BoltRecoveryTruthMath.nearest(emptyList(), actual))
    }

    @Test fun medianAndP80AreStable() {
        val stats = BoltRecoveryTruthMath.stats(listOf(50.0, 30.0, 20.0, 10.0, 40.0))
        assertEquals(5, stats.count)
        assertEquals(30.0, stats.medianMeters!!, 0.001)
        assertEquals(40.0, stats.p80Meters!!, 0.001)
    }
}
