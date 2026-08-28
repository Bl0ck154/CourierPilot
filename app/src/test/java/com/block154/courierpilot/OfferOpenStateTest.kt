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
    fun accessibilityVisibilityAcknowledgesOnlyCurrentOpenAttempt() {
        val key = "bolt-offer-1"
        val generation = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, key)

        assertTrue(OfferOpenState.isCurrent(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.wasVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.markVisible(context, CourierSignals.WOLT_PACKAGE))
        assertTrue(OfferOpenState.markVisible(context, CourierSignals.BOLT_PACKAGE))
        assertTrue(OfferOpenState.wasVisible(context, CourierSignals.BOLT_PACKAGE, key, generation))
        assertFalse(OfferOpenState.markVisible(context, CourierSignals.BOLT_PACKAGE))
    }

    @Test
    fun newerAttemptInvalidatesDelayedCallbacksFromOlderAttempt() {
        val oldGeneration = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, "old")
        val newGeneration = OfferOpenState.begin(context, CourierSignals.BOLT_PACKAGE, "new")

        assertFalse(OfferOpenState.isCurrent(context, CourierSignals.BOLT_PACKAGE, "old", oldGeneration))
        assertTrue(OfferOpenState.isCurrent(context, CourierSignals.BOLT_PACKAGE, "new", newGeneration))
    }
}
