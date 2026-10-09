package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A user-hidden WS-A session still owns the Bolt screen: passive discovery must not re-arm it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveAdvisorHiddenCaptureGateTest {
    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    @Test
    fun currentTrackedOfferScreenStaysTrueWhenOverlayIsUserHidden() {
        val service = Robolectric.buildService(TestService::class.java).create().get()
        LiveAdvisorHub.attach(service)
        val advisorField = LiveAdvisorHub.javaClass.getDeclaredField("advisor").apply {
            isAccessible = true
        }
        val advisor = advisorField.get(LiveAdvisorHub) as StableLiveOfferAdvisor
        fun set(name: String, value: Any?) {
            StableLiveOfferAdvisor::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(advisor, value)
            }
        }
        val parsed = ParsedOffer(
            priceCents = 241,
            restaurant = "Casa Della Pasta",
            distanceMeters = null,
            pickupAddresses = listOf("Vokiečių gatvė 13, Vilnius"),
        )
        set("currentParsed", parsed)
        set("expectedPackageName", CourierSignals.BOLT_PACKAGE)
        set("userHidden", true)
        set("dismissed", false)
        assertTrue(advisor.isUserHidden())
        assertTrue(advisor.isTrackingOffer(CourierSignals.BOLT_PACKAGE))
        assertTrue(LiveAdvisorHub.isCurrentTrackedOfferScreen(CourierSignals.BOLT_PACKAGE, parsed))
        assertTrue(LiveAdvisorHub.isCurrentTrackedOfferScreen(
            CourierSignals.BOLT_PACKAGE,
            parsed.copy(restaurant = "Casa Della Pasta (Vokiečių str.)"),
        ))
        // This is the same gate used by attemptCapture before passive Bolt discovery OCR.
        advisor.destroy()
    }
}
