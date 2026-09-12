package com.block154.courierpilot

import java.util.Locale

/** Small, non-destructive cleanup for history/detail labels. Stored raw text stays untouched. */
internal object OfferPresentation {
    fun merchantTitle(record: OfferRecord): String {
        val candidates = (record.merchantNames + listOfNotNull(record.restaurant))
            .map(::cleanMerchant)
            .filter(::isCredibleMerchant)
            .filterNot(::isGenericMerchantArtifact)
            .filterNot(BoltOfferTextSanitizer::isOrphanBranchFragment)
            .filterNot { record.packageName == CourierSignals.WOLT_PACKAGE && WoltOfferUiText.isMerchantUiNoise(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val first = candidates.firstOrNull()?.let { stripLeakedBuildingSuffixPrefix(it, record.dropoffAddresses) }
        if (first != null) return first

        // Older Wolt captures sometimes retained the correct pickup address/raw screen but lost the
        // merchant name because Accessibility and OCR arrived in a different node order. Recover a
        // title only when it is locally anchored immediately before a pickup address that we already
        // trust. This is presentation-only: the saved raw text and captured amount stay untouched.
        recoverWoltMerchantNearPickup(record)?.let { return it }

        // Never put an address or the courier platform into the merchant-title slot. `Wolt` is
        // metadata, not a venue name, and older fallback behaviour made broken captures look valid.
        return "Venue unknown"
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

    private fun recoverWoltMerchantNearPickup(record: OfferRecord): String? {
        if (record.packageName != CourierSignals.WOLT_PACKAGE || record.rawText.isBlank()) return null
        val pickupKeys = record.pickupAddresses
            .mapNotNull(DeliveryAddressNormalizer::key)
            .toSet()
        if (pickupKeys.isEmpty()) return null

        val lines = record.rawText.lineSequence()
            .map(::cleanMerchant)
            .filter(String::isNotBlank)
            .toList()

        lines.forEachIndexed { addressIndex, line ->
            val key = DeliveryAddressNormalizer.key(line) ?: return@forEachIndexed
            if (key !in pickupKeys) return@forEachIndexed

            val start = (addressIndex - MERCHANT_RECOVERY_LOOKBACK_LINES).coerceAtLeast(0)
            for (candidateIndex in addressIndex - 1 downTo start) {
                val candidate = lines[candidateIndex]
                // Do not jump across another address into an unrelated map/card row.
                if (candidateIndex != addressIndex - 1 && looksLikeAddress(candidate)) break
                if (isRecoveryBoundary(candidate)) break
                if (!isRecoverableMerchant(candidate)) continue
                return stripLeakedBuildingSuffixPrefix(candidate, record.dropoffAddresses)
            }
        }
        return null
    }

    private fun isRecoverableMerchant(value: String): Boolean {
        val clean = cleanMerchant(value)
        if (!isCredibleMerchant(clean)) return false
        if (isGenericMerchantArtifact(clean)) return false
        if (BoltOfferTextSanitizer.isOrphanBranchFragment(clean)) return false
        if (WoltOfferUiText.isMerchantUiNoise(clean)) return false
        if (MarketCurrencyParser.containsMoney(clean)) return false
        if (WoltOfferUiText.modernRouteSummaryRegex.matches(clean)) return false
        if (WoltOfferUiText.collapsedMultipleDropoffsRegex.matches(clean)) return false
        if (WoltOfferUiText.standaloneMultipleDropoffsRegex.matches(clean)) return false
        if (WoltOfferUiText.singleCustomerDropoffRegex.matches(clean)) return false
        if (Regex("(?iu)^\\+?\\s*\\d+(?:[.,]\\d+)?\\s*(?:km|m|min|stops?)\\b.*$").matches(clean)) return false
        return true
    }

    private fun isGenericMerchantArtifact(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT).trim()
        if (lower in RECOVERY_UI_LINES) return true
        if (lower == "customer") return true
        if (lower.startsWith("pickup ·") || lower.startsWith("pickup -")) return true
        return false
    }

    private fun isRecoveryBoundary(value: String): Boolean {
        if (MarketCurrencyParser.containsMoney(value)) return true
        if (WoltOfferUiText.isEarningsLabel(value)) return true
        return false
    }

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

    private val RECOVERY_UI_LINES = setOf(
        "pickup", "dropoff", "customer drop-off", "multiple drop-offs", "collect cash",
        "accept", "decline", "reject", "ready", "show map", "done", "timeline",
        "route distance", "estimated", "delivery from",
        WoltOfferUiText.LEGACY_EARNINGS_LABEL,
        WoltOfferUiText.MODERN_EARNINGS_LABEL,
    )

    private const val MERCHANT_RECOVERY_LOOKBACK_LINES = 8
}
