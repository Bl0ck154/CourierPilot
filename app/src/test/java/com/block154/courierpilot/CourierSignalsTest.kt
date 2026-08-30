package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourierSignalsTest {

    @Test
    fun strictOfferNotificationRejectsRoutineDeliveryUpdates() {
        assertFalse(CourierSignals.isOfferNotificationText("Your delivery has been completed"))
        assertFalse(CourierSignals.isOfferNotificationText("Delivery update: customer changed the note"))
        assertTrue(CourierSignals.isOfferNotificationText("You have a new task"))
        assertTrue(CourierSignals.isOfferNotificationText("Order #421", listOf("Accept", "Decline")))
    }

    @Test
    fun readyForPickupStatusIsNeverANewOfferEvenWithDecisionLikeActions() {
        assertFalse(CourierSignals.isOfferNotificationText("Order is ready for pickup", listOf("Accept", "Decline")))
        assertFalse(CourierSignals.isOfferNotificationText("Užsakymas paruoštas", listOf("Priimti", "Atmesti")))
        assertFalse(CourierSignals.isOfferNotificationText("Заказ готов к выдаче", listOf("Accept")))
        assertFalse(CourierSignals.isOfferNotificationText("Замовлення готове до видачі", listOf("Accept")))
    }

    @Test
    fun decisionActionsRemainOfferSignalWhenCourierNotificationWordingChanges() {
        assertTrue(CourierSignals.isOfferNotificationText("Incoming request", listOf("Accept", "Decline")))
        assertTrue(CourierSignals.isOfferNotificationText("", listOf("Priimti", "Atmesti")))
        assertFalse(CourierSignals.isOfferNotificationText("Order completed", listOf("Accept", "Decline")))
        assertFalse(CourierSignals.isOfferNotificationText("Go online to start accepting orders"))
    }

    @Test
    fun presenceSignalsPreferExplicitScreenLanguage() {
        assertEquals(PresenceSignal.OFFLINE, CourierSignals.detectPresence("Go online to start accepting orders"))
        assertEquals(PresenceSignal.ONLINE, CourierSignals.detectPresence("You're online · waiting for orders"))
        assertEquals(PresenceSignal.UNKNOWN, CourierSignals.detectPresence("Courier Partner"))
    }

    @Test
    fun woltOfferScreenCanBeDetectedWithoutNotification() {
        val text = """
            Delivery from
            Example Burger
            Gedimino pr. 9, Vilnius
            Route distance
            8.4 km
            €4.48
            Accept
            Decline
        """.trimIndent()
        assertTrue(CourierSignals.looksLikeOfferScreen(text, OfferParser.parse(text)))
    }

    @Test
    fun acceptedDeliveryDetailsDoNotLookLikeANewOfferWithoutDecisionControls() {
        val text = """
            Delivery
            Customer
            Žirmūnų g. 23-45, Vilnius
            €4.48
            Call customer
        """.trimIndent()
        assertFalse(CourierSignals.looksLikeOfferScreen(text, OfferParser.parse(text)))
    }

    @Test
    fun accessCodeMemoryDropsApartmentNumberAndKeepsDoorCode() {
        val text = """
            Customer
            Žirmūnų g. 23-45, Vilnius
            Delivery instructions
            Please use door code 1234#
        """.trimIndent()
        val observations = CourierSignals.extractAccessCodeObservations(text)
        assertEquals(1, observations.size)
        assertEquals("Žirmūnų g. 23", observations.single().displayAddress)
        assertEquals("1234#", observations.single().code)
    }
    @Test
    fun buildingNormalizationCollapsesApartmentAndStreetAliases() {
        val apartmentDash = CourierSignals.normalizeBuildingAddress("Vokiečių g. 1-36, Vilnius")
        val apartmentSlash = CourierSignals.normalizeBuildingAddress("Vokiečių g. 1/36, LT-01130 Vilnius")
        val explicitApartment = CourierSignals.normalizeBuildingAddress("Vokiečių g. 1, butas 36")
        val longLithuanian = CourierSignals.normalizeBuildingAddress("Vokiečių gatvė 1, Vilnius")
        val english = CourierSignals.normalizeBuildingAddress("Vokiečių str. 1, Vilnius")

        assertEquals("Vokiečių g. 1", apartmentDash?.second)
        assertEquals("Vokiečių g. 1", apartmentSlash?.second)
        assertEquals("Vokiečių g. 1", explicitApartment?.second)
        assertEquals(apartmentDash?.first, apartmentSlash?.first)
        assertEquals(apartmentDash?.first, explicitApartment?.first)
        assertEquals(apartmentDash?.first, longLithuanian?.first)
        assertEquals(apartmentDash?.first, english?.first)
    }

}
