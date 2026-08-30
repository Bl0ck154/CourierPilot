package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Stable identities for one live courier offer. */
internal object OfferDedupeIdentity {
    const val BURST_WINDOW_MS = 90L * 1000L
    const val PERSIST_DEDUPE_WINDOW_MS = 10L * 60L * 1000L
    private const val NORMAL_FUZZY_WINDOW_MS = 3L * 60L * 1000L
    private const val SPARSE_FRAME_WINDOW_MS = 45L * 1000L
    private const val BOLT_CARD_BURST_WINDOW_MS = 120L * 1000L
    private const val BOLT_VISUAL_BURST_WINDOW_MS = 120L * 1000L
    private const val BOLT_ETA_TOLERANCE_MINUTES = 2
    private const val DISTANCE_TOLERANCE_METERS = 150

    fun burstFingerprint(packageName: String, parsed: ParsedOffer): String {
        if (packageName == CourierSignals.BOLT_PACKAGE) {
            // Bolt OCR can see different map labels while the lower offer card has not changed.
            // Keep the burst identity tied to stable card fields, never to the merchant spelling.
            val addresses = addressTokens(parsed.pickupAddresses + parsed.dropoffAddresses)
                .sorted()
                .joinToString(";")
            return buildString {
                append(packageName)
                append("|p=").append(parsed.priceCents ?: -1)
                append("|n=").append(parsed.deliveryCount ?: -1)
                append("|e=").append(parsed.estimatedMinutesMin ?: -1)
                    .append('-').append(parsed.estimatedMinutesMax ?: -1)
                append("|a=").append(addresses)
            }
        }

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
     * A live Accessibility tree can briefly classify one address differently between frames. For
     * the first 90 seconds, a matching known drop-off is therefore allowed to prove identity before
     * pickup contradictions are considered. If both frames know different drop-offs we still keep
     * both offers. This is aimed at notification -> partial tree -> rich tree progression, not at
     * collapsing later genuine offers from the same venue.
     */
    fun isSameLiveOffer(first: OfferRecord, second: OfferRecord): Boolean {
        if (first.packageName != second.packageName) return false
        if (first.priceCents != second.priceCents) return false
        val elapsed = abs(first.capturedAt - second.capturedAt)
        if (elapsed > PERSIST_DEDUPE_WINDOW_MS) return false

        if (
            first.packageName == CourierSignals.BOLT_PACKAGE &&
            elapsed <= BOLT_VISUAL_BURST_WINDOW_MS &&
            first.visualFingerprint.isNotBlank() &&
            second.visualFingerprint.isNotBlank() &&
            OfferVisualFingerprint.isNear(first.visualFingerprint, second.visualFingerprint)
        ) {
            return true
        }

        // Bolt's bottom card can be captured more than once while OCR enriches the same screen. The
        // venue spelling and ETA may change frame-to-frame, so for a short live-card burst an exact
        // canonical address set + price is enough proof. If only part of the route is visible, fall
        // back to address overlap plus compatible ETA.
        if (first.packageName == CourierSignals.BOLT_PACKAGE && elapsed <= BOLT_CARD_BURST_WINDOW_MS) {
            val firstRouteAddresses = addressTokens(first.pickupAddresses + first.dropoffAddresses)
            val secondRouteAddresses = addressTokens(second.pickupAddresses + second.dropoffAddresses)
            val countCompatible = first.deliveryCount == null || second.deliveryCount == null ||
                first.deliveryCount == second.deliveryCount
            val exactAddressSet = firstRouteAddresses.isNotEmpty() && firstRouteAddresses == secondRouteAddresses
            if (exactAddressSet && countCompatible) return true
            if (
                overlaps(firstRouteAddresses, secondRouteAddresses) &&
                countCompatible &&
                boltEtaCompatible(first, second)
            ) {
                return true
            }
        }

        val firstDistance = first.distanceMeters
        val secondDistance = second.distanceMeters
        val distanceMatches = firstDistance != null && secondDistance != null &&
            abs(firstDistance - secondDistance) <= DISTANCE_TOLERANCE_METERS
        if (firstDistance != null && secondDistance != null && !distanceMatches) return false

        val firstCount = first.deliveryCount
        val secondCount = second.deliveryCount
        val countMatches = firstCount != null && secondCount != null && firstCount == secondCount

        val firstMerchants = merchantTokens(first.asParsedOffer())
        val secondMerchants = merchantTokens(second.asParsedOffer())
        val venueMatches = firstMerchants.isNotEmpty() && secondMerchants.isNotEmpty() &&
            firstMerchants.any { left -> secondMerchants.any { right -> tokenMatches(left, right) } }

        val firstPickups = addressTokens(first.pickupAddresses)
        val secondPickups = addressTokens(second.pickupAddresses)
        val pickupMatches = overlaps(firstPickups, secondPickups)

        val firstDropoffs = addressTokens(first.dropoffAddresses)
        val secondDropoffs = addressTokens(second.dropoffAddresses)
        val dropoffMatches = overlaps(firstDropoffs, secondDropoffs)

        val firstAllAddresses = firstPickups + firstDropoffs
        val secondAllAddresses = secondPickups + secondDropoffs
        val crossRoleAddressMatches = overlaps(firstAllAddresses, secondAllAddresses)

        // Rich/partial frames of one live offer can disagree about whether a repeated line is pickup
        // or drop-off. A matching drop-off is the strongest short-burst signal. If one side does not
        // expose drop-offs yet, require venue + distance + any canonical route address instead.
        if (elapsed <= BURST_WINDOW_MS) {
            val bothKnowDropoff = firstDropoffs.isNotEmpty() && secondDropoffs.isNotEmpty()
            if (bothKnowDropoff && dropoffMatches && (distanceMatches || venueMatches || pickupMatches)) {
                return true
            }
            if (!bothKnowDropoff && distanceMatches && venueMatches && crossRoleAddressMatches) {
                return true
            }
        }

        if (firstCount != null && secondCount != null && !countMatches) return false
        if (firstMerchants.isNotEmpty() && secondMerchants.isNotEmpty() && !venueMatches) return false
        if (firstPickups.isNotEmpty() && secondPickups.isNotEmpty() && !pickupMatches) return false
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

    private fun boltEtaCompatible(first: OfferRecord, second: OfferRecord): Boolean {
        val firstMin = first.estimatedMinutesMin
        val firstMax = first.estimatedMinutesMax
        val secondMin = second.estimatedMinutesMin
        val secondMax = second.estimatedMinutesMax
        if ((firstMin == null && firstMax == null) || (secondMin == null && secondMax == null)) return true

        val aMin = firstMin ?: firstMax ?: return true
        val aMax = firstMax ?: firstMin ?: return true
        val bMin = secondMin ?: secondMax ?: return true
        val bMax = secondMax ?: secondMin ?: return true
        return aMin <= bMax + BOLT_ETA_TOLERANCE_MINUTES &&
            bMin <= aMax + BOLT_ETA_TOLERANCE_MINUTES
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

    private fun addressTokens(values: List<String>): Set<String> = values.mapNotNull { value ->
        DeliveryAddressNormalizer.key(value)
            ?: identityToken(value).takeIf(String::isNotEmpty)?.let { "raw:$it" }
    }.toSet()

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
