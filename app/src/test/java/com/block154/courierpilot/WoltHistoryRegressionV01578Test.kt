package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class WoltHistoryRegressionV01578Test {
    private val sushiExpressCapturedText = """
        Decline

        €9.02

        Google Map

        2 stops (12.6 km) 23-36 min

        Vokiečių g. 7, Vilnius, LT-01130

        Versmių gatvė 65-2, Vilnius, 11307

        Estimated earnings for the full delivery

        Accept

        Map Marker

        Sushi Express (Vokiečių g.)
    """.trimIndent()

    @Test
    fun currentParserRecoversTwoStopRouteWhenOcrMerchantArrivesLast() {
        val parsed = OfferParser.parse(sushiExpressCapturedText)

        assertEquals(listOf("Vokiečių g. 7, Vilnius, LT-01130"), parsed.pickupAddresses)
        assertEquals(listOf("Versmių gatvė 65-2, Vilnius, 11307"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
    }

    @Test
    fun historyReparseReplacesLongerStaleArraysAndRecoversTrailingMerchant() {
        val brokenStored = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 902,
            distanceMeters = 12_600,
            restaurant = "g.), 8 Customer drop-off",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = sushiExpressCapturedText,
            merchantNames = listOf("g.)", "8 Customer drop-off", "Pickup"),
            pickupAddresses = listOf(
                "Palangos g. 2, Vilnius, LT01117",
                "Palangos gatvė 2, Vilius, 0117",
                "Kaminkelio gatvė 1D, Vilnius, 02182",
            ),
            customerNames = emptyList(),
            dropoffAddresses = emptyList(),
            deliveryCount = 1,
            estimatedMinutesMin = 23,
            estimatedMinutesMax = 36,
        )

        val repaired = brokenStored.withCurrentParsedStructure()

        assertEquals("Sushi Express (Vokiečių g.)", repaired.restaurant)
        assertEquals(listOf("Sushi Express (Vokiečių g.)"), repaired.merchantNames)
        assertEquals(listOf("Vokiečių g. 7, Vilnius, LT-01130"), repaired.pickupAddresses)
        assertEquals(listOf("Versmių gatvė 65-2, Vilnius, 11307"), repaired.dropoffAddresses)
        assertEquals(listOf("Customer"), repaired.customerNames)
        assertEquals(1, repaired.deliveryCount)
        assertEquals(12_600, repaired.distanceMeters)
        assertEquals(902, repaired.priceCents)
    }
}
