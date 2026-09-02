package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorV010Test {

    @Test
    fun platformEconomicsUsesVisibleOfferNumbersOnly() {
        val parsed = ParsedOffer(
            priceCents = 640,
            money = MoneyAmount(640, "EUR", 2),
            distanceMeters = 4_000,
            restaurant = "Test",
            estimatedMinutesMin = 20,
            estimatedMinutesMax = 30,
        )
        val economics = PlatformOfferEconomicsCalculator.calculate(parsed)
        assertEquals(1.6, economics.euroPerKilometer!!, 0.001)
        assertEquals(12.8, economics.euroPerHourMin!!, 0.001)
        assertEquals(19.2, economics.euroPerHourMax!!, 0.001)
    }

    @Test
    fun missingDistanceDoesNotInventPerKilometerValue() {
        val parsed = ParsedOffer(
            priceCents = 500,
            money = MoneyAmount(500, "EUR", 2),
            distanceMeters = null,
            restaurant = null,
            estimatedMinutesMin = 20,
            estimatedMinutesMax = 20,
        )
        val economics = PlatformOfferEconomicsCalculator.calculate(parsed)
        assertNull(economics.euroPerKilometer)
        assertEquals(15.0, economics.euroPerHourMin!!, 0.001)
    }

    @Test
    fun lifecycleDetectorRequiresExplicitCueAndMonotonicProgression() {
        assertEquals(
            DeliveryEventType.PICKED_UP,
            DeliveryLifecycleTracking.detect("Order picked up · navigate to customer")?.type,
        )
        assertEquals(
            DeliveryEventType.DELIVERED,
            DeliveryLifecycleTracking.detect("Delivery completed")?.type,
        )
        assertEquals(
            DeliveryEventType.ACCEPTED,
            DeliveryLifecycleTracking.detect(
                "Order is ready for pickup\nArrive in 7 min\nVynoteka (Kapsų str.)\nKapsų g. 3-43, Vilnius",
            )?.type,
        )
        assertNull(DeliveryLifecycleTracking.detect("Restaurant · Customer · 2.4 km"))
        assertNull(DeliveryLifecycleTracking.detect("Accept · Decline · €7.20"))

        assertTrue(DeliveryLifecycleTracking.canAdvance(DeliveryEventType.OFFER_CAPTURED, DeliveryEventType.ACCEPTED))
        assertTrue(DeliveryLifecycleTracking.canAdvance(DeliveryEventType.ACCEPTED, DeliveryEventType.PICKED_UP))
        assertTrue(DeliveryLifecycleTracking.canAdvance(DeliveryEventType.PICKED_UP, DeliveryEventType.DELIVERED))
        assertEquals(false, DeliveryLifecycleTracking.canAdvance(DeliveryEventType.OFFER_CAPTURED, DeliveryEventType.DELIVERED))
        assertEquals(false, DeliveryLifecycleTracking.canAdvance(DeliveryEventType.DELIVERED, DeliveryEventType.ACCEPTED))
    }

    @Test
    fun parserPreservesTimelineStopOrderForStackedRouting() {
        val parsed = OfferParser.parse(
            """
            2 deliveries from
            A, B
            Timeline
            A
            A g. 1, Vilnius
            Customer One
            C g. 3, Vilnius
            B
            B g. 2, Vilnius
            Customer Two
            D g. 4, Vilnius
            Route distance
            6.5 km
            Estimated
            25-35 min
            Expected earnings for the full delivery
            €9.00
            Accept
            Decline
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ParsedRouteStopKind.PICKUP,
                ParsedRouteStopKind.DROPOFF,
                ParsedRouteStopKind.PICKUP,
                ParsedRouteStopKind.DROPOFF,
            ),
            parsed.orderedRouteStops.map { it.kind },
        )
        assertEquals(
            listOf("A g. 1, Vilnius", "C g. 3, Vilnius", "B g. 2, Vilnius", "D g. 4, Vilnius"),
            parsed.orderedRouteStops.map { it.address },
        )
    }

    @Test
    fun routeDraftKeepsEveryParsedStopWithoutFabricatingCoordinates() {
        val parsed = ParsedOffer(
            priceCents = 900,
            money = MoneyAmount(900, "EUR", 2),
            distanceMeters = 6500,
            restaurant = "A, B",
            merchantNames = listOf("A", "B"),
            pickupAddresses = listOf("A g. 1, Vilnius", "B g. 2, Vilnius"),
            customerNames = listOf("One", "Two"),
            dropoffAddresses = listOf("C g. 3, Vilnius", "D g. 4, Vilnius"),
            deliveryCount = 2,
            estimatedMinutesMin = 25,
            estimatedMinutesMax = 35,
        )
        val draft = OfferRouteDraftBuilder.fromParsedOffer(parsed, RoutePoint(54.68, 25.27))
        assertEquals(1, draft.resolved.size)
        assertEquals(4, draft.unresolved.size)
        assertTrue(draft.unresolved.any { it.kind == WaypointKind.PICKUP })
        assertTrue(draft.unresolved.any { it.kind == WaypointKind.DROPOFF })
    }
}
