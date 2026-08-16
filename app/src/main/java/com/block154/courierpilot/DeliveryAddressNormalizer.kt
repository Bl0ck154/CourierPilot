package com.block154.courierpilot

/**
 * Conservative canonicalization for delivery addresses before they enter local memory.
 *
 * Courier apps and customers use several visually similar dash characters for apartment suffixes.
 * CourierSignals already knows how to strip the common ASCII `house-apartment` form; this adapter
 * first folds Unicode punctuation and a couple of explicit apartment-label variants into that form.
 * Lines without a street marker + house number are intentionally left unresolved rather than guessed.
 */
internal object DeliveryAddressNormalizer {
    private val apartmentLabelAfterHouse = Regex(
        "(?i)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]\\s*(?:butas|but\\.?|apt\\.?|apartment)\\s*#?\\s*\\d{1,4}\\b.*$"
    )
    private val apartmentNumberThenLabel = Regex(
        "(?i)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]\\s*\\d{1,4}\\s*(?:butas|but\\.?|apt\\.?|apartment)\\b.*$"
    )

    fun normalize(raw: String): Pair<String, String>? {
        var cleaned = raw
            .replace('\u00A0', ' ')
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

        cleaned = apartmentLabelAfterHouse.replace(cleaned) { match -> match.groupValues[1] }
        cleaned = apartmentNumberThenLabel.replace(cleaned) { match -> match.groupValues[1] }
        return CourierSignals.normalizeBuildingAddress(cleaned)
    }

    fun key(raw: String): String? = normalize(raw)?.first

    fun display(raw: String): String? = normalize(raw)?.second
}
