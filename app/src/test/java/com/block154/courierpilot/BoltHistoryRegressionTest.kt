package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoltHistoryRegressionTest {
    @Test
    fun storedBoltText_usesBottomCardPriceInsteadOfEarlierAccountTotal() {
        val raw = """
            €65.00
            Today's earnings
            Map
            Y4, Fresh Post (Cyber City)
            Mindaugo g. 11, Vilnius
            10 min
            €4.30
            Accept
            Decline
        """.trimIndent()

        val sanitized = BoltOfferTextSanitizer.sanitizeStoredRawText(raw)
        val parsed = OfferParser.parse(sanitized)

        assertFalse(sanitized.contains("€65.00"))
        assertEquals(430, parsed.priceCents)
        assertTrue(parsed.merchantNames.any { it.contains("Fresh Post") })
    }

    @Test
    fun orphanStreetSuffix_cannotBecomeBoltMerchant() {
        val raw = """
            Real Shop
            str.)
            Sodų g. 15, Vilnius
            8 min
            €3.36
            Accept
            Decline
        """.trimIndent()

        val sanitized = BoltOfferTextSanitizer.sanitizeStoredRawText(raw)
        val parsed = OfferParser.parse(sanitized)

        assertFalse(sanitized.lineSequence().any { it.trim() == "str.)" })
        assertEquals("Real Shop", parsed.merchantNames.firstOrNull())
    }

    @Test
    fun sameBoltCard_isDedupedAcrossLongerOcrEnrichmentBurst() {
        val first = boltRecord(
            capturedAt = 1_000_000L,
            priceCents = 336,
            merchant = "Real Shop",
            address = "Sodų g. 15, Vilnius",
            etaMin = 8,
            etaMax = 10,
        )
        val second = boltRecord(
            capturedAt = 1_070_000L,
            priceCents = 336,
            merchant = "str.)",
            address = "Sodų g. 15, Vilnius",
            etaMin = 11,
            etaMax = 13,
        )

        assertTrue(OfferDedupeIdentity.isSameLiveOffer(first, second))
    }

    private fun boltRecord(
        capturedAt: Long,
        priceCents: Int,
        merchant: String,
        address: String,
        etaMin: Int,
        etaMax: Int,
    ) = OfferRecord(
        capturedAt = capturedAt,
        platform = "Bolt",
        packageName = CourierSignals.BOLT_PACKAGE,
        priceCents = priceCents,
        distanceMeters = null,
        restaurant = merchant,
        screenshotUri = "",
        screenshotFilename = "",
        rawText = "",
        merchantNames = listOf(merchant),
        pickupAddresses = listOf(address),
        estimatedMinutesMin = etaMin,
        estimatedMinutesMax = etaMax,
    )
    @Test
    fun recurringBoltMapMarkerPrefixIsRemovedFromAddressRows() {
        val samples = mapOf(
            "4 Seiny gatvė 3, Vilnius" to "Seiny gatvė 3, Vilnius",
            "Y4 BASANAVIČIAUS 19, VILNIUS" to "BASANAVIČIAUS 19, VILNIUS",
            "У4 Kalvarijų g. 88, Vilnius" to "Kalvarijų g. 88, Vilnius",
        )

        samples.forEach { (rawAddress, expected) ->
            assertEquals(expected, BoltOfferTextSanitizer.stripLeadingMapMarkerFromAddress(rawAddress))
            val raw = """
                Example Shop
                $rawAddress
                9 min
                €3.10
                Accept
                Decline
            """.trimIndent()
            val parsed = OfferParser.parse(BoltOfferTextSanitizer.sanitizeStoredRawText(raw))
            assertTrue(parsed.pickupAddresses.any { it == expected })
        }
    }

    @Test
    fun markerPrefixIsNotRemovedFromLegitimateLeadingHouseNumberWithoutAnotherHouseNumber() {
        assertEquals(
            "4 Gedimino pr., Vilnius",
            BoltOfferTextSanitizer.stripLeadingMapMarkerFromAddress("4 Gedimino pr., Vilnius"),
        )
    }

    @Test
    fun storedBoltFallbackAddressIsCleanedEvenWhenRawTextIsUnavailable() {
        val repaired = OfferRecord(
            capturedAt = 1_000L,
            platform = "Bolt",
            packageName = CourierSignals.BOLT_PACKAGE,
            priceCents = 238,
            distanceMeters = null,
            restaurant = "McDonald's",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = "",
            merchantNames = listOf("McDonald's"),
            pickupAddresses = listOf("4 Seiny gatvė 3, Vilnius"),
        ).withCurrentParsedStructure()

        assertEquals(listOf("Seiny gatvė 3, Vilnius"), repaired.pickupAddresses)
    }

}
