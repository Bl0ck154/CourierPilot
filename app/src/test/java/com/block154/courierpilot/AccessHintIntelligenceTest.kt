package com.block154.courierpilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessHintIntelligenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(
            "courierpilot_pending_arrival_hint_v1",
            "courierpilot_access_hint_feedback_v1",
            "courierpilot_learned_entrances_v1",
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun pendingReminderSurvivesStoreRoundTripAndExpires() {
        val now = 1_000_000L
        val reminder = PendingArrivalReminder(
            deliveryKey = "delivery",
            buildingKey = "building",
            suggestion = AccessCodeSuggestion("Test g. 1", listOf("1234"), "Wolt", now),
            armedAt = now,
            destination = RoutePoint(54.68, 25.27),
        )
        PendingArrivalReminderStore.save(context, reminder)
        val restored = PendingArrivalReminderStore.load(context, now + 1_000L)
        assertNotNull(restored)
        assertEquals("delivery", restored!!.deliveryKey)
        assertEquals(listOf("1234"), restored.suggestion.codes)
        assertEquals(54.68, restored.destination!!.latitude, 0.000001)

        assertNull(
            PendingArrivalReminderStore.load(
                context,
                now + ArrivalAccessHintPolicy.REMINDER_TTL_MS + 1,
            )
        )
    }

    @Test
    fun rejectionSuppressesUntilFreshLiveEvidenceReappears() {
        val now = System.currentTimeMillis()
        val record = AccessCodeRecord(
            id = 1,
            buildingKey = "test-building",
            displayAddress = "Test g. 1",
            code = "12 34",
            platform = "Wolt",
            firstSeenAt = now - 1_000L,
            lastSeenAt = now - 1_000L,
            seenCount = 1,
        )
        assertTrue(AccessHintFeedbackStore.shouldSurface(context, record, now))
        AccessHintFeedbackStore.markRejected(context, record.buildingKey, record.code, now)
        assertFalse(AccessHintFeedbackStore.shouldSurface(context, record, now + 1L))

        AccessHintFeedbackStore.markObserved(context, record.buildingKey, record.code, now + 2L)
        assertTrue(AccessHintFeedbackStore.shouldSurface(context, record.copy(lastSeenAt = now + 2L), now + 3L))
    }

    @Test
    fun staleSingleObservationNeedsConfirmation() {
        val now = System.currentTimeMillis()
        val stale = AccessCodeRecord(
            id = 2,
            buildingKey = "old-building",
            displayAddress = "Old g. 2",
            code = "9999",
            platform = "Bolt",
            firstSeenAt = now - 250L * DAY_MS,
            lastSeenAt = now - 250L * DAY_MS,
            seenCount = 1,
        )
        assertFalse(AccessHintFeedbackStore.shouldSurface(context, stale, now))
        AccessHintFeedbackStore.markConfirmed(context, stale.buildingKey, stale.code, now)
        assertTrue(AccessHintFeedbackStore.shouldSurface(context, stale, now + 1L))
    }

    @Test
    fun learnedEntranceRequiresRepeatedCloseConfirmedSamples() {
        val now = System.currentTimeMillis()
        val building = "entrance-building"
        LearnedEntranceStore.record(
            context,
            building,
            CurrentLocationFix(RoutePoint(54.68000, 25.27000), 12f, 1_000L, "test"),
            now,
        )
        assertNull(LearnedEntranceStore.preferred(context, building, now))

        LearnedEntranceStore.record(
            context,
            building,
            CurrentLocationFix(RoutePoint(54.68005, 25.27005), 10f, 1_000L, "test"),
            now + 1_000L,
        )
        val preferred = LearnedEntranceStore.preferred(context, building, now + 2_000L)
        assertNotNull(preferred)
        assertTrue(ArrivalAccessHintPolicy.distanceMeters(preferred!!, RoutePoint(54.680025, 25.270025)) < 15.0)
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
