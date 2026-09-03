package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltCaptureRegressionV01519Test {

    @Test
    fun implausibleEuroOcrTokenDoesNotWinOverLaterValidOfferAmount() {
        assertTrue(MarketCurrencyParser.containsMoney("€200.00"))
        assertEquals(
            MoneyAmount(418, "EUR", 2),
            MarketCurrencyParser.parse("€200.00\n€4.18"),
        )
        assertNull(MarketCurrencyParser.parse("€200.00"))
    }

    @Test
    fun structuralReparseNeverOverwritesPersistedOfferPrice() {
        val stored = OfferRecord(
            capturedAt = 1_788_380_027_000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 418,
            distanceMeters = 3_500,
            restaurant = "KFC (Stotis)",
            screenshotUri = "content://offer",
            screenshotFilename = "offer.png",
            rawText = """
                €7.99
                Expected earnings for the full delivery
                Delivery from
                KFC (Stotis)
                Route distance
                3.5 km
            """.trimIndent(),
            merchantNames = listOf("KFC (Stotis)"),
            pickupAddresses = listOf("V. Šopeno g. 1, LT02100 Vilnius"),
            customerNames = listOf("Customer"),
            dropoffAddresses = listOf("Dariaus ir Girėno gatvė 11, 02170 Vilnius"),
            deliveryCount = 1,
        )

        val reparsed = stored.withCurrentParsedStructure()

        assertEquals(418, reparsed.priceCents)
        assertEquals("EUR", reparsed.currencyCode)
    }

    @Test
    fun gluedStreetMarkerDuplicateCollapsesToOnePickup() {
        val stored = OfferRecord(
            capturedAt = 1_788_380_027_000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 418,
            distanceMeters = 3_500,
            restaurant = "KFC (Stotis)",
            screenshotUri = "content://offer",
            screenshotFilename = "offer.png",
            rawText = "",
            merchantNames = listOf("KFC (Stotis)"),
            pickupAddresses = listOf(
                "V. Šopeno g. 1, LT02100 Vilnius",
                "V. Šopenog. 1, LT02100 Vilnius",
            ),
            customerNames = listOf("Muhammad N."),
            dropoffAddresses = listOf("Dariaus ir Girėno gatvė 11, 02170 Vilnius"),
            deliveryCount = 1,
        )

        val repaired = stored.withCurrentParsedStructure()

        assertEquals(listOf("V. Šopeno g. 1, LT02100 Vilnius"), repaired.pickupAddresses)
        assertEquals(listOf("KFC (Stotis)"), repaired.merchantNames)
        assertEquals(1, repaired.deliveryCount)
    }
}
