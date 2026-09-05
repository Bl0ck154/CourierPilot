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
}
