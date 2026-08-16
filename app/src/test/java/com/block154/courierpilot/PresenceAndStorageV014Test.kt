package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PresenceAndStorageV014Test {

    @Test
    fun backgroundServiceNotificationIsNotOnlineEvidence() {
        assertEquals(PresenceSignal.UNKNOWN, CourierSignals.detectPresence("Courier app is running"))
        assertEquals(PresenceSignal.UNKNOWN, CourierSignals.detectPresence("Keep you active while app is in background"))
    }

    @Test
    fun explicitOnlineAndOfflineTextRemainStrongSignals() {
        assertEquals(PresenceSignal.ONLINE, CourierSignals.detectPresence("Waiting for orders"))
        assertEquals(PresenceSignal.ONLINE, CourierSignals.detectPresence("You're online"))
        assertEquals(PresenceSignal.OFFLINE, CourierSignals.detectPresence("Go online"))
        assertEquals(PresenceSignal.OFFLINE, CourierSignals.detectPresence("Start delivering"))
    }

    @Test
    fun galleryScreenshotSavingIsOffByDefaultAndUserControlled() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courierpilot_capture_storage", 0).edit().clear().commit()

        assertFalse(CaptureStorageSettings.saveOfferScreenshots(context))
        CaptureStorageSettings.setSaveOfferScreenshots(context, true)
        assertTrue(CaptureStorageSettings.saveOfferScreenshots(context))
        CaptureStorageSettings.setSaveOfferScreenshots(context, false)
        assertFalse(CaptureStorageSettings.saveOfferScreenshots(context))
    }
}
