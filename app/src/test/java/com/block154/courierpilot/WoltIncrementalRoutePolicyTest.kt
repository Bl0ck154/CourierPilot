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
    @Test
    fun twoStopAddonWithTrustedSamePickupBaselineRoutesExistingToNewDropoff() {
        val baseline = OfferParser.parse(
            """
            €5.00
            2 stops (5.0 km) • 12–18 min
            Eat More Chinese & Shimai Sushi (Palangos g.)
            Palangos g. 2, Vilnius, LT01117
            Customer drop-off
            Aguonų gatvė 14, Vilnius
            Estimated earnings for the full delivery
            Accept
            Decline
            """.trimIndent()
        )
        val addon = OfferParser.parse(
            """
            +€4.87
            +2 stops (3.5 km) • 7–14 min extra
            Eat More Chinese & Shimai Sushi (Palangos g.)
            Palangos g. 2, Vilnius, LT01117
            Customer drop-off
            Aguonų gatvė 14, Vilnius
            Customer drop-off
            Burbiškių g. 6b, Vilnius, 03153
            Accept
            Decline
            """.trimIndent()
        )

        val scope = WoltIncrementalRoutePolicy.select(addon, acceptedBaseline = baseline)

        assertEquals(WoltRouteScopeKind.INCREMENTAL_DROPOFF_TAIL, scope.kind)
        assertFalse(scope.requiresCurrentLocation)
        assertTrue(scope.platformDistanceComparable)
        assertEquals(
            listOf(
                "Aguonų gatvė 14, Vilnius",
                "Burbiškių g. 6b, Vilnius, 03153",
            ),
            scope.stops.map { it.address },
        )
        assertTrue(scope.stops.all { it.kind == ParsedRouteStopKind.DROPOFF })
    }

    @Test
    fun twoStopAddonWithDifferentAcceptedPickupStaysConservative() {
        val baseline = ParsedOffer(
            pickupAddresses = listOf("Mindaugo g. 11, Vilnius"),
            dropoffAddresses = listOf("Aguonų gatvė 14, Vilnius"),
        )
        val addon = ParsedOffer(
            pickupAddresses = listOf("Palangos g. 2, Vilnius"),
            dropoffAddresses = listOf(
                "Aguonų gatvė 14, Vilnius",
                "Burbiškių g. 6b, Vilnius",
            ),
            orderedRouteStops = listOf(
                ParsedRouteStop(ParsedRouteStopKind.PICKUP, "New pickup", "Palangos g. 2, Vilnius"),
                ParsedRouteStop(ParsedRouteStopKind.DROPOFF, "Customer", "Aguonų gatvė 14, Vilnius"),
                ParsedRouteStop(ParsedRouteStopKind.DROPOFF, "Customer", "Burbiškių g. 6b, Vilnius"),
            ),
            isIncrementalOffer = true,
            incrementalStopCount = 2,
        )

        val scope = WoltIncrementalRoutePolicy.select(addon, acceptedBaseline = baseline)

        assertEquals(WoltRouteScopeKind.FULL_REMAINING, scope.kind)
        assertTrue(scope.requiresCurrentLocation)
        assertFalse(scope.platformDistanceComparable)
    }

    @Test
    fun ambiguousIncrementalFullRouteIsContextOnlyForEconomics() {
        val parsed = ParsedOffer(isIncrementalOffer = true)

        assertFalse(
            WoltIncrementalRoutePolicy.canScoreResolvedRoute(
                parsed,
                WoltRouteScopeKind.FULL_REMAINING,
            )
        )
        assertTrue(
            WoltIncrementalRoutePolicy.canScoreResolvedRoute(
                parsed,
                WoltRouteScopeKind.INCREMENTAL_DROPOFF_TAIL,
            )
        )
        assertTrue(
            WoltIncrementalRoutePolicy.canScoreResolvedRoute(
                ParsedOffer(isIncrementalOffer = false),
                WoltRouteScopeKind.FULL_REMAINING,
            )
        )
    }

}
