package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WoltLithuanianRoadTypeRegressionTest {
    @Test
    fun parsesCurrentHolyDonutOfferWithProspektasDropoff() {
        val parsed = OfferParser.parse(
            """
            €5.07
            2 stops (5.3 km) • 12–25 min
            Holy Donut (Vilniaus g.)
            Vilniaus g. 18, Vilnius, LT-01402
            Customer drop-off
            Savanorių prospektas 46, Vilnius, 03136
            Accept
            """.trimIndent()
        )

        assertEquals(507, parsed.priceCents)
        assertEquals(5300, parsed.distanceMeters)
        assertEquals(listOf("Holy Donut (Vilniaus g.)"), parsed.merchantNames)
        assertEquals(listOf("Vilniaus g. 18, Vilnius, LT-01402"), parsed.pickupAddresses)
        assertEquals(listOf("Savanorių prospektas 46, Vilnius, 03136"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun parsesLithuanianProspektasAbbreviationAsWoltDropoff() {
        val parsed = OfferParser.parse(
            """
            €4.80
            2 stops (4.8 km) • 10–20 min
            Test Cafe (Gedimino pr.)
            Gedimino pr. 10, Vilnius, LT-01103
            Customer drop-off
            Laisvės pr. 77B, Vilnius, 06122
            Accept
            """.trimIndent()
        )

        assertEquals(listOf("Gedimino pr. 10, Vilnius, LT-01103"), parsed.pickupAddresses)
        assertEquals(listOf("Laisvės pr. 77B, Vilnius, 06122"), parsed.dropoffAddresses)
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }
}
