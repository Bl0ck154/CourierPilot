package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryLifecycleTrackingTest {
    @Test
    fun acceptedTaskWithoutOfferControlsTerminatesPendingOffer() {
        val text = """
            Address details
            Order details
            Difficulties with the delivery?
            Customer
            Pelėsos gatvė 10, Vilnius
        """.trimIndent()

        assertTrue(DeliveryLifecycleTracking.isAcceptedTaskWithoutOfferControls(text))
    }

    @Test
    fun activeRouteBehindAddonDoesNotTerminateVisibleOffer() {
        val text = """
            Address details
            Order details
            +€2.78
            +2 stops (2.3 km) • 5–12 min extra
            12 Restoranas (Mindaugo g.)
            Mindaugo g. 11, Vilnius, LT03225
            Customer drop-off
            Pelėsos gatvė 10, Vilnius, 03225
            Accept
            Decline
        """.trimIndent()

        assertFalse(DeliveryLifecycleTracking.isAcceptedTaskWithoutOfferControls(text))
    }
}
