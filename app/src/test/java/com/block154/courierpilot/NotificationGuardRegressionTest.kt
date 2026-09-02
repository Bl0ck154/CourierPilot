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
        assertTrue(decision.reasons.contains("bolt_learned_visible_text_guard"))
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
}
