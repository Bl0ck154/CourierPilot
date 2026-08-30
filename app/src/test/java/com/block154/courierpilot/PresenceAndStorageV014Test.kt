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
    fun legacyPersistentNotificationOnlineStateSelfHealsToUnknown() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("courierpilot_presence", 0)
        prefs.edit()
            .clear()
            .putString("bolt_state", PresenceSignal.ONLINE.name)
            .putString("bolt_source", "persistent notification")
            .putStringSet("active_platforms", emptySet())
            .commit()

        val presence = CourierPresence.platformPresence(context, CourierSignals.BOLT_PACKAGE)

        assertEquals(PresenceSignal.UNKNOWN, presence.state)
        assertEquals("legacy persistent notification discarded", presence.source)
    }

    @Test
    fun galleryScreenshotSavingIsOnByDefaultAndUserControlled() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courierpilot_capture_storage", 0).edit().clear().commit()

        assertTrue(CaptureStorageSettings.saveOfferScreenshots(context))
        CaptureStorageSettings.setSaveOfferScreenshots(context, false)
        assertFalse(CaptureStorageSettings.saveOfferScreenshots(context))
        CaptureStorageSettings.setSaveOfferScreenshots(context, true)
        assertTrue(CaptureStorageSettings.saveOfferScreenshots(context))
    }
    @Test
    fun staleOnlineSignalStopsInflatingWorkTime() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("courierpilot_presence", 0)
        prefs.edit().clear().commit()
        val database = CourierMetaDatabase.get(context)
        database.writableDatabase.delete("work_sessions", null, null)

        val start = 1_000_000L
        CourierPresence.markOfferOnline(context, CourierSignals.BOLT_PACKAGE, now = start)
        CourierPresence.reconcileStaleOnline(
            context,
            now = start + CourierPresence.ONLINE_SIGNAL_TTL_MS + 2L * 60L * 60L * 1000L,
        )

        val summary = database.workSummarySince(0L, now = start + 3L * 60L * 60L * 1000L)
        assertFalse(summary.active)
        assertEquals(CourierPresence.ONLINE_SIGNAL_TTL_MS, summary.totalMillis)
        assertEquals(PresenceSignal.UNKNOWN, CourierPresence.platformPresence(context, CourierSignals.BOLT_PACKAGE).state)
    }

    @Test
    fun freshOnlineSignalKeepsAutomaticSessionActive() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("courierpilot_presence", 0)
        prefs.edit().clear().commit()
        val database = CourierMetaDatabase.get(context)
        database.writableDatabase.delete("work_sessions", null, null)

        val start = System.currentTimeMillis()
        CourierPresence.markOfferOnline(context, CourierSignals.WOLT_PACKAGE, now = start)
        val afterTwentyMinutes = start + 20L * 60L * 1000L
        CourierPresence.reconcileStaleOnline(context, now = afterTwentyMinutes)

        val summary = database.workSummarySince(start, now = afterTwentyMinutes)
        assertTrue(summary.active)
        assertEquals(20L * 60L * 1000L, summary.totalMillis)
    }


    @Test
    fun nextOfferAfterLongGapStartsNewSessionInsteadOfBridgingIdleHours() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("courierpilot_presence", 0)
        prefs.edit().clear().commit()
        val database = CourierMetaDatabase.get(context)
        database.writableDatabase.delete("work_sessions", null, null)

        val start = 10_000_000L
        CourierPresence.markOfferOnline(context, CourierSignals.BOLT_PACKAGE, now = start)

        val secondOfferAt = start + 2L * 60L * 60L * 1000L
        CourierPresence.markOfferOnline(context, CourierSignals.BOLT_PACKAGE, now = secondOfferAt)

        val tenMinutesLater = secondOfferAt + 10L * 60L * 1000L
        val summary = database.workSummarySince(0L, now = tenMinutesLater)

        assertTrue(summary.active)
        assertEquals(2, summary.sessionCount)
        assertEquals(
            CourierPresence.ONLINE_SIGNAL_TTL_MS + 10L * 60L * 1000L,
            summary.totalMillis,
        )
    }


}
