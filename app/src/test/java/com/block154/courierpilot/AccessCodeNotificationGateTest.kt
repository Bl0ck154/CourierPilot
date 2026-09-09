package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessCodeNotificationGateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AccessCodeNotificationGate.clearForTests(context)
    }

    @Test
    fun reopeningSameDeliveryCannotNotifyTwice() {
        val key = AccessCodeNotificationGate.deliveryKey(
            CourierSignals.BOLT_PACKAGE,
            "zirmunu g 23",
            "Žirmūnų g. 23, Vilnius",
            unitHint = "145",
        )

        assertTrue(AccessCodeNotificationGate.claim(context, key, now = 10_000L))
        assertFalse(AccessCodeNotificationGate.claim(context, key, now = 20_000L))
    }

    @Test
    fun consumingVisibleCurrentOrderCodeKeepsLaterViewsQuiet() {
        val key = AccessCodeNotificationGate.deliveryKey(
            CourierSignals.WOLT_PACKAGE,
            "mindaugo g 1a",
            "Mindaugo gatvė 1A-9, Vilnius",
        )

        AccessCodeNotificationGate.consume(context, key, now = 10_000L)
        assertFalse(AccessCodeNotificationGate.claim(context, key, now = 20_000L))
    }

    @Test
    fun differentExplicitApartmentAtSameBuildingGetsDifferentDeliveryKey() {
        val first = AccessCodeNotificationGate.deliveryKey(
            CourierSignals.BOLT_PACKAGE,
            "zirmunu g 23",
            "Žirmūnų g. 23, Vilnius",
            unitHint = "145",
        )
        val second = AccessCodeNotificationGate.deliveryKey(
            CourierSignals.BOLT_PACKAGE,
            "zirmunu g 23",
            "Žirmūnų g. 23, Vilnius",
            unitHint = "146",
        )

        assertNotEquals(first, second)
        assertTrue(AccessCodeNotificationGate.claim(context, first, now = 10_000L))
        assertTrue(AccessCodeNotificationGate.claim(context, second, now = 20_000L))
    }
}
