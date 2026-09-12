package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltIncrementalRoutePolicyTest {
    @Test
    fun oneStopAddonRoutesOnlyFromExistingDropoffToAppendedDropoff() {
        val parsed = OfferParser.parse(
            """
            +€3.58
            +1 stop (3.8 km) • 6–13 min extra
            Holy Donut (Vilniaus g.)
            Vilniaus g. 18, Vilnius, LT-01402
            Customer drop-off
            Olimpiečių gatvė 1, Vilnius, 09200
            Customer drop-off
            Laumenų gatvė 4, Vilnius, 09300
            Accept
            Decline
            """.trimIndent()
        )

        val scope = WoltIncrementalRoutePolicy.select(parsed)

        assertEquals(WoltRouteScopeKind.INCREMENTAL_DROPOFF_TAIL, scope.kind)
        assertFalse(scope.requiresCurrentLocation)
        assertTrue(scope.platformDistanceComparable)
        assertEquals(
            listOf(
                "Olimpiečių gatvė 1, Vilnius, 09200",
                "Laumenų gatvė 4, Vilnius, 09300",
            ),
            scope.stops.map { it.address },
        )
        assertTrue(scope.stops.all { it.kind == ParsedRouteStopKind.DROPOFF })
    }

    @Test
    fun multiStopAddonStaysOnConservativeFullRemainingRoute() {
        val parsed = OfferParser.parse(
            """
            +€2.78
            +2 stops (2.3 km) • 5–12 min extra
            12 Restoranas (Mindaugo g.)
            Mindaugo g. 11, Vilnius, LT03225
            Customer drop-off
            Cyber City, Vilnius, 03230
            Customer drop-off
            Pelėsos gatvė 10, Vilnius, 03225
            Estimated earnings for the full delivery
            Accept
            Decline
            """.trimIndent()
        )

        val scope = WoltIncrementalRoutePolicy.select(parsed)

        assertEquals(WoltRouteScopeKind.FULL_REMAINING, scope.kind)
        assertTrue(scope.requiresCurrentLocation)
        assertFalse(scope.platformDistanceComparable)
    }
}
