package com.block154.courierpilot

import com.google.mlkit.vision.text.Text

/**
 * Builds parser input from OCR without letting Bolt's live map labels leak into the offer card.
 *
 * Bolt renders the actionable offer as a bottom sheet over a live map, and the sheet height changes
 * with stacked orders. A fixed 60% crop was therefore too brittle: sometimes it chopped off the
 * venue title, and when no line survived it fell back to full-screen OCR, which could archive map
 * labels or an unrelated € account total as the offer price.
 *
 * The current path anchors on the lowest plausible € line in the lower part of the screen and keeps
 * the text neighbourhood above it. If no lower-card price anchor exists, Bolt returns no OCR text and
 * the capture pipeline retries instead of trusting the whole screen.
 */
internal object OfferOcrText {
    private const val BOLT_PRICE_SEARCH_TOP_FRACTION = 0.35
    private const val BOLT_MIN_CARD_TOP_FRACTION = 0.30
    private const val BOLT_ADDRESS_LOOKBACK_FRACTION = 0.36
    private const val BOLT_TITLE_MARGIN_FRACTION = 0.14
    private const val BOLT_NO_ADDRESS_LOOKBACK_FRACTION = 0.28
    private const val BOLT_AFTER_PRICE_FRACTION = 0.12

    private val priceRegex = Regex(
        "(?i)(?:€\\s*|EUR\\s*)(\\d+(?:[.,]\\d{1,2})?)|(\\d+(?:[.,]\\d{1,2})?)\\s*(?:€|EUR)"
    )

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

        val allLines = ocr.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                val value = line.text.trim().replace(Regex("\\s+"), " ")
                if (value.isBlank()) null
                else CardLine(bounds.top, bounds.bottom, bounds.left, bounds.centerY(), value)
            }
            .sortedWith(compareBy<CardLine> { it.top }.thenBy { it.left })

        if (allLines.isEmpty()) return ""

        val lowerSearchTop = (imageHeight * BOLT_PRICE_SEARCH_TOP_FRACTION).toInt()
        val priceAnchor = allLines
            .filter { it.centerY >= lowerSearchTop && priceRegex.containsMatchIn(it.text) }
            .maxByOrNull { it.centerY }
            ?: return ""

        val addressLookbackTop = priceAnchor.centerY - (imageHeight * BOLT_ADDRESS_LOOKBACK_FRACTION).toInt()
        val addressAnchors = allLines.filter { line ->
            line.centerY in addressLookbackTop..(priceAnchor.centerY + (imageHeight * 0.05).toInt()) &&
                BoltOfferTextSanitizer.looksLikeAddress(line.text)
        }

        val minimumTop = (imageHeight * BOLT_MIN_CARD_TOP_FRACTION).toInt()
        val cardTop = if (addressAnchors.isNotEmpty()) {
            val firstAddressTop = addressAnchors.minOf { it.top }
            maxOf(minimumTop, firstAddressTop - (imageHeight * BOLT_TITLE_MARGIN_FRACTION).toInt())
        } else {
            maxOf(minimumTop, priceAnchor.top - (imageHeight * BOLT_NO_ADDRESS_LOOKBACK_FRACTION).toInt())
        }
        val cardBottom = (priceAnchor.bottom + imageHeight * BOLT_AFTER_PRICE_FRACTION).toInt()
            .coerceAtMost(imageHeight)

        val cardLines = allLines
            .filter { it.centerY in cardTop..cardBottom }
            .map { BoltOfferTextSanitizer.sanitizeCardLine(it.text) }
            .filterNot(BoltOfferTextSanitizer::isOrphanBranchFragment)
            .distinct()

        // Never fall back to full-screen OCR for Bolt. A blank result is safer: the accessibility
        // service will retry while the live offer remains visible.
        return cardLines.joinToString("\n")
    }

    private data class CardLine(
        val top: Int,
        val bottom: Int,
        val left: Int,
        val centerY: Int,
        val text: String,
    )
}
