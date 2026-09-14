package com.block154.courierpilot

import java.util.Locale

/** Repairs OCR/accessibility index glyphs that leak in front of Wolt venue names. */
internal object WoltMerchantLabelRepair {
    fun repair(value: String): String {
        val clean = value
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        val match = LEADING_INDEX.matchEntire(clean) ?: return clean
        val remainder = match.groupValues[1].trim()
        if (remainder.length < 2) return clean
        if (WoltOfferUiText.isMerchantUiNoise(remainder)) return clean
        if (looksLikeAddress(remainder)) return clean
        return remainder
    }

    private fun looksLikeAddress(value: String): Boolean {
        if (!Regex("\\d").containsMatchIn(value)) return false
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains(" gatv") || lower.contains("vilnius") || lower.contains("lt-") ||
            Regex("(?iu)\\b(?:g|pr|pl|al|skg)\\.\\s*\\d").containsMatchIn(value) ||
            Regex("(?iu)\\bstr\\.?\\s*\\d").containsMatchIn(value)
    }

    // Current history presentation already rejects digit-leading merchant labels as corrupt.
    // Salvage the useful suffix instead of degrading a real venue to "Venue unknown".
    private val LEADING_INDEX = Regex("(?iu)^\\d{1,2}\\s+(\\p{L}.*)$")
}
