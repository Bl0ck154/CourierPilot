package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStructuralFallbackTest {
    @Test
    fun highConfidenceWoltStructure_canBootstrapWithoutText() {
        val structure = interactiveStructure(CourierSignals.WOLT_PACKAGE)
        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "",
            actionLabels = emptyList(),
        )
        assertTrue(decision.isOffer)
        assertTrue("wolt_structural_fallback" in decision.reasons)
    }

    @Test
    fun sameHighConfidenceBoltStructure_doesNotBootstrapWithoutExplicitOrLearnedEvidence() {
        val structure = interactiveStructure(CourierSignals.BOLT_PACKAGE)
        val decision = NotificationOfferClassifier.classify(
            structure = structure,
            text = "",
            actionLabels = emptyList(),
        )
        assertFalse(decision.isOffer)
    }

    @Test
    fun weakWoltStructure_doesNotBootstrap() {
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

    private fun interactiveStructure(packageName: String) = NotificationStructure(
        packageName = packageName,
        flags = 0,
        contentIntentPresent = true,
        contentIntentCreatorPackage = packageName,
        contentIntentKind = PendingIntentKind.ACTIVITY,
        actionCount = 2,
        actionIntentCount = 2,
        sameCreatorActionIntentCount = 2,
    )
}
