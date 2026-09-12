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
 *
 * Historical rows are untrusted UI input. One malformed capture must never strand Home/History/
 * OfferDetails in a permanent loading state, so structural enrichment is fail-open: the original
 * persisted record is still usable when a future parser repair encounters unexpected text.
 */
internal fun OfferRecord.withCurrentParsedStructure(): OfferRecord =
    runCatching { withCurrentParsedStructureUnchecked() }.getOrElse { this }

private fun OfferRecord.withCurrentParsedStructureUnchecked(): OfferRecord {
    val parseText = when {
        rawText.isBlank() -> ""
        packageName == CourierSignals.BOLT_PACKAGE -> BoltOfferTextSanitizer.sanitizeStoredRawText(rawText)
        else -> rawText
    }
    val parsedBase = parseText.takeIf(String::isNotBlank)?.let(OfferParser::parse)
    val parsed = if (packageName == CourierSignals.WOLT_PACKAGE && parsedBase != null) {
        parsedBase.withRecoveredTrailingWoltMerchants(parseText)
    } else {
        parsedBase
    }

    val parsedMerchants = parsed?.merchantNames.orEmpty().filterNot { value ->
        packageName == CourierSignals.WOLT_PACKAGE && WoltOfferUiText.isMerchantUiNoise(value)
    }
    val storedMerchants = merchantNames.filterNot { value ->
        packageName == CourierSignals.WOLT_PACKAGE && WoltOfferUiText.isMerchantUiNoise(value)
    }

    // A complete current parse of the redesigned Wolt card is stronger than old persisted route
    // structure. Previous versions rewarded list length, so three stale/misclassified pickup rows
    // could beat a correct one-pickup/one-drop-off reparse and keep History permanently wrong even
    // though rawText already contained the truth. When the old and current stop counts agree, keep
    // normal quality selection so a clean stored address can beat a freshly truncated OCR string.
    val authoritativeModernWoltStructure = packageName == CourierSignals.WOLT_PACKAGE &&
        parsed != null &&
        WoltOfferUiText.hasModernOfferStructure(parseText) &&
        parsed.pickupAddresses.isNotEmpty() &&
        parsed.dropoffAddresses.isNotEmpty()

    // A venue list cannot legitimately contain more independent merchants than a fully reconstructed
    // pickup list for this historical card. That shape is the signature of the 0.15.77 corruption
    // (`g.)`, `8 Customer drop-off`, `Pickup` for one real pickup), so prefer the recovered parser
    // merchant there. Otherwise retain normal quality selection so clean stored names such as
    // `Holy Donut (Vokiečių g.)` beat a fresh but noisier `B Holy Donut (Vokiečiųg.)` OCR string.
    val staleStoredMerchantOverflow = authoritativeModernWoltStructure &&
        parsedMerchants.isNotEmpty() &&
        storedMerchants.size > parsed.pickupAddresses.size.coerceAtLeast(1)
    val sourceMerchants = if (staleStoredMerchantOverflow) {
        parsedMerchants
    } else {
        chooseBetterNameList(parsedMerchants, storedMerchants)
    }
    val sourcePickups = if (
        authoritativeModernWoltStructure && pickupAddresses.size != parsed.pickupAddresses.size
    ) {
        parsed.pickupAddresses
    } else {
        chooseBetterAddressList(parsed?.pickupAddresses.orEmpty(), pickupAddresses)
    }
    val sourceCustomers = if (authoritativeModernWoltStructure && parsed.customerNames.isNotEmpty()) {
        parsed.customerNames
    } else {
        chooseBetterNameList(parsed?.customerNames.orEmpty(), customerNames, customerNames = true)
    }
    val sourceDropoffs = if (
        authoritativeModernWoltStructure && dropoffAddresses.size != parsed.dropoffAddresses.size
    ) {
        parsed.dropoffAddresses
    } else {
        chooseBetterAddressList(parsed?.dropoffAddresses.orEmpty(), dropoffAddresses)
    }

    val normalizedPickups = canonicalDistinctAddresses(sourcePickups, bolt = packageName == CourierSignals.BOLT_PACKAGE)
    val normalizedDropoffs = canonicalDistinctAddresses(sourceDropoffs, bolt = packageName == CourierSignals.BOLT_PACKAGE)
    val normalizedMerchants = normalizedNames(sourceMerchants)
        .filterNot { packageName == CourierSignals.BOLT_PACKAGE && BoltOfferTextSanitizer.isOrphanBranchFragment(it) }
        .filterNot { packageName == CourierSignals.WOLT_PACKAGE && WoltOfferUiText.isMerchantUiNoise(it) }
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

    val safeStoredRestaurant = restaurant?.takeUnless { value ->
        (packageName == CourierSignals.BOLT_PACKAGE && BoltOfferTextSanitizer.isOrphanBranchFragment(value)) ||
            (packageName == CourierSignals.WOLT_PACKAGE && WoltOfferUiText.isMerchantUiNoise(value))
    }

    return copy(
        // The persisted amount/currency is capture-time truth. Structural reparsing must never
        // replace it with a later OCR interpretation.
        priceCents = priceCents,
        distanceMeters = parsed?.distanceMeters ?: distanceMeters,
        restaurant = normalizedMerchants.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: safeStoredRestaurant,
        merchantNames = normalizedMerchants,
        pickupAddresses = normalizedPickups,
        customerNames = normalizedCustomers,
        dropoffAddresses = normalizedDropoffs,
        deliveryCount = correctedDeliveryCount,
        estimatedMinutesMin = parsed?.estimatedMinutesMin ?: estimatedMinutesMin,
        estimatedMinutesMax = parsed?.estimatedMinutesMax ?: estimatedMinutesMax,
    )
}

