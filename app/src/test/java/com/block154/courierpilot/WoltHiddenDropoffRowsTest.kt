package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wolt can keep the collapsed "Multiple drop-offs" destinations in the card's Accessibility text
 * as bare `street` / `city, postcode` rows. 0.17.1 read them as extra pickups (and "Vilnius" as a
 * venue), so the route never resolved. Customer addresses below are invented.
 */
class WoltHiddenDropoffRowsTest {
    private val pickups = listOf("Vokiečių g. 24, Vilnius, LT-01130", "A. Jakšto g. 7, Vilnius, 01105")
    private val hiddenRows = "Pavyzdžio gatvė 7-12\nVilnius\nBandomoji gatvė 68\nVilnius, 10300"

    private fun card(beforeMarker: String = "", afterMarker: String = "", afterAccept: String = "") = buildString {
        appendLine("€8.09")
        appendLine("4 stops (6.8 km) • 17–30 min")
        appendLine("Blue Lotus Indian & Thai")
        appendLine(pickups[0])
        appendLine("Saigon")
        appendLine(pickups[1])
        if (beforeMarker.isNotEmpty()) appendLine(beforeMarker)
        appendLine("Multiple drop-offs (2 stops)")
        if (afterMarker.isNotEmpty()) appendLine(afterMarker)
        append("Accept")
        if (afterAccept.isNotEmpty()) append("\n" + afterAccept)
    }

    private fun assertRoutable(text: String) {
        val parsed = OfferParser.parse(text)
        assertEquals(listOf("Blue Lotus Indian & Thai", "Saigon"), parsed.merchantNames)
        assertEquals(pickups, parsed.pickupAddresses)
        assertEquals(listOf("Pavyzdžio gatvė 7-12", "Bandomoji gatvė 68"), parsed.dropoffAddresses)
        assertEquals(2, parsed.deliveryCount)
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun hiddenRowsAfterCollapsedMarkerAreCustomers() = assertRoutable(card(afterMarker = hiddenRows))

    @Test
    fun hiddenRowsBeforeCollapsedMarkerAreCustomers() = assertRoutable(card(beforeMarker = hiddenRows))

    @Test
    fun hiddenRowsAfterAcceptAreCustomers() = assertRoutable(card(afterAccept = hiddenRows))

    @Test
    fun hiddenRowsPlusOpenedSheetStayConsistent() = assertRoutable(
        card(afterMarker = hiddenRows) + "\nMultiple dropoffs\n2 stops\n" + hiddenRows + "\nDone"
    )

    @Test
    fun openedSheetWithoutHyphenIsRead() = assertRoutable(
        card() + "\nMultiple dropoffs\n2 stops\n" + hiddenRows + "\nDone"
    )

    @Test
    fun incompleteHiddenRowsNeverBecomePickups() {
        val parsed = OfferParser.parse(card(beforeMarker = "Pavyzdžio gatvė 7-12\nVilnius"))
        assertEquals(pickups, parsed.pickupAddresses)
        assertTrue(parsed.dropoffAddresses.isEmpty())
        assertNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun localityLineIsNeverAVenue() {
        val parsed = OfferParser.parse(card(afterMarker = hiddenRows))
        assertTrue(parsed.merchantNames.none { it.startsWith("Vilnius") })
    }
}
