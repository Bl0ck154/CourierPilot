package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class WoltMerchantHistoryRepairV01593Test {
    @Test
    fun digitPrefixedWoltMerchantIsRepairedInsteadOfBecomingUnknown() {
        val raw = """
            €4.18
            2 stops (4.7 km) • 10–17 min
            9 Habibi Oriental Food
            Pylimo g. 25, Vilnius, LT00370
            Customer drop-off
            A. Goštauto gatvė 40A, Vilnius, 01112
            Accept
        """.trimIndent()
        val stored = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 418,
            distanceMeters = 4_700,
            restaurant = "9 Habibi Oriental Food",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = raw,
            merchantNames = listOf("9 Habibi Oriental Food"),
            pickupAddresses = listOf("Pylimo g. 25, Vilnius, LT00370"),
            customerNames = listOf("Customer"),
            dropoffAddresses = listOf("A. Goštauto gatvė 40A, Vilnius, 01112"),
            deliveryCount = 1,
        )

        val repaired = stored.withCurrentParsedStructure()

        assertEquals(listOf("Habibi Oriental Food"), repaired.merchantNames)
        assertEquals("Habibi Oriental Food", repaired.restaurant)
        assertEquals("Habibi Oriental Food", OfferPresentation.merchantTitle(repaired))
    }
}
