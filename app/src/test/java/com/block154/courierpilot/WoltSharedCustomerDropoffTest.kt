package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wolt can combine orders from two merchants for one customer.
 * "Drop off 2 orders" counts parcels/orders, not distinct customer destinations.
 */
class WoltSharedCustomerDropoffTest {
    private val sharedCustomerCard = """
        €8.25
        3 stops (10.6 km) • 30–43 min
        **MOSS **
        Pylimo g. 47, Vilnius, LT-01137
        Talutti Bakes'n'Shakes City
        Vilniaus g. 35, Vilnius, LT01119
        Customer drop-off
        Viršuliškių gatvė 93, Vilnius, 05117
        Drop off 2 orders
        Accept
    """.trimIndent()

    @Test
    fun twoRestaurantsOneCustomerHasThreePhysicalStopsAndTwoOrders() {
        val parsed = OfferParser.parse(sharedCustomerCard)

        assertEquals(825, parsed.priceCents)
        assertEquals(10_600, parsed.distanceMeters)
        assertEquals(30, parsed.estimatedMinutesMin)
        assertEquals(43, parsed.estimatedMinutesMax)
        assertEquals(listOf("**MOSS **", "Talutti Bakes'n'Shakes City"), parsed.merchantNames)
        assertEquals(
            listOf("Pylimo g. 47, Vilnius, LT-01137", "Vilniaus g. 35, Vilnius, LT01119"),
            parsed.pickupAddresses,
        )
        assertEquals(listOf("Viršuliškių gatvė 93, Vilnius, 05117"), parsed.dropoffAddresses)
        assertEquals(2, parsed.deliveryCount)
        assertEquals(2, parsed.sharedDropoffOrderCount)
        assertEquals(
            listOf(ParsedRouteStopKind.PICKUP, ParsedRouteStopKind.PICKUP, ParsedRouteStopKind.DROPOFF),
            parsed.orderedRouteStops.map { it.kind },
        )
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun incompleteThreeStopCardDoesNotRouteWithMissingSecondPickup() {
        val incomplete = """
            €8.25
            3 stops (10.6 km) • 30–43 min
            **MOSS **
            Pylimo g. 47, Vilnius, LT-01137
            Customer drop-off
            Viršuliškių gatvė 93, Vilnius, 05117
            Drop off 2 orders
            Accept
        """.trimIndent()

        val parsed = OfferParser.parse(incomplete)

        assertEquals(2, parsed.deliveryCount)
        assertNull(parsed.sharedDropoffOrderCount)
        assertNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun twoSeparateCustomersStillRequireTwoDropoffs() {
        val twoCustomers = """
            €8.25
            4 stops (10.6 km) • 30–43 min
            MOSS
            Pylimo g. 47, Vilnius, LT-01137
            Talutti Bakes'n'Shakes City
            Vilniaus g. 35, Vilnius, LT01119
            Customer drop-off
            Viršuliškių gatvė 93, Vilnius, 05117
            Customer drop-off
            Ukmergės gatvė 10, Vilnius, 09313
            Accept
        """.trimIndent()
        val parsed = OfferParser.parse(twoCustomers)

        assertEquals(2, parsed.deliveryCount)
        assertNull(parsed.sharedDropoffOrderCount)
        assertEquals(2, parsed.pickupAddresses.size)
        assertEquals(2, parsed.dropoffAddresses.size)
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun historicalStructureReparseKeepsTwoOrdersAndOneDestination() {
        val stored = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 825,
            distanceMeters = 10_600,
            restaurant = null,
            screenshotUri = "",
            screenshotFilename = "",
            rawText = sharedCustomerCard,
            deliveryCount = 1,
        )

        val repaired = stored.withCurrentParsedStructure()

        assertEquals(2, repaired.deliveryCount)
        assertEquals(2, repaired.merchantNames.size)
        assertEquals(2, repaired.pickupAddresses.size)
        assertEquals(1, repaired.dropoffAddresses.size)
        assertTrue(repaired.merchantNames.none { "Drop off" in it })
    }
    @Test
    fun missingThreeStopSummaryCannotConfirmSharedCustomerRoute() {
        val parsed = OfferParser.parse(
            """
            €8.25
            MOSS
            Pylimo g. 47, Vilnius, LT-01137
            Customer drop-off
            Viršuliškių gatvė 93, Vilnius, 05117
            Drop off 2 orders
            Accept
            """.trimIndent()
        )
        assertEquals(2, parsed.deliveryCount)
        assertNull(parsed.sharedDropoffOrderCount)
        assertNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

}
