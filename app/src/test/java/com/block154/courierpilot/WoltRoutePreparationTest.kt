package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WoltRoutePreparationTest {
    @Test
    fun completeSingleDeliveryCanPrepareBeforePrice() {
        val parsed = ParsedOffer(
            priceCents = null,
            distanceMeters = null,
            restaurant = "KFC Stotis",
            merchantNames = listOf("KFC Stotis"),
            pickupAddresses = listOf("Sodų g. 17, Vilnius"),
            dropoffAddresses = listOf("Dariaus ir Girėno g. 11, Vilnius"),
            deliveryCount = 1,
        )
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun incompleteMultiDeliveryDoesNotPrepareTooEarly() {
        val parsed = ParsedOffer(
            priceCents = null,
            distanceMeters = null,
            restaurant = null,
            pickupAddresses = listOf("Sodų g. 17, Vilnius"),
            dropoffAddresses = listOf("Dariaus ir Girėno g. 11, Vilnius"),
            deliveryCount = 2,
        )
        assertNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun changedStopInvalidatesPreparedFingerprint() {
        val first = ParsedOffer(
            priceCents = null,
            distanceMeters = null,
            restaurant = null,
            pickupAddresses = listOf("Sodų g. 17, Vilnius"),
            dropoffAddresses = listOf("Dariaus ir Girėno g. 11, Vilnius"),
            deliveryCount = 1,
        )
        val second = first.copy(dropoffAddresses = listOf("Dariaus ir Girėno g. 21, Vilnius"))
        assertNotEquals(
            AutomaticWoltRouteCoordinator.routeFingerprint(first),
            AutomaticWoltRouteCoordinator.routeFingerprint(second),
        )
    }

    @Test
    fun cityAwareQueryDoesNotDuplicateExistingCity() {
        val city = MarketCity("lt-vilnius", "Vilnius", "LT", 1L)
        assertEquals(
            listOf("Dariaus ir Girėno g. 11, Vilnius"),
            RouteGeocodeQueryPolicy.candidates("Dariaus ir Girėno g. 11, Vilnius", city),
        )
    }

    @Test
    fun cityAwareQueryQualifiesAmbiguousStreetFirst() {
        val city = MarketCity("lt-vilnius", "Vilnius", "LT", 1L)
        assertEquals(
            listOf("Dariaus ir Girėno g. 11, Vilnius, LT", "Dariaus ir Girėno g. 11"),
            RouteGeocodeQueryPolicy.candidates("Dariaus ir Girėno g. 11", city),
        )
    }
}
