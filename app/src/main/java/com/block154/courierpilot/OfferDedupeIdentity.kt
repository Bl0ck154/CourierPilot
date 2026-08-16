package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Stable identities for one live courier offer.
 *
 * Accessibility/OCR may expose route details progressively, so exact full-screen fingerprints are
 * deliberately not used as the last persistence guard. A same-live-offer comparison is intentionally
 * short-lived and requires price plus at least one secondary route signal (venue, distance or count).
 */
internal object OfferDedupeIdentity {
    const val BURST_WINDOW_MS = 90L * 1000L
    const val PERSIST_DEDUPE_WINDOW_MS = 3L * 60L * 1000L
    private const val DISTANCE_TOLERANCE_METERS = 150

    fun burstFingerprint(packageName: String, parsed: ParsedOffer): String {
        val merchants = merchantTokens(parsed)
            .sorted()
            .joinToString(";")
        return buildString {
            append(packageName)
            append("|p=").append(parsed.priceCents ?: -1)
            append("|d=").append(parsed.distanceMeters ?: -1)
            append("|n=").append(parsed.deliveryCount ?: -1)
            append("|m=").append(merchants)
        }
    }

    fun burstFingerprint(record: OfferRecord): String =
        burstFingerprint(record.packageName, record.asParsedOffer())

    /**
     * Fuzzy guard for duplicate captures of the same live offer. This is only valid inside a short
     * persistence window; outside that window two genuinely different orders may naturally have the
     * same price/restaurant/distance.
     */
    fun isSameLiveOffer(first: OfferRecord, second: OfferRecord): Boolean {
        if (first.packageName != second.packageName) return false
        if (first.priceCents != second.priceCents) return false
        if (abs(first.capturedAt - second.capturedAt) > PERSIST_DEDUPE_WINDOW_MS) return false

        val firstDistance = first.distanceMeters
        val secondDistance = second.distanceMeters
        val distanceMatches = firstDistance != null && secondDistance != null &&
            abs(firstDistance - secondDistance) <= DISTANCE_TOLERANCE_METERS
        if (firstDistance != null && secondDistance != null && !distanceMatches) return false

        val firstCount = first.deliveryCount
        val secondCount = second.deliveryCount
        val countMatches = firstCount != null && secondCount != null && firstCount == secondCount
        if (firstCount != null && secondCount != null && !countMatches) return false

        val firstMerchants = merchantTokens(first.asParsedOffer())
        val secondMerchants = merchantTokens(second.asParsedOffer())
        val venueMatches = firstMerchants.isNotEmpty() && secondMerchants.isNotEmpty() &&
            firstMerchants.any { left -> secondMerchants.any { right -> tokenMatches(left, right) } }
        if (firstMerchants.isNotEmpty() && secondMerchants.isNotEmpty() && !venueMatches) return false

        val addressMatches = addressTokens(first).let { left ->
            left.isNotEmpty() && addressTokens(second).let { right ->
                right.isNotEmpty() && left.any(right::contains)
            }
        }

        // Delivery count alone is too weak (especially for single Bolt offers). Require a venue,
        // route distance or canonical building address in addition to the equal price/time window.
        return distanceMatches || venueMatches || addressMatches
    }

    private fun OfferRecord.asParsedOffer(): ParsedOffer = ParsedOffer(
        priceCents = priceCents,
        distanceMeters = distanceMeters,
        restaurant = restaurant,
        merchantNames = merchantNames,
        pickupAddresses = pickupAddresses,
        customerNames = customerNames,
        dropoffAddresses = dropoffAddresses,
        deliveryCount = deliveryCount,
        estimatedMinutesMin = estimatedMinutesMin,
        estimatedMinutesMax = estimatedMinutesMax,
    )

    private fun merchantTokens(parsed: ParsedOffer): List<String> =
        (parsed.merchantNames.ifEmpty { listOfNotNull(parsed.restaurant) })
            .map(::identityToken)
            .filter(String::isNotEmpty)
            .distinct()

    private fun addressTokens(record: OfferRecord): Set<String> =
        (record.pickupAddresses + record.dropoffAddresses)
            .mapNotNull { CourierSignals.normalizeBuildingAddress(it)?.first }
            .toSet()

    private fun tokenMatches(left: String, right: String): Boolean =
        left == right || (left.length >= 5 && right.length >= 5 && (left.contains(right) || right.contains(left)))

    private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
