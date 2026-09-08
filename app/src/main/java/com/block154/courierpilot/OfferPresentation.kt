package com.block154.courierpilot

import java.util.Locale

/** Small, non-destructive cleanup for history/detail labels. Stored raw text stays untouched. */
internal object OfferPresentation {
    fun merchantTitle(record: OfferRecord): String {
        val candidates = (record.merchantNames + listOfNotNull(record.restaurant))
            .map(::cleanMerchant)
            .filter(::isCredibleMerchant)
            .filterNot { record.packageName == CourierSignals.WOLT_PACKAGE && WoltOfferUiText.isMerchantUiNoise(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val first = candidates.firstOrNull()?.let { stripLeakedBuildingSuffixPrefix(it, record.dropoffAddresses) }
        if (first != null) return first

        val pickup = record.pickupAddresses.firstOrNull()?.trim().orEmpty()
        return if (pickup.isNotBlank()) "Pickup · $pickup" else record.platform
    }

    fun cleanMerchant(value: String): String = value
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        // OCR/Accessibility occasionally glues Lithuanian street marker to the branch name:
        // `Vokiečiųg.` -> `Vokiečių g.`. Restrict this to a marker followed by punctuation/number.
        .replace(Regex("(?iu)(?<=\\p{L})(g\\.)(?=\\s*[,)]|\\s*\\d)"), " $1")
        // A corrupted second OCR candidate was historically joined into restaurant text as
        // `Holy Donut (...), 8 min`. It is UI metadata, not part of the venue name.
        .replace(Regex("(?iu),\\s*\\d+(?:[.,]\\d+)?\\s*(?:min|km|m)?\\s*$"), "")
        .trim(' ', ',')

    private fun isCredibleMerchant(value: String): Boolean {
        if (value.length < 2) return false
        if (value.firstOrNull()?.isDigit() == true) return false
        if (looksLikeAddress(value)) return false
        if (Regex("^[A-Za-zĄČĘĖĮŠŲŪŽąčęėįšųūž]$").matches(value)) return false
        return true
    }

    private fun stripLeakedBuildingSuffixPrefix(value: String, dropoffs: List<String>): String {
        val match = Regex("^([A-Za-zĄČĘĖĮŠŲŪŽąčęėįšųūž])\\s+(.+)$").matchEntire(value) ?: return value
        val token = match.groupValues[1]
        val remainder = match.groupValues[2]
        if (remainder.length < 4) return value
        val suffix = Regex("(?iu)\\b\\d+\\s*${Regex.escape(token)}\\b")
        return if (dropoffs.any { suffix.containsMatchIn(it) }) remainder else value
    }

    private fun looksLikeAddress(value: String): Boolean {
        if (!Regex("\\d").containsMatchIn(value)) return false
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains(" gatv") || lower.contains("vilnius") || lower.contains("lt-") ||
            Regex("(?i)\\b(?:g|pr|pl|al|skg)\\.\\s*\\d").containsMatchIn(value) ||
            Regex("(?i)\\bstr\\.?\\s*\\d").containsMatchIn(value)
    }
}
