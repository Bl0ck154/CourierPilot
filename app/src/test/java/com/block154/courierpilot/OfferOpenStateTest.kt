package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfferOpenStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courier_offer_open_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun windowVisibilityIsNotEnoughToVerifyOfferOpen() {
        val key = "bolt-offer-1"
        val generation = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, key)

        assertTrue(OfferOpenState.isCurrent(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.wasWindowVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.wasOfferVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.markWindowVisible(context, CourierSignals.WOLT_PACKAGE))
        assertTrue(OfferOpenState.markWindowVisible(context, CourierSignals.BOLT_PACKAGE))
        assertTrue(OfferOpenState.wasWindowVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.wasOfferVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
    }

    @Test
    fun actualOfferVisibilityUpgradesWindowToVerifiedOffer() {
        val key = "bolt-offer-2"
        val generation = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, key)

        assertTrue(OfferOpenState.markOfferVisible(context, CourierSignals.BOLT_PACKAGE))
        assertTrue(OfferOpenState.wasWindowVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertTrue(OfferOpenState.wasOfferVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.markOfferVisible(context, CourierSignals.BOLT_PACKAGE))
    }

    @Test
    fun newerAttemptInvalidatesDelayedCallbacksFromOlderAttempt() {
        val oldGeneration = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, "old")
        val newGeneration = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, "new")

        assertFalse(OfferOpenState.isCurrent(context, CourierSignals.BOLT_PACKAGE, "old", oldGeneration))
        assertTrue(OfferOpenState.isCurrent(context, CourierSignals.BOLT_PACKAGE, "new", newGeneration))
    }
    @Test
    fun woltUsesTheSameWindowAndOfferVerificationStateMachine() {
        val key = "wolt-offer-1"
        val generation = OfferOpenState.begin(context, CourierSignals.WOLT_PACKAGE, key)

        assertTrue(OfferOpenState.markWindowVisible(context, CourierSignals.WOLT_PACKAGE))
        assertTrue(OfferOpenState.wasWindowVisible(context, CourierSignals.WOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.wasOfferVisible(context, CourierSignals.WOLT_PACKAGE, key, generation))
        assertTrue(OfferOpenState.markOfferVisible(context, CourierSignals.WOLT_PACKAGE))
        assertTrue(OfferOpenState.wasOfferVisible(context, CourierSignals.WOLT_PACKAGE, key, generation))
    }


}
