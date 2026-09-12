package com.block154.courierpilot

import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AccessHintIntelligenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ArrivalAccessHintMonitor.cancelAll(context)
        listOf(
            "courierpilot_pending_arrival_hint_v1",
            "courierpilot_access_hint_feedback_v1",
            "courierpilot_learned_entrances_v1",
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After
    fun tearDown() {
        ArrivalAccessHintMonitor.cancelAll(context)
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
            fallbackNotifyAt = now + 420_000L,
            fallbackTimingSource = "delivery-screen-range",
        )
        PendingArrivalReminderStore.save(context, reminder)
        val restored = PendingArrivalReminderStore.load(context, now + 1_000L)
        assertNotNull(restored)
        assertEquals("delivery", restored!!.deliveryKey)
        assertEquals(listOf("1234"), restored.suggestion.codes)
        assertEquals(54.68, restored.destination!!.latitude, 0.000001)
        assertEquals(now + 420_000L, restored.fallbackNotifyAt)
        assertEquals("delivery-screen-range", restored.fallbackTimingSource)

        assertNull(
            PendingArrivalReminderStore.load(
                context,
                now + ArrivalAccessHintPolicy.REMINDER_TTL_MS + 1,
            )
        )
    }

    @Test
    fun restoredReminderWaitsForSameLiveDeliveryIdentityBeforeActivation() {
        val now = System.currentTimeMillis()
        val building = "restore-building-${System.nanoTime()}"
        val suggestion = AccessCodeSuggestion("Test g. 5", listOf("5555"), "Wolt", now)
        CourierMetaDatabase.get(context).saveAccessCode(
            AccessCodeObservation(building, suggestion.displayAddress, "5555"),
            platform = "Wolt",
            now = now,
        )
        PendingArrivalReminderStore.save(
            context,
            PendingArrivalReminder(
                deliveryKey = "delivery-restored",
                buildingKey = building,
                suggestion = suggestion,
                armedAt = now,
                destination = RoutePoint(54.68, 25.27),
            )
        )

        ArrivalAccessHintMonitor.restore(context)

        assertTrue(ArrivalAccessHintMonitor.awaitingLiveDeliveryConfirmation())
        assertEquals("delivery-restored", PendingArrivalReminderStore.load(context)?.deliveryKey)

        ArrivalAccessHintMonitor.arm(
            context,
            deliveryKey = "delivery-restored",
            buildingKey = building,
            suggestion = suggestion.copy(updatedAt = now + 1_000L),
        )

        assertFalse(ArrivalAccessHintMonitor.awaitingLiveDeliveryConfirmation())
        val resumed = PendingArrivalReminderStore.load(context)
        assertNotNull(resumed)
        assertEquals("delivery-restored", resumed!!.deliveryKey)
        assertEquals(now, resumed.armedAt)
        assertEquals(54.68, resumed.destination!!.latitude, 0.000001)
    }

    @Test
    fun restoreDropsCodeRejectedAfterReminderWasPersisted() {
        val now = System.currentTimeMillis()
        val building = "restore-rejected-${System.nanoTime()}"
        val suggestion = AccessCodeSuggestion("Test g. 6", listOf("6666"), "Bolt", now)
        val database = CourierMetaDatabase.get(context)
        database.saveAccessCode(
            AccessCodeObservation(building, suggestion.displayAddress, "6666"),
            platform = "Bolt",
            now = now,
        )
        PendingArrivalReminderStore.save(
            context,
            PendingArrivalReminder(
                deliveryKey = "delivery-rejected",
                buildingKey = building,
                suggestion = suggestion,
                armedAt = now,
                destination = RoutePoint(54.68, 25.27),
            )
        )
        AccessHintFeedbackStore.markRejected(context, building, "6666", now + 1L)

        ArrivalAccessHintMonitor.restore(context)

        assertFalse(ArrivalAccessHintMonitor.awaitingLiveDeliveryConfirmation())
        assertNull(PendingArrivalReminderStore.load(context))
    }

    @Test
    fun armingDifferentDeliveryReplacesPersistedReminder() {
        val now = System.currentTimeMillis()
        seedLearnedEntrance("building-a", now)
        seedLearnedEntrance("building-b", now)

        ArrivalAccessHintMonitor.arm(
            context,
            deliveryKey = "delivery-a",
            buildingKey = "building-a",
            suggestion = AccessCodeSuggestion("Test g. 1", listOf("1111"), "Wolt", now),
        )
        assertEquals("delivery-a", PendingArrivalReminderStore.load(context)?.deliveryKey)

        ArrivalAccessHintMonitor.arm(
            context,
            deliveryKey = "delivery-b",
            buildingKey = "building-b",
            suggestion = AccessCodeSuggestion("Test g. 2", listOf("2222"), "Wolt", now + 1_000L),
        )

        val persisted = PendingArrivalReminderStore.load(context)
        assertNotNull(persisted)
        assertEquals("delivery-b", persisted!!.deliveryKey)
        assertEquals("building-b", persisted.buildingKey)
        assertEquals(listOf("2222"), persisted.suggestion.codes)
    }

    @Test
    fun changedVisibleDeliveryCancelsPersistedReminderButSameDeliveryKeepsIt() {
        val now = System.currentTimeMillis()
        seedLearnedEntrance("cancel-building", now)
        ArrivalAccessHintMonitor.arm(
            context,
            deliveryKey = "delivery-current",
            buildingKey = "cancel-building",
            suggestion = AccessCodeSuggestion("Test g. 3", listOf("3333"), "Bolt", now),
        )

        ArrivalAccessHintMonitor.cancelUnless(context, listOf("delivery-current"))
        assertEquals("delivery-current", PendingArrivalReminderStore.load(context)?.deliveryKey)

        ArrivalAccessHintMonitor.cancelUnless(context, listOf("delivery-next"))
        assertNull(PendingArrivalReminderStore.load(context))
    }

    @Test
    fun explicitCancellationClearsDurableReminder() {
        val now = System.currentTimeMillis()
        PendingArrivalReminderStore.save(
            context,
            PendingArrivalReminder(
                deliveryKey = "delivery",
                buildingKey = "building",
                suggestion = AccessCodeSuggestion("Test g. 4", listOf("4444"), "Wolt", now),
                armedAt = now,
                destination = RoutePoint(54.68, 25.27),
            )
        )
        assertNotNull(PendingArrivalReminderStore.load(context))

        ArrivalAccessHintMonitor.cancelAll(context)

        assertNull(PendingArrivalReminderStore.load(context))
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

    @Test
    fun worksFeedbackLearnsArrivalSnapshotRatherThanLaterButtonLocation() {
        val building = "feedback-arrival-building"
        val now = System.currentTimeMillis()
        val receiver = AccessCodeFeedbackReceiver()

        fun confirm(lat: Double, lon: Double, capturedAt: Long) {
            receiver.onReceive(
                context,
                Intent(context, AccessCodeFeedbackReceiver::class.java).apply {
                    action = AccessCodeFeedbackReceiver.ACTION_WORKS
                    putExtra(AccessCodeFeedbackReceiver.EXTRA_BUILDING_KEY, building)
                    putStringArrayListExtra(AccessCodeFeedbackReceiver.EXTRA_CODES, arrayListOf("4321"))
                    putExtra(AccessCodeFeedbackReceiver.EXTRA_ARRIVAL_LATITUDE, lat)
                    putExtra(AccessCodeFeedbackReceiver.EXTRA_ARRIVAL_LONGITUDE, lon)
                    putExtra(AccessCodeFeedbackReceiver.EXTRA_ARRIVAL_ACCURACY_METERS, 10f)
                    putExtra(AccessCodeFeedbackReceiver.EXTRA_ARRIVAL_FIX_AGE_MS, 1_000L)
                    putExtra(AccessCodeFeedbackReceiver.EXTRA_ARRIVAL_CAPTURED_AT, capturedAt)
                },
            )
        }

        confirm(54.68100, 25.27100, now)
        confirm(54.68104, 25.27104, now + 1_000L)

        assertEquals(2, LearnedEntranceStore.sampleCount(context, building, now + 2_000L))
        val preferred = LearnedEntranceStore.preferred(context, building, now + 2_000L)
        assertNotNull(preferred)
        assertTrue(
            ArrivalAccessHintPolicy.distanceMeters(
                preferred!!,
                RoutePoint(54.68102, 25.27102),
            ) < 10.0,
        )
    }

    @Test
    fun worksFeedbackWithoutArrivalSnapshotDoesNotInventEntranceSample() {
        val building = "feedback-no-snapshot"
        AccessCodeFeedbackReceiver().onReceive(
            context,
            Intent(context, AccessCodeFeedbackReceiver::class.java).apply {
                action = AccessCodeFeedbackReceiver.ACTION_WORKS
                putExtra(AccessCodeFeedbackReceiver.EXTRA_BUILDING_KEY, building)
                putStringArrayListExtra(AccessCodeFeedbackReceiver.EXTRA_CODES, arrayListOf("1111"))
            },
        )

        assertEquals(0, LearnedEntranceStore.sampleCount(context, building))
    }

    private fun seedLearnedEntrance(buildingKey: String, now: Long) {
        LearnedEntranceStore.record(
            context,
            buildingKey,
            CurrentLocationFix(RoutePoint(54.68000, 25.27000), 10f, 1_000L, "test"),
            now - 2_000L,
        )
        LearnedEntranceStore.record(
            context,
            buildingKey,
            CurrentLocationFix(RoutePoint(54.68003, 25.27003), 10f, 1_000L, "test"),
            now - 1_000L,
        )
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