/**
 * ML Kit does not guarantee text-block order. Real Wolt frames can contain the pickup address near
 * the route summary while the venue title is emitted at the very end of OCR, after Accept/Map Marker.
 * A branch suffix such as `Sushi Express (Vokiečių g.)` is strong evidence because its street token
 * can be matched directly to the already parsed pickup address. Prefer that evidence over malformed
 * stored merchant fragments from older captures.
 */
private fun ParsedOffer.withRecoveredTrailingWoltMerchants(rawText: String): ParsedOffer {
    if (pickupAddresses.isEmpty() || rawText.isBlank()) return this

    val recovered = rawText.lineSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val match = trailingWoltBranchMerchantRegex.matchEntire(line) ?: return@mapNotNull null
            if (WoltOfferUiText.isMerchantUiNoise(line)) return@mapNotNull null
            val branch = match.groupValues.getOrNull(1).orEmpty()
            val pickup = pickupAddresses.firstOrNull { address -> branchMatchesPickup(branch, address) }
                ?: return@mapNotNull null
            pickup to line
        }
        .distinctBy { (pickup, merchant) -> identityToken(pickup) + "|" + identityToken(merchant) }
        .sortedBy { (pickup, _) -> pickupAddresses.indexOfFirst { address -> addressesSemanticallyEqual(address, pickup) } }
        .map { it.second }
        .distinctBy(::identityToken)
        .toList()

    if (recovered.isEmpty()) return this
    return copy(
        restaurant = recovered.joinToString(", "),
        merchantNames = recovered,
    )
}

private val trailingWoltBranchMerchantRegex = Regex(
    """(?iu)^.{2,120}\(([^()]*(?:\bg\.|\bgatv(?:ė|e)|\bstr\.?|\bstreet|\bpr\.?|\bprospektas|\bave\.?|\bavenue|\brd\.?|\broad|\bal\.?|\bpl\.?|\bplentas|\bskg\.?|\bkel\.?|\bkelias)[^()]*)\)\s*$"""
)

private fun branchMatchesPickup(branch: String, address: String): Boolean {
    val branchKey = identityToken(branch)
    val addressKey = identityToken(address)
    if (branchKey.length < 3 || addressKey.isBlank()) return false
    return addressKey == branchKey ||
        addressKey.startsWith("$branchKey ") ||
        addressKey.contains(" $branchKey ")
}

private fun addressesSemanticallyEqual(a: String, b: String): Boolean =
    identityToken(a) == identityToken(b)

private fun chooseBetterAddressList(parsed: List<String>, stored: List<String>): List<String> {
    if (parsed.isEmpty()) return stored
    if (stored.isEmpty()) return parsed
    fun score(values: List<String>): Int {
        val quality = values.sumOf(::addressQualityScore)
        return (quality * 10 / values.size.coerceAtLeast(1)) + values.size * 8
    }
    return if (score(stored) > score(parsed)) stored else parsed
}

private fun addressQualityScore(value: String): Int {
    val clean = value.trim()
    if (clean.isBlank()) return -100
    val lower = clean.lowercase(Locale.ROOT)
    var score = 0
    if (clean.firstOrNull()?.isUpperCase() == true) score += 8 else if (clean.firstOrNull()?.isLowerCase() == true) score -= 6
    if (lower.contains(" gatv") || Regex("(?i)\\b(?:g|pr|pl|al|skg)\\.\\s*\\d").containsMatchIn(clean)) score += 8
    if (lower.contains("vilnius")) score += 5
    if (Regex("(?i)\\b(?:LT[- ]?)?\\d{5}\\b").containsMatchIn(clean)) score += 4
    if (Regex("\\d").containsMatchIn(clean)) score += 3
    if (clean.length >= 12) score += 2
    return score
}

private fun chooseBetterNameList(
    parsed: List<String>,
    stored: List<String>,
    customerNames: Boolean = false,
): List<String> {
    if (parsed.isEmpty()) return stored
    if (stored.isEmpty()) return parsed
    fun score(values: List<String>): Int = values.sumOf { nameQualityScore(it, customerNames) }
    return if (score(stored) > score(parsed)) stored else parsed
}

private fun nameQualityScore(value: String, customerName: Boolean): Int {
    val clean = value.trim()
    if (clean.isBlank()) return -50
    if (clean.length < 2) return -30
    if (customerName && isGenericCustomer(clean)) return 1
    var score = 10
    if (clean.firstOrNull()?.isUpperCase() == true) score += 3
    if (clean.firstOrNull()?.isDigit() == true) score -= 20
    if (Regex("^[A-Za-zĄČĘĖĮŠŲŪŽąčęėįšųūž]\\s+.+$").matches(clean)) score -= 5
    if (addressQualityScore(clean) >= 10) score -= 20
    if (clean.length >= 4) score += 2
    return score
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