package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stacked Wolt offers can carry 2–3 venues; every venue must survive History re-parsing. */
class WoltMultiVenueHistoryTest {
    private val doubleVenueCard = """
        €12.00
        4 stops (8.3 km) • 26–39 min
        Ponas Mėsainis (Kauno g.)
        Kauno g. 13, Vilnius, LT03128
        The Urban Garden
        J.Basanaviciaus g. 3, Vilnius, LT 01118
        Multiple drop-offs (2 stops)
        Accept
    """.trimIndent()

    private fun record(raw: String, merchants: List<String> = emptyList(), pickups: List<String> = emptyList()) = OfferRecord(
        capturedAt = 1L,
        platform = "Wolt",
        packageName = CourierSignals.WOLT_PACKAGE,
        priceCents = 1200,
        distanceMeters = null,
        restaurant = merchants.takeIf { it.isNotEmpty() }?.joinToString(", "),
        screenshotUri = "",
        screenshotFilename = "",
        rawText = raw,
        merchantNames = merchants,
        pickupAddresses = pickups,
    )

    @Test
    fun branchSuffixedVenueDoesNotEvictPlainNamedSecondVenue() {
        val repaired = record(doubleVenueCard).withCurrentParsedStructure()

        assertEquals(listOf("Ponas Mėsainis (Kauno g.)", "The Urban Garden"), repaired.merchantNames)
        assertEquals("Ponas Mėsainis (Kauno g.) + The Urban Garden", OfferPresentation.merchantSummary(repaired))
        assertEquals("Ponas Mėsainis (Kauno g.)", OfferPresentation.merchantTitle(repaired))
    }

    @Test
    fun plainVenueFirstKeepsPickupOrder() {
        val raw = """
            €9.40
            4 stops (6.1 km) • 20–31 min
            The Urban Garden
            J.Basanaviciaus g. 3, Vilnius, LT 01118
            Ponas Mėsainis (Kauno g.)
            Kauno g. 13, Vilnius, LT03128
            Multiple drop-offs (2 stops)
            Accept
        """.trimIndent()

        val repaired = record(raw).withCurrentParsedStructure()

        assertEquals(listOf("The Urban Garden", "Ponas Mėsainis (Kauno g.)"), repaired.merchantNames)
    }

    @Test
    fun expandedDropoffSheetKeepsBothVenuesAndBothCustomers() {
        val raw = doubleVenueCard + "\n" +
            WoltAccessibilityDropoffRecovery.expandedFrame(listOf("Pavyzdžio gatvė 7", "Bandomoji g. 12"), 2)

        val repaired = record(raw).withCurrentParsedStructure()

        assertEquals(listOf("Ponas Mėsainis (Kauno g.)", "The Urban Garden"), repaired.merchantNames)
        assertEquals(2, repaired.pickupAddresses.size)
        assertEquals(2, repaired.dropoffAddresses.size)
    }

    @Test
    fun storedRowWithLostNamesRecoversEveryVenueFromRawText() {
        val stored = record(
            raw = doubleVenueCard,
            pickups = listOf("Kauno g. 13, Vilnius, LT03128", "J.Basanaviciaus g. 3, Vilnius, LT 01118"),
        )

        assertEquals("Ponas Mėsainis (Kauno g.) + The Urban Garden", OfferPresentation.merchantSummary(stored))
    }
}
