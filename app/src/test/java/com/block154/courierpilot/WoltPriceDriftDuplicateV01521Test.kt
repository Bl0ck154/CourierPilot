package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltPriceDriftDuplicateV01521Test {

    @Test
    fun sameFullWoltRouteWithinSecondsIsDuplicateEvenWhenTransientPriceChanges() {
        val first = record(
            capturedAt = 1_000_000L,
            priceCents = 300,
            distanceMeters = null,
        )
        val settled = record(
            capturedAt = first.capturedAt + 2_957L,
            priceCents = 317,
            distanceMeters = 1_600,
        )

        assertTrue(OfferDedupeIdentity.isSameLiveOffer(first, settled))
        assertEquals(settled, OfferDedupeIdentity.preferredHistoricalRecord(first, settled))
    }

    @Test
    fun implausibleTwoHundredEuroRecaptureLosesToNormalRicherFrame() {
        val noisy = record(
            capturedAt = 2_000_000L,
            priceCents = 20_000,
            distanceMeters = null,
            merchant = "Holy Donut (Vokiečių g.)",
            pickup = "Vokiečių g. 9, Vilnius",
            dropoff = "Oreivių gatvė 32, 02188 Vilnius",
        )
        val settled = record(
            capturedAt = noisy.capturedAt + 10_474L,
            priceCents = 481,
            distanceMeters = 5_800,
            merchant = "Holy Donut (Vokiečių g.)",
            pickup = "Vokiečių g. 9, Vilnius",
            dropoff = "Oreivių gatvė 32, 02188 Vilnius",
        )

        assertTrue(OfferDedupeIdentity.isSameLiveOffer(noisy, settled))
        assertEquals(settled, OfferDedupeIdentity.preferredHistoricalRecord(noisy, settled))
    }

    @Test
    fun differentDestinationIsNeverCollapsedByPriceDriftRule() {
        val first = record(capturedAt = 3_000_000L, priceCents = 300, distanceMeters = null)
        val differentCustomer = record(
            capturedAt = first.capturedAt + 5_000L,
            priceCents = 317,
            distanceMeters = 1_600,
            dropoff = "Gedimino pr. 44, Vilnius",
        )

        assertFalse(OfferDedupeIdentity.isSameLiveOffer(first, differentCustomer))
    }

    @Test
    fun sameRouteOutsideShortPriceDriftWindowRemainsSeparate() {
        val first = record(capturedAt = 4_000_000L, priceCents = 300, distanceMeters = 1_600)
        val later = record(
            capturedAt = first.capturedAt + 25_000L,
            priceCents = 317,
            distanceMeters = 1_600,
        )

        assertFalse(OfferDedupeIdentity.isSameLiveOffer(first, later))
    }

    private fun record(
        capturedAt: Long,
        priceCents: Int,
        distanceMeters: Int?,
        merchant: String = "Hesburger (Vokiečių)",
        pickup: String = "Vokiečių g. 7, Vilnius",
        dropoff: String = "Pylimo g. 5, 03107 Vilnius",
    ) = OfferRecord(
        capturedAt = capturedAt,
        platform = "Wolt",
        packageName = CourierSignals.WOLT_PACKAGE,
        priceCents = priceCents,
        currencyCode = "EUR",
        currencyFractionDigits = 2,
        distanceMeters = distanceMeters,
        restaurant = merchant,
        screenshotUri = "content://$capturedAt",
        screenshotFilename = "$capturedAt.png",
        rawText = "",
        merchantNames = listOf(merchant),
        pickupAddresses = listOf(pickup),
        customerNames = listOf("Customer"),
        dropoffAddresses = listOf(dropoff),
        deliveryCount = 1,
    )
}
