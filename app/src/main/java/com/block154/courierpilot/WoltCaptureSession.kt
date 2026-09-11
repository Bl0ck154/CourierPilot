package com.block154.courierpilot

import android.graphics.Bitmap

/**
 * Mutable state scoped to one Wolt offer capture transaction.
 *
 * This class deliberately owns data, not capture policy. Accessibility probing, disclosure clicks,
 * OCR retries, route publication and persistence decisions remain in [OfferAccessibilityService].
 */
internal class WoltCaptureSession {
    var frameKey: String = ""
        private set
    var cardFrameText: String = ""
    var dropoffFrameText: String = ""
    var visibleBasePickupAddresses: List<String> = emptyList()
    var dropoffProbeKey: String = ""
    var dropoffProbeAttempts: Int = 0
    var dropoffSemanticProbeAttempts: Int = 0
    var dropoffResolvedKey: String = ""
    var dropoffResolvedCount: Int = 0
    var dropoffSheetSettleAttempts: Int = 0
    var routeOcrRecoveryAttempts: Int = 0
    var idleHomeKey: String = ""
    var idleHomeFirstSeenAtElapsed: Long = 0L
    var idleHomeChecks: Int = 0

    private var proofBitmap: Bitmap? = null
    private var proofOfferKey: String = ""

    fun offerKey(pending: PendingOffer): String =
        "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"

    /**
     * Preserve the historical split between Wolt's collapsed offer card and expanded drop-off
     * sheet. A new offer key clears every session counter and any frozen proof from the old offer.
     */
    fun accumulateFrame(pending: PendingOffer, currentText: String): String {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return currentText
        val key = offerKey(pending)
        ensureOffer(key)

        val clean = currentText.trim()
        if (clean.isNotBlank()) {
            when {
                WoltOfferUiText.hasExpandedMultipleDropoffSheet(clean) -> dropoffFrameText = clean
                WoltOfferUiText.hasModernOfferStructure(clean) -> cardFrameText = clean
            }
        }
        return mergedFrameText(currentText)
    }

    fun ensureOffer(key: String) {
        if (key == frameKey) return
        clearProofIfDifferent(key)
        frameKey = key
        cardFrameText = ""
        dropoffFrameText = ""
        visibleBasePickupAddresses = emptyList()
        dropoffProbeKey = key
        dropoffProbeAttempts = 0
        dropoffSemanticProbeAttempts = 0
        dropoffResolvedKey = ""
        dropoffResolvedCount = 0
        dropoffSheetSettleAttempts = 0
        routeOcrRecoveryAttempts = 0
        idleHomeKey = ""
        idleHomeFirstSeenAtElapsed = 0L
        idleHomeChecks = 0
    }

    fun mergedFrameText(fallback: String = ""): String {
        val frames = listOf(cardFrameText, dropoffFrameText)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        return if (frames.isEmpty()) fallback else frames.joinToString("\n")
    }

    fun stashProof(pending: PendingOffer, bitmap: Bitmap): Boolean {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return false
        val key = offerKey(pending)
        clearProofIfDifferent(key)
        if (proofBitmap != null) return false
        proofOfferKey = key
        proofBitmap = bitmap
        return true
    }

    fun takeProof(pending: PendingOffer): Bitmap? {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return null
        val key = offerKey(pending)
        if (proofOfferKey != key) {
            if (proofOfferKey.isNotBlank()) clearProof()
            return null
        }
        val bitmap = proofBitmap
        proofBitmap = null
        proofOfferKey = ""
        return bitmap
    }

    fun discardProof(pending: PendingOffer) {
        if (proofOfferKey == offerKey(pending)) clearProof()
    }

    fun clearProof() {
        proofBitmap?.let { if (!it.isRecycled) it.recycle() }
        proofBitmap = null
        proofOfferKey = ""
    }

    private fun clearProofIfDifferent(key: String) {
        if (proofOfferKey.isNotBlank() && proofOfferKey != key) clearProof()
    }
}
