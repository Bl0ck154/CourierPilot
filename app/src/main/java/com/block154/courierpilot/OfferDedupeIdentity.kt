package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Stable identities for one live courier offer. */
internal object OfferDedupeIdentity {
    const val BURST_WINDOW_MS = 90L * 1000L
    const val PERSIST_DEDUPE_WINDOW_MS = 10L * 60L * 1000L
    internal const val BOLT_PRICE_DRIFT_WINDOW_MS = 30L * 1000L
    private const val NORMAL_FUZZY_WINDOW_MS = 3L * 60L * 1000L
    private const val SPARSE_FRAME_WINDOW_MS = 45L * 1000L
    private const val BOLT_CARD_BURST_WINDOW_MS = 120L * 1000L
    private const val BOLT_VISUAL_BURST_WINDOW_MS = 120L * 1000L
    private const val WOLT_PRICE_DRIFT_WINDOW_MS = 20L * 1000L
    private const val BOLT_ETA_TOLERANCE_MINUTES = 2
    private const val DISTANCE_TOLERANCE_METERS = 150

    fun burstFingerprint(packageName: String, parsed: ParsedOffer): String {
        if (packageName == CourierSignals.BOLT_PACKAGE) {
            // Bolt OCR occasionally corrupts only the price while the card/route is unchanged (for
            // example €6.84 -> €84.00). Once at least one canonical route address is visible, price
            // must therefore not be part of the short-lived screen identity. Keep ETA only for the
            // weaker single-address case so two genuinely different offers from the same venue are
            // less likely to collapse.
            val addresses = addressTokens(parsed.pickupAddresses + parsed.dropoffAddresses)
                .sorted()
            return buildString {
                append(packageName)
                if (addresses.isEmpty()) {
                    append("|p=").append(parsed.priceCents ?: -1)
                }
                append("|n=").append(parsed.deliveryCount ?: -1)
                if (addresses.size <= 1) {
                    append("|e=").append(parsed.estimatedMinutesMin ?: -1)
                        .append('-').append(parsed.estimatedMinutesMax ?: -1)
                }
                append("|a=").append(addresses.joinToString(";"))
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
     * Fuzzy guard for duplicate captures of the same live courier offer.
     *
     * Wolt can briefly expose the same screen through two capture paths while its Accessibility
     * contents are still settling. Bolt can do the equivalent while full-screen OCR is settling,
     * including corrupting only the price token. Price drift is tolerated only under strong,
     * platform-specific evidence; different routes are never collapsed just because a venue matches.
     */
    fun isSameLiveOffer(first: OfferRecord, second: OfferRecord): Boolean {
        if (first.packageName != second.packageName) return false
        val elapsed = abs(first.capturedAt - second.capturedAt)
        if (elapsed > PERSIST_DEDUPE_WINDOW_MS) return false
        val pricesMatch = first.priceCents == second.priceCents

        // The lower Bolt card is stronger evidence than OCR money. Run this before the price guard:
        // the real-device failure that produced €6.84 and €84.00 had one unchanged visual card.
        if (
            first.packageName == CourierSignals.BOLT_PACKAGE &&
            elapsed <= BOLT_VISUAL_BURST_WINDOW_MS &&
            first.visualFingerprint.isNotBlank() &&
            second.visualFingerprint.isNotBlank() &&
            OfferVisualFingerprint.isNear(first.visualFingerprint, second.visualFingerprint)
        ) {
            return true
        }

        if (first.packageName == CourierSignals.BOLT_PACKAGE && elapsed <= BOLT_CARD_BURST_WINDOW_MS) {
            val firstRouteAddresses = addressTokens(first.pickupAddresses + first.dropoffAddresses)
            val secondRouteAddresses = addressTokens(second.pickupAddresses + second.dropoffAddresses)
            val countCompatible = first.deliveryCount == null || second.deliveryCount == null ||
                first.deliveryCount == second.deliveryCount
            val exactAddressSet = firstRouteAddresses.isNotEmpty() && firstRouteAddresses == secondRouteAddresses

            if (pricesMatch && exactAddressSet && countCompatible) return true
            if (pricesMatch &&
                overlaps(firstRouteAddresses, secondRouteAddresses) &&
                countCompatible &&
                boltEtaCompatible(first, second)
            ) {
                return true
            }
        }

        if (!pricesMatch) {
            return isStrongBoltPriceDriftDuplicate(first, second, elapsed) ||
                isStrongWoltPriceDriftDuplicate(first, second, elapsed)
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

    /** Chooses which row to retain when a repair finds two captures of one live offer. */
    fun preferredHistoricalRecord(first: OfferRecord, second: OfferRecord): OfferRecord {
        if (first.packageName == CourierSignals.BOLT_PACKAGE && first.priceCents != second.priceCents) {
            val firstMoneyScore = boltHistoricalMoneyQuality(first)
            val secondMoneyScore = boltHistoricalMoneyQuality(second)
            if (firstMoneyScore != secondMoneyScore) {
                return if (secondMoneyScore > firstMoneyScore) second else first
            }
        }

        val firstScore = historicalQuality(first)
        val secondScore = historicalQuality(second)
        if (firstScore != secondScore) return if (secondScore > firstScore) second else first

        // Wolt price drift normally settles toward the later Accessibility frame. Bolt price drift is
        // usually OCR corruption of an already stable card, so keep the earlier row on an equal tie.
        if (first.priceCents != second.priceCents) {
            return if (first.packageName == CourierSignals.BOLT_PACKAGE) {
                if (first.capturedAt <= second.capturedAt) first else second
            } else {
                if (second.capturedAt >= first.capturedAt) second else first
            }
        }
        return if (first.capturedAt <= second.capturedAt) first else second
    }

    private fun isStrongBoltPriceDriftDuplicate(
        first: OfferRecord,
        second: OfferRecord,
        elapsed: Long,
    ): Boolean {
        if (first.packageName != CourierSignals.BOLT_PACKAGE) return false
        if (elapsed > BOLT_PRICE_DRIFT_WINDOW_MS) return false
        if (!first.currencyCode.equals(second.currencyCode, ignoreCase = true)) return false

        val firstAddresses = addressTokens(first.pickupAddresses + first.dropoffAddresses)
        val secondAddresses = addressTokens(second.pickupAddresses + second.dropoffAddresses)
        if (firstAddresses.isEmpty() || firstAddresses != secondAddresses) return false

        val firstCount = first.deliveryCount
        val secondCount = second.deliveryCount
        val countCompatible = firstCount == null || secondCount == null || firstCount == secondCount
        if (!countCompatible) return false

        // A complete pickup+drop-off route is already strong enough. With only one visible address,
        // also require the same venue and a compatible ETA to avoid collapsing two rapid real offers
        // from the same busy restaurant.
        if (firstAddresses.size >= 2) return true

        val firstMerchants = merchantTokens(first.asParsedOffer())
        val secondMerchants = merchantTokens(second.asParsedOffer())
        val venueMatches = firstMerchants.isNotEmpty() && secondMerchants.isNotEmpty() &&
            firstMerchants.any { left -> secondMerchants.any { right -> tokenMatches(left, right) } }
        return venueMatches && boltEtaCompatible(first, second)
    }

    private fun isStrongWoltPriceDriftDuplicate(
        first: OfferRecord,
        second: OfferRecord,
        elapsed: Long,
    ): Boolean {
        if (first.packageName != CourierSignals.WOLT_PACKAGE) return false
        if (elapsed > WOLT_PRICE_DRIFT_WINDOW_MS) return false
        if (!first.currencyCode.equals(second.currencyCode, ignoreCase = true)) return false

        val firstMerchants = merchantTokens(first.asParsedOffer())
        val secondMerchants = merchantTokens(second.asParsedOffer())
        val venueMatches = firstMerchants.isNotEmpty() && secondMerchants.isNotEmpty() &&
            firstMerchants.any { left -> secondMerchants.any { right -> tokenMatches(left, right) } }
        if (!venueMatches) return false

        val firstPickups = addressTokens(first.pickupAddresses)
        val secondPickups = addressTokens(second.pickupAddresses)
        val firstDropoffs = addressTokens(first.dropoffAddresses)
        val secondDropoffs = addressTokens(second.dropoffAddresses)
        if (firstPickups.isEmpty() || secondPickups.isEmpty()) return false
        if (firstDropoffs.isEmpty() || secondDropoffs.isEmpty()) return false
        if (firstPickups != secondPickups || firstDropoffs != secondDropoffs) return false

        val firstCount = first.deliveryCount
        val secondCount = second.deliveryCount
        return firstCount == null || secondCount == null || firstCount == secondCount
    }

    private fun historicalQuality(record: OfferRecord): Int {
        var score = 0
        if (isPlausibleHistoricalMoney(record)) score += 8
        if ((record.distanceMeters ?: 0) > 0) score += 5
        score += addressTokens(record.pickupAddresses).size.coerceAtMost(3) * 3
        score += addressTokens(record.dropoffAddresses).size.coerceAtMost(4) * 4
        score += merchantTokens(record.asParsedOffer()).size.coerceAtMost(3) * 2
        score += record.customerNames.count { it.isNotBlank() }.coerceAtMost(4)
        if (record.deliveryCount != null) score += 1
        return score
    }

    private fun boltHistoricalMoneyQuality(record: OfferRecord): Int {
        if (record.priceCents <= 0) return 0
        if (!record.currencyCode.equals("EUR", ignoreCase = true)) return 1
        return when (record.priceCents) {
            in 100..5_000 -> 2
            in 20..10_000 -> 1
            else -> 0
        }
    }

    private fun isPlausibleHistoricalMoney(record: OfferRecord): Boolean {
        if (record.priceCents <= 0) return false
        return !record.currencyCode.equals("EUR", ignoreCase = true) || record.priceCents <= 10_000
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
