package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrossPlatformOfferStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courier_offer_capture", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun staleOtherPlatformCaptureIsPreemptedByFreshOffer() {
        assertEquals(
            ArmResult.ARMED,
            OfferState.arm(context, CourierSignals.BOLT_PACKAGE, "Bolt Courier", "bolt-1"),
        )
        context.getSharedPreferences("courier_offer_capture", Context.MODE_PRIVATE).edit()
            .putLong(
                "pending_armed_at",
                System.currentTimeMillis() - OfferState.CROSS_PLATFORM_PREEMPT_AFTER_MS - 1_000L,
            )
            .commit()

        assertEquals(
            ArmResult.PREEMPTED_STALE_OTHER_PLATFORM,
            OfferState.arm(context, CourierSignals.WOLT_PACKAGE, "Wolt Partner", "wolt-1"),
        )
        assertEquals(CourierSignals.WOLT_PACKAGE, OfferState.pending(context)?.packageName)
        assertEquals("wolt-1", OfferState.pending(context)?.notificationKey)
    }

    @Test
    fun freshOtherPlatformCaptureStillQueuesBriefly() {
        assertEquals(
            ArmResult.ARMED,
            OfferState.arm(context, CourierSignals.BOLT_PACKAGE, "Bolt Courier", "bolt-1"),
        )
        assertEquals(
            ArmResult.QUEUED_OTHER_PLATFORM,
            OfferState.arm(context, CourierSignals.WOLT_PACKAGE, "Wolt Partner", "wolt-1"),
        )
        assertEquals(CourierSignals.BOLT_PACKAGE, OfferState.pending(context)?.packageName)

        OfferState.clear(context)
        assertEquals(CourierSignals.WOLT_PACKAGE, OfferState.pending(context)?.packageName)
        assertEquals("wolt-1", OfferState.pending(context)?.notificationKey)
    }
}
