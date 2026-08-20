package com.block154.courierpilot

import com.google.mlkit.vision.text.Text

/**
 * Builds parser input from OCR without letting Bolt's live map labels leak into the offer card.
 *
 * Bolt renders the actionable offer as a bottom sheet while the upper part of the screenshot is a
 * Mapbox map. OCR over the whole bitmap is still useful, but only lines physically inside the lower
 * sheet are allowed to become Bolt offer metadata. No assumptions are made about merchant-name
 * length or wording.
 */
internal object OfferOcrText {
    private const val BOLT_CARD_TOP_FRACTION = 0.60

    fun combine(
        packageName: String,
        accessibilityText: String,
        ocr: Text,
        imageHeight: Int,
    ): String {
        if (packageName != CourierSignals.BOLT_PACKAGE) {
            return listOf(accessibilityText.trim(), ocr.text.trim())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }

        val cutoff = (imageHeight * BOLT_CARD_TOP_FRACTION).toInt()
        val cardLines = ocr.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                if (bounds.centerY() < cutoff) return@mapNotNull null
                val value = line.text.trim().replace(Regex("\\s+"), " ")
                if (value.isBlank()) null else CardLine(bounds.top, bounds.left, value)
            }
            .sortedWith(compareBy<CardLine> { it.top }.thenBy { it.left })
            .map { it.text }
            .distinct()

        // ML Kit normally provides line bounds. Preserve a compatibility fallback for unusual OCR
        // implementations instead of dropping a real offer entirely.
        return cardLines.joinToString("\n").ifBlank { ocr.text.trim() }
    }

    private data class CardLine(
        val top: Int,
        val left: Int,
        val text: String,
    )
}
