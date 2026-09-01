package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStructuralFallbackTest {
    @Test
    fun highConfidenceWoltStructure_canBootstrapWithoutText() {
        val structure = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
            actionCount = 2,
            actionIntentCount = 2,
            sameCreatorActionIntentCount = 2,
        )
        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "",
            actionLabels = emptyList(),
        )
        assertTrue(decision.isOffer)
    }

    @Test
    fun weakStructure_doesNotBootstrap() {
        val structure = NotificationStructure(
            packageName = CourierSignals.WOLT_PACKAGE,
            flags = 0,
            contentIntentPresent = true,
            contentIntentCreatorPackage = CourierSignals.WOLT_PACKAGE,
            contentIntentKind = PendingIntentKind.ACTIVITY,
        )
        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "",
            actionLabels = emptyList(),
        )
        assertFalse(decision.isOffer)
    }
}
