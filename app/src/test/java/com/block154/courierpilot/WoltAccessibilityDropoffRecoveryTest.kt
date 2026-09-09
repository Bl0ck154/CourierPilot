package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltAccessibilityDropoffRecoveryTest {
    @Test
    fun recoversTwoHiddenCustomersWithoutOpeningSheet() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Multiple drop-offs",
                "2 stops",
                "V. Grybo Gatvė 34",
                "Vilnius",
                "Kaukyšos gatvė 18\nVilnius, 11342",
                "Done",
            ),
            excludedAddresses = listOf("Upės g. 6, Vilnius, LT-09309"),
            expectedCount = 2,
        )

        assertEquals(listOf("V. Grybo Gatvė 34", "Kaukyšos gatvė 18"), result.resolvedAddresses)
        assertEquals(2, result.candidateCount)
        assertTrue(WoltAccessibilityDropoffRecovery.expandedFrame(result.resolvedAddresses, 2).contains("2 stops"))
    }

    @Test
    fun ignoresHiddenDuplicatesOfVisiblePickupAddresses() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Upės g. 6",
                "V. Grybo Gatvė 34",
                "Kaukyšos gatvė 18",
            ),
            excludedAddresses = listOf("Upės g. 6, Vilnius, LT-09309"),
            expectedCount = 2,
        )

        assertEquals(listOf("V. Grybo Gatvė 34", "Kaukyšos gatvė 18"), result.resolvedAddresses)
        assertEquals(2, result.candidateCount)
    }

    @Test
    fun recoversOpenedSheetAddressesEvenWhenDropoffLabelsAreMissing() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Sushi Out (Upės g.)",
                "Upės g. 6, Vilnius, LT-09309",
                "Multiple drop-offs",
                "2 stops",
                "V. Grybo Gatvė 34",
                "Vilnius",
                "Kaukyšos gatvė 18",
                "Vilnius, 11342",
                "Done",
            ),
            excludedAddresses = listOf("Upės g. 6, Vilnius, LT-09309"),
            expectedCount = 2,
        )

        assertEquals(listOf("V. Grybo Gatvė 34", "Kaukyšos gatvė 18"), result.resolvedAddresses)
    }

    @Test
    fun recoversFiveHiddenCustomersWithoutAnyHardCodedBatchLimit() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "A. Goštauto g. 12",
                "Žirmūnų g. 54",
                "Kalvarijų g. 125",
                "Ozo g. 18",
                "Ukmergės g. 221",
            ),
            excludedAddresses = listOf("Vokiečių g. 12"),
            expectedCount = 5,
        )

        assertEquals(5, result.resolvedAddresses.size)
        assertEquals("A. Goštauto g. 12", result.resolvedAddresses.first())
        assertEquals("Ukmergės g. 221", result.resolvedAddresses.last())
    }

    @Test
    fun mergesVisibleOldDropoffWithHiddenNewStopsForAddOnOffer() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Žirmūnų g. 54",
                "Kalvarijų g. 125",
                "Ozo g. 18",
            ),
            excludedAddresses = listOf("Vokiečių g. 12"),
            expectedCount = 4,
            knownAddresses = listOf("Arklių gatvė 36, Vilnius, 01305"),
        )

        assertEquals(
            listOf(
                "Arklių gatvė 36, Vilnius, 01305",
                "Žirmūnų g. 54",
                "Kalvarijų g. 125",
                "Ozo g. 18",
            ),
            result.resolvedAddresses,
        )
        assertEquals(3, result.candidateCount)
    }

    @Test
    fun refusesAmbiguousHiddenAddressSetSoClickFallbackCanRun() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "V. Grybo Gatvė 34",
                "Kaukyšos gatvė 18",
                "Žirmūnų g. 64",
            ),
            excludedAddresses = emptyList(),
            expectedCount = 2,
        )

        assertTrue(result.resolvedAddresses.isEmpty())
        assertEquals(3, result.candidateCount)
    }

    @Test
    fun doesNotTreatMenuOrMapLabelsAsCustomerAddresses() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Vilnius",
                "Kalvarijų turgus",
                "Ready for pickup",
                "4 stops (14.9 km)",
                "Decline",
            ),
            excludedAddresses = emptyList(),
            expectedCount = 2,
        )

        assertTrue(result.resolvedAddresses.isEmpty())
        assertEquals(0, result.candidateCount)
    }
    @Test
    fun recoversCurrentVilniusOpenedSheetAddressesFromAccessibility() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Multiple dropoffs",
                "2 stops",
                "Liepkalnio gatvė 22",
                "Vilnius, 02105",
                "Bartų g. 30",
                "Vilnius, 03153",
                "Done",
            ),
            excludedAddresses = emptyList(),
            expectedCount = 2,
        )

        assertEquals(listOf("Liepkalnio gatvė 22", "Bartų g. 30"), result.resolvedAddresses)
        assertEquals(2, result.candidateCount)
    }

    @Test
    fun recoveredHiddenStopsImmediatelyCompleteCurrentFourStopRoute() {
        val collapsedCard = """
            €4.08
            4 stops (3.6 km) • 13–20 min
            Crustum (Vokiečių g.)
            Vokiečių g. 18a, Vilnius, LT01130
            Ponas Mėsainis (Kauno g.)
            Kauno g. 13, Vilnius, LT03128
            Multiple drop-offs (2 stops)
            Accept
            Decline
        """.trimIndent()
        val recovered = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Multiple drop-offs",
                "2 stops",
                "Liepkalnio gatvė 22",
                "Vilnius, 02105",
                "Bartų g. 30",
                "Vilnius, 03153",
                "Done",
            ),
            excludedAddresses = listOf("Vokiečių g. 18a", "Kauno g. 13"),
            expectedCount = 2,
        )
        val merged = collapsedCard + "\n" +
            WoltAccessibilityDropoffRecovery.expandedFrame(recovered.resolvedAddresses, 2)
        val parsed = OfferParser.parse(merged)

        assertEquals(2, parsed.pickupAddresses.size)
        assertEquals(2, parsed.dropoffAddresses.size)
        assertTrue(AutomaticWoltRouteCoordinator.routeFingerprint(parsed) != null)
    }

    @Test
    fun recoversNamedPlacePostalCustomerWithoutOpeningSheet() {
        val result = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = listOf(
                "Cyber City, Vilnius, 03230",
                "Pelėsos gatvė 10, Vilnius, 03225",
            ),
            excludedAddresses = listOf("Mindaugo g. 11, Vilnius, LT-03225"),
            expectedCount = 2,
        )

        assertEquals(
            listOf("Cyber City, Vilnius, 03230", "Pelėsos gatvė 10, Vilnius, 03225"),
            result.resolvedAddresses,
        )
        assertEquals(2, result.candidateCount)
    }

}
