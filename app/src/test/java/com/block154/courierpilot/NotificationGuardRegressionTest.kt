package com.block154.courierpilot

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGuardRegressionTest {

    private fun learnedBoltProfile() = NotificationStructure(
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
        notificationId = 71,
        extrasKeys = setOf("android.title", "android.text"),
    )

    @Test
    fun learnedBoltProfileCannotAutoOpenArbitraryVisibleCopy() {
        val learned = learnedBoltProfile()
        val decision = NotificationOfferClassifier.classify(
            structure = learned.copy(),
            text = "Something new is waiting for couriers today",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertTrue(decision.learnedMatchScore >= NotificationOfferClassifier.LEARNED_PROFILE_MATCH_THRESHOLD)
        assertTrue(decision.reasons.contains("learned_profile_diagnostic_only"))
        assertFalse(decision.isOffer)
    }

    @Test
    fun explicitBoltNewRequestStillAutoOpens() {
        val learned = learnedBoltProfile()
        val decision = NotificationOfferClassifier.classify(
            structure = learned.copy(),
            text = "New request",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertTrue(decision.isOffer)
        assertTrue(decision.reasons.contains("strong_offer_text"))
    }

    @Test
    fun obviousBoltPromotionHardRejectsBeforeLearnedProfile() {
        val learned = learnedBoltProfile()
        val decision = NotificationOfferClassifier.classify(
            structure = learned.copy(),
            text = "Invite friends and earn more with our promotion",
            actionLabels = emptyList(),
            learnedProfiles = listOf(learned),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.reasons.contains("negative_delivery_state"))
    }

    @Test
    fun learnedWoltShapeCannotAutoOpenUnrelatedVisiblePush() {
        val learned = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            channelId = "orders",
            category = Notification.CATEGORY_SERVICE,
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            actionCount = 2,
            actionIntentCount = 2,
            sameCreatorActionIntentCount = 2,
            notificationId = 10,
        )
        val decision = NotificationOfferClassifier.classify(
            structure = learned.copy(),
            text = "Courier updates are available",
            actionLabels = listOf("Open", "Later"),
            learnedProfiles = listOf(learned),
        )

        assertTrue(decision.learnedMatchScore >= NotificationOfferClassifier.LEARNED_PROFILE_MATCH_THRESHOLD)
        assertFalse(decision.isOffer)
        assertTrue(decision.reasons.contains("learned_profile_diagnostic_only"))
    }

    @Test
    fun explicitAcceptDeclinePairCanIdentifyIncomingOrderWithoutKnownBodyText() {
        val decision = NotificationOfferClassifier.classify(
            structure = learnedBoltProfile().copy(
                actionCount = 2,
                actionIntentCount = 2,
                sameCreatorActionIntentCount = 2,
            ),
            text = "Completely changed incoming screen copy",
            actionLabels = listOf("Accept", "Decline"),
        )

        assertTrue(decision.isOffer)
        assertTrue(decision.reasons.contains("decision_pair"))
    }

    @Test
    fun singleAcceptActionIsNotEnoughToAutoOpen() {
        val decision = NotificationOfferClassifier.classify(
            structure = learnedBoltProfile().copy(
                actionCount = 1,
                actionIntentCount = 1,
                sameCreatorActionIntentCount = 1,
            ),
            text = "Courier update",
            actionLabels = listOf("Accept"),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.reasons.contains("single_decision_action"))
    }

    @Test
    fun ongoingNotificationNeverAutoOpensEvenWithNewOrderText() {
        val decision = NotificationOfferClassifier.classify(
            structure = learnedBoltProfile().copy(flags = Notification.FLAG_ONGOING_EVENT),
            text = "New request",
            actionLabels = emptyList(),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.reasons.contains("ongoing_guard"))
    }

    @Test
    fun boltAppIsRunningStatusIsHardBlockedEvenIfShapeLooksLikeAnOrder() {
        val decision = NotificationOfferClassifier.classify(
            structure = learnedBoltProfile().copy(
                flags = 0,
                actionCount = 2,
                actionIntentCount = 2,
                sameCreatorActionIntentCount = 2,
            ),
            text = "Bolt Courier app is running · We keep you active while app is in background",
            actionLabels = listOf("Accept", "Decline"),
            learnedProfiles = listOf(learnedBoltProfile()),
        )

        assertFalse(decision.isOffer)
        assertTrue(decision.reasons.contains("presence_notification"))
    }
}
