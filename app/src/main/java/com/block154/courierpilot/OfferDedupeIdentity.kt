package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Stable identities for one live courier offer.
 *
 * Accessibility/OCR may expose route details progressively. Persistence therefore uses a tiered
 * duplicate guard: a very short sparse-frame fallback, the normal fuzzy route comparison, and a
 * longer window only for strong route identity / exact capture keys.
 */
internal object OfferDedupeIdentity {
    const val BURST_WINDOW_MS = 90L * 1000L
    const val PERSIST_DEDUPE_WINDOW_MS = 10L * 60L * 1000L
    private const val NORMAL_FUZZY_WINDOW_MS = 3L * 60L * 1000L
    private const val SPARSE_FRAME_WINDOW_MS = 45L * 1000L
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
     * Fuzzy guard for duplicate captures of the same live offer.
     *
     * Within 45 seconds, a sparse notification frame may legitimately contain only the price while
     * the later Accessibility frame contains the route. Contradictory route fields still reject the
     * match. From 3 to 10 minutes we require strong route identity so two genuine later offers from
     * the same venue are not collapsed merely because their price matches.
     */
    fun isSameLiveOffer(first: OfferRecord, second: OfferRecord): Boolean {
        if (first.packageName != second.packageName) return false
        if (first.priceCents != second.priceCents) return false
        val elapsed = abs(first.capturedAt - second.capturedAt)
        if (elapsed > PERSIST_DEDUPE_WINDOW_MS) return false

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

        val firstPickups = addressTokens(first.pickupAddresses)
        val secondPickups = addressTokens(second.pickupAddresses)
        val pickupMatches = overlaps(firstPickups, secondPickups)
        if (firstPickups.isNotEmpty() && secondPickups.isNotEmpty() && !pickupMatches) return false

        val firstDropoffs = addressTokens(first.dropoffAddresses)
        val secondDropoffs = addressTokens(second.dropoffAddresses)
        val dropoffMatches = overlaps(firstDropoffs, secondDropoffs)
        if (firstDropoffs.isNotEmpty() && secondDropoffs.isNotEmpty() && !dropoffMatches) return false

        val addressMatches = pickupMatches || dropoffMatches
        val firstHasRouteIdentity = firstDistance != null || firstMerchants.isNotEmpty() ||
            firstPickups.isNotEmpty() || firstDropoffs.isNotEmpty()
        val secondHasRouteIdentity = secondDistance != null || secondMerchants.isNotEmpty() ||
            secondPickups.isNotEmpty() || secondDropoffs.isNotEmpty()

        if (elapsed <= SPARSE_FRAME_WINDOW_MS && (!firstHasRouteIdentity || !secondHasRouteIdentity)) {
            return true
        }

        if (elapsed <= NORMAL_FUZZY_WINDOW_MS) {
            return distanceMatches || venueMatches || addressMatches
        }

        val countCompatible = firstCount == null || secondCount == null || countMatches
        if (firstDropoffs.isNotEmpty() && secondDropoffs.isNotEmpty()) {
            return dropoffMatches && countCompatible
        }

        return pickupMatches && venueMatches && distanceMatches && countCompatible
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

    private fun addressTokens(values: List<String>): Set<String> =
        values.mapNotNull { DeliveryAddressNormalizer.key(it) }.toSet()

    private fun overlaps(first: Set<String>, second: Set<String>): Boolean =
        first.isNotEmpty() && second.isNotEmpty() && first.any(second::contains)

    private fun tokenMatches(left: String, right: String): Boolean =
        left == right || (left.length >= 5 && right.length >= 5 && (left.contains(right) || right.contains(left)))

    private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
