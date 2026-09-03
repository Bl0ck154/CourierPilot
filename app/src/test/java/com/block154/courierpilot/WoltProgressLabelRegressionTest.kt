package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WoltProgressLabelRegressionTest {

    @Test
    fun progressBannerIsNotParsedAsWoltMerchant() {
        val parsed = OfferParser.parse(
            """
            €4.00
            Expected earnings for the full delivery
            Delivery from
            66% COMPLETED
            Wingo (Route 66)
            Route distance
            3.4 km
            Estimated
            10 - 18 min
            Timeline
            Wingo (Route 66)
            Ready
            Konstitucijos pr. 12, LT-09309 Vilnius
            Customer
            12 min
            Gedimino pr. 20, LT-01103 Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(400, parsed.priceCents)
        assertEquals(listOf("Wingo (Route 66)"), parsed.merchantNames)
        assertEquals("Wingo (Route 66)", parsed.restaurant)
        assertEquals(listOf("Konstitucijos pr. 12, LT-09309 Vilnius"), parsed.pickupAddresses)
        assertFalse(parsed.customerNames.any { it.contains("completed", ignoreCase = true) })
    }
}
