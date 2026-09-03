package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale

/**
 * Re-runs the current parser against stored raw text so UI fixes also improve previously captured
 * records. Bolt records are first passed through the same bottom-card text sanitizer used by the
 * live OCR pipeline so old full-screen OCR fallbacks can no longer keep map/account text as offer
 * metadata.
 *
 * The final pass below is deliberately semantic: Accessibility can expose the same stop more than
 * once with invisible whitespace/post-code differences, so route arrays are collapsed by canonical
 * building identity before they reach dedupe, history repair, address memory or UI.
 *
 * Persisted money is deliberately not reparsed here. A stored offer has already passed the capture
 * gate; raw Accessibility/OCR text is noisier and may contain extra or malformed money tokens. Using
 * a newer parser to overwrite the saved amount while merely opening History can therefore corrupt a
 * previously correct record.
 */
internal fun OfferRecord.withCurrentParsedStructure(): OfferRecord {
    val parseText = when {
        rawText.isBlank() -> ""
        packageName == CourierSignals.BOLT_PACKAGE -> BoltOfferTextSanitizer.sanitizeStoredRawText(rawText)
        else -> rawText
    }
    val parsed = parseText.takeIf(String::isNotBlank)?.let(OfferParser::parse)

    val sourceMerchants = parsed?.merchantNames?.takeIf { it.isNotEmpty() } ?: merchantNames
    val sourcePickups = parsed?.pickupAddresses?.takeIf { it.isNotEmpty() } ?: pickupAddresses
    val sourceCustomers = parsed?.customerNames?.takeIf { it.isNotEmpty() } ?: customerNames
    val sourceDropoffs = parsed?.dropoffAddresses?.takeIf { it.isNotEmpty() } ?: dropoffAddresses

    val normalizedPickups = canonicalDistinctAddresses(sourcePickups, bolt = packageName == CourierSignals.BOLT_PACKAGE)
    val normalizedDropoffs = canonicalDistinctAddresses(sourceDropoffs, bolt = packageName == CourierSignals.BOLT_PACKAGE)
    val normalizedMerchants = normalizedNames(sourceMerchants)
        .filterNot { packageName == CourierSignals.BOLT_PACKAGE && BoltOfferTextSanitizer.isOrphanBranchFragment(it) }
        .let { names ->
            if (normalizedPickups.size == 1 && names.isNotEmpty()) listOf(names.first()) else names
        }
    val normalizedCustomers = normalizedNames(sourceCustomers).let { names ->
        if (normalizedDropoffs.size == 1 && names.isNotEmpty()) {
            listOf(names.firstOrNull { !isGenericCustomer(it) } ?: names.first())
        } else {
            names
        }
    }

    val explicitDeliveryCount = rawText.takeIf(String::isNotBlank)?.let(::explicitWoltDeliveryCount)
    val meaningfulCustomerCount = normalizedCustomers
        .filterNot(::isGenericCustomer)
        .map(::identityToken)
        .filter(String::isNotBlank)
        .distinct()
        .size
    val inferredDeliveryCount = maxOf(normalizedDropoffs.size, meaningfulCustomerCount)
        .takeIf { it > 0 }
    val correctedDeliveryCount = explicitDeliveryCount
        ?: inferredDeliveryCount
        ?: parsed?.deliveryCount
        ?: deliveryCount

    val safeStoredRestaurant = restaurant?.takeUnless {
        packageName == CourierSignals.BOLT_PACKAGE && BoltOfferTextSanitizer.isOrphanBranchFragment(it)
    }

    return copy(
        // The persisted amount/currency is capture-time truth. Structural reparsing must never
        // replace it with a later OCR interpretation.
        priceCents = priceCents,
        distanceMeters = parsed?.distanceMeters ?: distanceMeters,
        restaurant = parsed?.restaurant ?: normalizedMerchants.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: safeStoredRestaurant,
        merchantNames = normalizedMerchants,
        pickupAddresses = normalizedPickups,
        customerNames = normalizedCustomers,
        dropoffAddresses = normalizedDropoffs,
        deliveryCount = correctedDeliveryCount,
        estimatedMinutesMin = parsed?.estimatedMinutesMin ?: estimatedMinutesMin,
        estimatedMinutesMax = parsed?.estimatedMinutesMax ?: estimatedMinutesMax,
    )
}

private val gluedStreetMarkerBeforeHouse = Regex(
    """(?iu)(?<=\p{L})(?=(?:g\.|gatv(?:ė|e)|str\.?|street|pr\.?|prospektas|ave\.?|avenue|al\.?|pl\.?|plentas|skg\.?|kel\.?|kelias)\s*\d)"""
)

private fun canonicalDistinctAddresses(values: List<String>, bolt: Boolean = false): List<String> {
    val seen = mutableSetOf<String>()
    return values.mapNotNull { raw ->
        val source = if (bolt) BoltOfferTextSanitizer.stripLeadingMapMarkerFromAddress(raw) else raw
        val cleaned = source
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace("\u200B", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return@mapNotNull null

        // OCR sometimes glues a street marker to the final street-name letter, e.g.
        // "V. Šopenog. 1". Canonicalize only for identity comparison and keep the best original
        // display text. The strong "marker + house number" lookahead avoids altering normal words.
        val identitySource = gluedStreetMarkerBeforeHouse.replace(cleaned, " ")
        val key = DeliveryAddressNormalizer.key(identitySource) ?: identityToken(identitySource)
        cleaned.takeIf { key.isNotBlank() && seen.add(key) }
    }
}

private fun normalizedNames(values: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return values.mapNotNull { raw ->
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        val key = identityToken(cleaned)
        cleaned.takeIf { key.isNotBlank() && seen.add(key) }
    }
}

private fun explicitWoltDeliveryCount(rawText: String): Int? =
    Regex("(?im)^\\s*(\\d{1,2})\\s+deliver(?:y|ies)\\s+from\\s*$")
        .find(rawText)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it in 1..20 }

private fun isGenericCustomer(value: String): Boolean =
    value.trim().equals("Customer", ignoreCase = true)

private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()