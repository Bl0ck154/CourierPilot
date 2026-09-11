package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class OfferPresentationHistoryTest {
    @Test
    fun recoversWoltMerchantFromRawTextAnchoredToKnownPickup() {
        val record = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 902,
            distanceMeters = 12_600,
            restaurant = null,
            screenshotUri = "",
            screenshotFilename = "",
            rawText = """
                €9.02
                2 stops (12.6 km) • 26–39 min
                Pickup
                Hesburger (Vokiečių)
                Ready
                Vokiečių g. 7, Vilnius, LT-01130
                Customer drop-off
                Versmių gatvė 65-2, Vilnius, 11307
                Accept
            """.trimIndent(),
            merchantNames = emptyList(),
            pickupAddresses = listOf("Vokiečių g. 7, Vilnius, LT-01130"),
            dropoffAddresses = listOf("Versmių gatvė 65-2, Vilnius, 11307"),
            deliveryCount = 1,
        )

        assertEquals("Hesburger (Vokiečių)", OfferPresentation.merchantTitle(record))
    }

    @Test
    fun orphanStreetSuffixCanNeverBeShownAsMerchantTitle() {
        val record = OfferRecord(
            capturedAt = 2L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 396,
            distanceMeters = 4_400,
            restaurant = "g.)",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = "",
            merchantNames = listOf("g.)"),
            pickupAddresses = listOf("Palangos g. 2, Vilnius, LT01117"),
        )

        assertEquals("Wolt", OfferPresentation.merchantTitle(record))
    }

    @Test
    fun missingMerchantNeverTurnsPickupAddressIntoFakeVenueTitle() {
        val record = OfferRecord(
            capturedAt = 3L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 511,
            distanceMeters = 6_600,
            restaurant = null,
            screenshotUri = "",
            screenshotFilename = "",
            rawText = "€5.11\n2 stops (6.6 km) • 17–25 min\nAccept",
            merchantNames = emptyList(),
            pickupAddresses = listOf("Laisvės prospektas 85, Vilnius, 06123"),
        )

        assertEquals("Wolt", OfferPresentation.merchantTitle(record))
    }

    @Test
    fun legitimateBranchSuffixIsStillKept() {
        val record = OfferRecord(
            capturedAt = 4L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 328,
            distanceMeters = 2_000,
            restaurant = "No Forks Mexican Grill (Vokiečių str.)",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = "",
            merchantNames = listOf("No Forks Mexican Grill (Vokiečių str.)"),
            pickupAddresses = listOf("Vokiečių g. 12, Vilnius"),
        )

        assertEquals("No Forks Mexican Grill (Vokiečių str.)", OfferPresentation.merchantTitle(record))
    }
}
