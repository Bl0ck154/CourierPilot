package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferDataRepairV13Test {
    @Test
    fun removesObservedMirMinuteOcrCapture() {
        val record = offer(
            priceCents = 700,
            currencyCode = "MIR",
            rawText = """
                7 MIR
                Expected earnings for the full delivery
                Delivery from
                Fresh Mesh
                Route distance
                2.7 km
                Accept
            """.trimIndent(),
            restaurant = "Fresh Mesh",
            merchantNames = listOf("Fresh Mesh"),
        )
        assertTrue(OfferDataRepair.shouldDiscardUntrustedWoltCapture(record))
    }

    @Test
    fun removesUnanchoredWeakWoltMoneyCapture() {
        val record = offer(
            priceCents = 2800,
            rawText = """
                Expected earnings for the full delivery
                Delivery from
                Accept
                Decline
                Route distance
                Estimated
                €28.00
            """.trimIndent(),
        )
        assertTrue(OfferDataRepair.shouldDiscardUntrustedWoltCapture(record))
    }

    @Test
    fun preservesStructurallyRichHistoricalWoltRowEvenWhenAmountCannotBeReparsed() {
        val record = offer(
            priceCents = 448,
            rawText = """
                Expected earnings for the full delivery
                Delivery from
                Sushi Lounge
                Route distance
                8.4 km
                Timeline
                Sushi Lounge
                Dominikonų g. 6, LT-01131 Vilnius
                Rasa T.
                Žirmūnų gatvė 54 81, Vilnius
                Accept
            """.trimIndent(),
            distanceMeters = 8400,
            restaurant = "Sushi Lounge",
            merchantNames = listOf("Sushi Lounge"),
            pickupAddresses = listOf("Dominikonų g. 6, LT-01131 Vilnius"),
            dropoffAddresses = listOf("Žirmūnų gatvė 54 81, Vilnius"),
        )
        assertFalse(OfferDataRepair.shouldDiscardUntrustedWoltCapture(record))
    }

    private fun offer(
        priceCents: Int,
        currencyCode: String = "EUR",
        rawText: String,
        distanceMeters: Int? = null,
        restaurant: String? = null,
        merchantNames: List<String> = emptyList(),
        pickupAddresses: List<String> = emptyList(),
        dropoffAddresses: List<String> = emptyList(),
    ) = OfferRecord(
        capturedAt = 1_000L,
        platform = "Wolt",
        packageName = CourierSignals.WOLT_PACKAGE,
        priceCents = priceCents,
        currencyCode = currencyCode,
        distanceMeters = distanceMeters,
        restaurant = restaurant,
        screenshotUri = "",
        screenshotFilename = "",
        rawText = rawText,
        merchantNames = merchantNames,
        pickupAddresses = pickupAddresses,
        dropoffAddresses = dropoffAddresses,
    )
}
