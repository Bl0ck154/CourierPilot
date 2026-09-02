package com.block154.courierpilot

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationOfferClassifierTest {

    @Test
    fun unknownTwoActionStructureDoesNotAutoOpenBeforeProfileIsLearned() {
        val structure = NotificationStructure(
            packageName = CourierSignals.BOLT_PACKAGE,
            channelId = "incoming_requests",
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.BOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            actionCount = 2,
            actionIntentCount = 2,
            sameCreatorActionIntentCount = 2,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "Visiškai naujas tekstas kurio mes niekada nematėme",
            actionLabels = listOf("Foo", "Bar"),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.score >= 8)
    }

    @Test
    fun ordinaryTransientCourierMessageWithOneActionIsNotOffer() {
        val structure = NotificationStructure(
            packageName = CourierSignals.BOLT_PACKAGE,
            channelId = "messages",
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.BOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            actionCount = 1,
            actionIntentCount = 1,
            sameCreatorActionIntentCount = 1,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "Customer sent you a message",
            actionLabels = listOf("Reply"),
        )

        assertFalse(decision.isOffer)
    }

    @Test
    fun learnedBoltProfileStillRecognizesTextlessOrderPush() {
        val learned = NotificationStructure(
            packageName = CourierSignals.BOLT_PACKAGE,
            channelId = "incoming_requests",
            category = Notification.CATEGORY_SERVICE,
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.BOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            actionCount = 0,
            actionIntentCount = 0,
            sameCreatorActionIntentCount = 0,
            extrasKeys = setOf("android.title", "android.text"),
            notificationId = 71,
        )
        val changed = learned.copy(
            extrasKeys = setOf("android.title", "android.text", "android.subText"),
            observedAt = learned.observedAt + 10_000L,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = changed,
            text = "",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertTrue(decision.learnedMatchScore >= NotificationOfferClassifier.LEARNED_PROFILE_MATCH_THRESHOLD)
        assertTrue(decision.isOffer)
    }

    @Test
    fun completedDeliveryTextOverridesEvenMatchingLearnedProfile() {
        val learned = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            channelId = "orders",
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            notificationId = 10,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = learned.copy(),
            text = "Order completed",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertFalse(decision.isOffer)
    }

    @Test
    fun onlinePresenceDoesNotBecomeOfferFromGenericActivityIntent() {
        val structure = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            channelId = "status",
            flags = Notification.FLAG_ONGOING_EVENT,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "You are online · waiting for orders",
            actionLabels = emptyList(),
        )

        assertFalse(decision.isOffer)
    }
    @Test
    fun learnedDefaultChannelDoesNotMatchUnrelatedNotificationByChannelAlone() {
        val learned = NotificationStructure(
            packageName = CourierSignals.BOLT_PACKAGE,
            channelId = "default",
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.BOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            actionCount = 0,
            notificationId = 71,
            extrasKeys = setOf("android.title", "android.text"),
        )
        val unrelated = learned.copy(
            notificationId = 99,
            extrasKeys = setOf("android.title", "android.text"),
        )

        val decision = NotificationOfferClassifier.classify(
            structure = unrelated,
            text = "Weekly courier news",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.learnedMatchScore < NotificationOfferClassifier.LEARNED_PROFILE_MATCH_THRESHOLD)
    }

    @Test
    fun woltStrongOfferTextBootstrapsWithoutBoltSpecificLogic() {
        val structure = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            channelId = "orders",
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            notificationId = 10,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "You have a new task",
            actionLabels = emptyList(),
        )

        assertTrue(decision.isOffer)
    }

    @Test
    fun learnedWoltProfileSurvivesWordingChange() {
        val learned = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            channelId = "orders",
            category = Notification.CATEGORY_SERVICE,
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            notificationId = 10,
            extrasKeys = setOf("android.title", "android.text"),
        )
        val changed = learned.copy(
            extrasKeys = setOf("android.title", "android.text", "android.subText"),
            observedAt = learned.observedAt + 5_000L,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = changed,
            text = "Visiškai pakeistas Wolt tekstas",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertTrue(decision.learnedMatchScore >= NotificationOfferClassifier.LEARNED_PROFILE_MATCH_THRESHOLD)
        assertTrue(decision.isOffer)
    }

    @Test
    fun boltLearnedProfileNeverCrossMatchesWolt() {
        val bolt = NotificationStructure(
            packageName = CourierSignals.BOLT_PACKAGE,
            channelId = "orders",
            category = Notification.CATEGORY_SERVICE,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.BOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            notificationId = 10,
            extrasKeys = setOf("android.title", "android.text"),
        )
        val wolt = bolt.copy(
            packageName = CourierSignals.WOLT_PACKAGE,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
        )

        val decision = NotificationOfferClassifier.classify(
            structure = wolt,
            text = "Unknown wording",
            actionLabels = emptyList(),
            learnedProfiles = listOf(bolt),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.learnedMatchScore == 0)
    }


    @Test
    fun boltReadyNotificationHardRejectsEvenMatchingLearnedOfferProfile() {
        val learned = NotificationStructure(
            packageName = CourierSignals.BOLT_PACKAGE,
            channelId = "orders",
            category = Notification.CATEGORY_SERVICE,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.BOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            notificationId = 71,
        )
        val decision = NotificationOfferClassifier.classify(
            structure = learned.copy(),
            text = "Order is ready for pickup",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )
        assertFalse(decision.isOffer)
    }

}
