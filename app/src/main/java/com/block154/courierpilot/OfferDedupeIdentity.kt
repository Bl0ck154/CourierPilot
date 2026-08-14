package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale

/**
 * Short-lived identity for one live offer. The full semantic fingerprint intentionally includes
 * addresses, but Accessibility/OCR can expose those progressively across callbacks. For a brief
 * live-offer window, price + route distance + delivery count + venue summary are stable enough to
 * recognize the same card without conflating offers over a long period.
 */
internal object OfferDedupeIdentity {
    const val BURST_WINDOW_MS = 90L * 1000L

    fun burstFingerprint(packageName: String, parsed: ParsedOffer): String {
        val merchants = (parsed.merchantNames.ifEmpty { listOfNotNull(parsed.restaurant) })
            .map(::identityToken)
            .filter(String::isNotEmpty)
            .distinct()
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

    fun burstFingerprint(record: OfferRecord): String {
        val parsed = ParsedOffer(
            priceCents = record.priceCents,
            distanceMeters = record.distanceMeters,
            restaurant = record.restaurant,
            merchantNames = record.merchantNames,
            pickupAddresses = record.pickupAddresses,
            customerNames = record.customerNames,
            dropoffAddresses = record.dropoffAddresses,
            deliveryCount = record.deliveryCount,
            estimatedMinutesMin = record.estimatedMinutesMin,
            estimatedMinutesMax = record.estimatedMinutesMax,
        )
        return burstFingerprint(record.packageName, parsed)
    }

    private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
