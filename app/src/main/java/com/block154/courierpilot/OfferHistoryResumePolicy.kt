package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Fail-closed recovery for a live offer that CourierPilot already persisted but briefly lost after
 * a bad Accessibility/Compose frame. This is intentionally stricter than normal DB dedupe: a recent
 * history row is allowed to resurrect the overlay only when the visible offer still has the same
 * platform price, platform distance and merchant identity. Known route addresses/counts may only
 * strengthen the match; contradictions veto recovery.
 */
internal object OfferHistoryResumePolicy {
    const val RECOVERY_WINDOW_MS = 5L * 60L * 1000L
    private const val DISTANCE_TOLERANCE_METERS = 150

    fun findMatchingRecent(
        database: OfferDatabase,
        packageName: String,
        parsed: ParsedOffer,
        now: Long = System.currentTimeMillis(),
    ): OfferRecord? {
        if (parsed.priceCents == null || parsed.distanceMeters == null) return null
        return database.recordsSince(now - RECOVERY_WINDOW_MS, limit = 40)
            .asSequence()
            .filter { it.packageName == packageName }
            .map { it.withCurrentParsedStructure() }
            .firstOrNull { isStrongMatch(it, packageName, parsed, now) }
    }

    fun isStrongMatch(
        historical: OfferRecord,
        packageName: String,
        visible: ParsedOffer,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (historical.packageName != packageName) return false
        val age = now - historical.capturedAt
        if (age !in 0..RECOVERY_WINDOW_MS) return false

        val visiblePrice = visible.priceCents ?: return false
        if (historical.priceCents != visiblePrice) return false

        val historicalDistance = historical.distanceMeters ?: return false
        val visibleDistance = visible.distanceMeters ?: return false
        if (abs(historicalDistance - visibleDistance) > DISTANCE_TOLERANCE_METERS) return false

        if (!merchantMatches(historical, visible)) return false

        val historicalCount = historical.deliveryCount
        val visibleCount = visible.deliveryCount
        if (historicalCount != null && visibleCount != null && historicalCount != visibleCount) return false

        val historicalPickups = addressKeys(historical.pickupAddresses)
        val visiblePickups = addressKeys(visible.pickupAddresses)
        if (historicalPickups.isNotEmpty() && visiblePickups.isNotEmpty() && historicalPickups.intersect(visiblePickups).isEmpty()) {
            return false
        }

        val historicalDropoffs = addressKeys(historical.dropoffAddresses)
        val visibleDropoffs = addressKeys(visible.dropoffAddresses)
        if (historicalDropoffs.isNotEmpty() && visibleDropoffs.isNotEmpty() && historicalDropoffs.intersect(visibleDropoffs).isEmpty()) {
            return false
        }

        return true
    }

    private fun merchantMatches(historical: OfferRecord, visible: ParsedOffer): Boolean {
        val historicalNames = (historical.merchantNames + listOfNotNull(historical.restaurant))
            .map(::merchantToken)
            .filter(String::isNotBlank)
            .distinct()
        val visibleNames = (visible.merchantNames + listOfNotNull(visible.restaurant))
            .map(::merchantToken)
            .filter(String::isNotBlank)
            .distinct()
        if (historicalNames.isEmpty() || visibleNames.isEmpty()) return false
        return historicalNames.any { left ->
            visibleNames.any { right ->
                left == right || (left.length >= 5 && right.length >= 5 && (left.contains(right) || right.contains(left)))
            }
        }
    }

    private fun addressKeys(values: List<String>): Set<String> = values.mapNotNull { value ->
        DeliveryAddressNormalizer.key(value)
            ?: merchantToken(value).takeIf(String::isNotBlank)?.let { "raw:$it" }
    }.toSet()

    private fun merchantToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
