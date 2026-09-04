package com.block154.courierpilot

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOfferNotificationLifetimeTest {
    @After
    fun cleanup() = LiveOfferNotificationLifetime.clearForTest()

    @Test
    fun tracksAndRemovesExactOfferNotification() {
        LiveOfferNotificationLifetime.markActive("com.wolt.courierapp", "offer-1")
        assertTrue(LiveOfferNotificationLifetime.isActive("com.wolt.courierapp", "offer-1"))

        LiveOfferNotificationLifetime.markRemoved("com.wolt.courierapp", "offer-1")
        assertFalse(LiveOfferNotificationLifetime.isActive("com.wolt.courierapp", "offer-1"))
    }

    @Test
    fun reconcileReplacesStaleLifetimeState() {
        LiveOfferNotificationLifetime.markActive("old.pkg", "old-key")
        LiveOfferNotificationLifetime.replaceActive(listOf("com.wolt.courierapp" to "live-key"))

        assertFalse(LiveOfferNotificationLifetime.isActive("old.pkg", "old-key"))
        assertTrue(LiveOfferNotificationLifetime.isActive("com.wolt.courierapp", "live-key"))
    }

    @Test
    fun screenDiscoveredPseudoKeysAreNeverLifetimeAnchors() {
        LiveOfferNotificationLifetime.markActive("com.wolt.courierapp", "screen:abc")
        assertFalse(LiveOfferNotificationLifetime.isActive("com.wolt.courierapp", "screen:abc"))
    }
}
