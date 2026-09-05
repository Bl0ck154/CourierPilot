package com.block154.courierpilot

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

internal enum class ParsedRouteStopKind {
    PICKUP,
    DROPOFF,
}

internal data class ParsedRouteStop(
    val kind: ParsedRouteStopKind,
    val name: String?,
    val address: String,
)

internal data class ParsedOffer(
    val priceCents: Int?,
    val money: MoneyAmount? = null,
    val distanceMeters: Int?,
    val restaurant: String?,
    val merchantNames: List<String> = emptyList(),
    val pickupAddresses: List<String> = emptyList(),
    val customerNames: List<String> = emptyList(),
    val dropoffAddresses: List<String> = emptyList(),
    val deliveryCount: Int? = null,
    val estimatedMinutesMin: Int? = null,
    val estimatedMinutesMax: Int? = null,
    val orderedRouteStops: List<ParsedRouteStop> = emptyList(),
)

internal object OfferParser {
    private val priceRegex = Regex(
        "(?i)(?:(?:€|£|zł|₴|Kč|Ft|[A-Z]{3})\\s*\\d|\\d[^\\n]{0,20}(?:€|£|zł|₴|Kč|Ft|[A-Z]{3}))"
    )
    private val distanceRegex = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b")
    private val estimateRegex = Regex("(?i)\\b(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})\\s*min\\b")
    private val singleMinuteRegex = Regex("(?i)~?\\s*(\\d{1,3})\\s*min\\b")
    private val stackedHeaderRegex = Regex("(?i)^\\s*(\\d+)\\s+deliver(?:y|ies)\\s+from\\s*$")
    private val minuteOnlyRegex = Regex("(?i)^~?\\s*\\d{1,3}\\s*min$")
    private val progressStatusRegex = Regex("(?i)^\\s*\\d{1,3}\\s*%\\s*(?:complete|completed)\\s*$")
    private val boltDropoffCountRegex = Regex("(?i)^\\s*drop[- ]?off\\s+points?\\s*:\\s*(\\d+)\\s*$")
    private val boltBranchNameRegex = Regex(
        "(?i).*\\([^)]*(?:\\bstr\\.?|\\bstreet|\\bg\\.?|\\bgatv(?:ė|e)?|\\bpr\\.?|\\bprospektas|\\bave\\.?|\\bavenue|\\brd\\.?|\\broad)[^)]*\\)\\s*$"
    )
    private val boltMapPoiRegex = Regex(
        "(?i).*(?:\\bpark\\b|\\bstadium\\b|\\bstation\\b|\\bstotis\\b|\\bmuseum\\b|\\bmuziej|\\bold town\\b|\\bsenamiest).*"
    )
    private val suspiciousInlineOcrGlyphRegex = Regex("(?<=\\p{L})[|¦](?=\\p{L})")

    fun parse(text: String): ParsedOffer {
        val lines = normalizedLines(text)
        val modernWolt = parseModernWoltLayout(lines)
        val merchantSummary = parseWoltMerchantSummary(lines)
        val merchantNames = when {
            modernWolt?.merchantNames?.isNotEmpty() == true -> modernWolt.merchantNames
            merchantSummary != null -> merchantSummary.second
            else -> parseBoltMerchants(lines)
        }
        val legacyStops = if (modernWolt == null) parseAddressStops(lines, merchantNames) else emptyList()
        val merchantStops = legacyStops.filter { it.isMerchant }
        val customerStops = legacyStops.filterNot { it.isMerchant }
        val pickups = modernWolt?.pickupAddresses ?: merchantStops.map { it.address }.distinct()
        val customers = modernWolt?.customerNames ?: customerStops.map { it.name ?: "Customer" }
        val dropoffs = modernWolt?.dropoffAddresses ?: customerStops.map { it.address }
        val orderedStops = modernWolt?.orderedRouteStops ?: legacyStops.map { stop ->
            ParsedRouteStop(
                kind = if (stop.isMerchant) ParsedRouteStopKind.PICKUP else ParsedRouteStopKind.DROPOFF,
                name = stop.name,
                address = stop.address,
            )
        }
        val estimate = parseEstimate(lines)

        // Restaurant count and delivery count are different concepts. Wolt can batch any number of
        // customer orders from one or several venues. Support both the legacy N-deliveries header
        // and the redesigned "Multiple drop-offs (N stops)" card/modal.
        val headerDeliveryCount = merchantSummary?.first ?: 0
        val modernDeliveryCount = modernWolt?.deliveryCount ?: 0
        val boltDropoffCount = lines.firstNotNullOfOrNull { line ->
            boltDropoffCountRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 0
        val customerStopCount = dropoffs.size
        val baselineCount = if (merchantNames.isNotEmpty()) 1 else 0
        val deliveryCount = maxOf(
            headerDeliveryCount,
            modernDeliveryCount,
            boltDropoffCount,
            customerStopCount,
            baselineCount,
        ).takeIf { it > 0 }

        val money = parseMoney(lines.joinToString(separator = 10.toChar().toString()), lines)
        return ParsedOffer(
            priceCents = money?.amountMinor?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(),
            money = money,
            distanceMeters = parseDistanceMeters(lines.joinToString(separator = 10.toChar().toString())),
            restaurant = merchantNames.takeIf { it.isNotEmpty() }?.joinToString(", "),
            merchantNames = merchantNames,
            pickupAddresses = pickups,
            customerNames = customers,
            dropoffAddresses = dropoffs,
            deliveryCount = deliveryCount,
            estimatedMinutesMin = estimate?.first,
            estimatedMinutesMax = estimate?.second,
            orderedRouteStops = orderedStops,
        )
    }

    fun platformName(packageName: String, sourceName: String): String {
        val value = "$packageName $sourceName".lowercase(Locale.ROOT)
        return when {
            "wolt" in value -> "Wolt"
            "bolt" in value -> "Bolt"
            else -> sourceName.ifBlank { packageName }
        }
    }

    private fun parseEstimate(lines: List<String>): Pair<Int, Int>? {
        lines.forEach { line ->
            estimateRegex.find(line)?.let { match ->
                val min = match.groupValues[1].toIntOrNull()
                val max = match.groupValues[2].toIntOrNull()
                if (min != null && max != null && min in 1..240 && max in min..240) return min to max
            }
        }

        lines.firstOrNull { MarketCurrencyParser.containsMoney(it) }?.let { priceLine ->
            singleMinuteRegex.find(priceLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..240 }
                ?.let { return it to it }
        }
        return null
    }

    private fun normalizedLines(text: String): List<String> = text.lineSequence()
        .map(::sanitizeCapturedLine)
        .filter { it.isNotEmpty() }
        .toList()

    private fun sanitizeCapturedLine(raw: String): String {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace(Regex("\\p{Cf}+"), "")
            .replace("\uFFFD", "")
        value = suspiciousInlineOcrGlyphRegex.replace(value, "")
        return value.trim().replace(Regex("\\s+"), " ")
    }

    private data class ModernWoltLayout(
        val merchantNames: List<String>,
        val pickupAddresses: List<String>,
        val customerNames: List<String>,
        val dropoffAddresses: List<String>,
        val deliveryCount: Int?,
        val orderedRouteStops: List<ParsedRouteStop>,
    )

    /**
     * Wolt's September 2026 offer card no longer exposes the legacy "Delivery from / Timeline"
     * section. Pickups are rendered as name/address rows below a compact "N stops (X km) • ETA"
     * summary, while one destination is shown as "Customer drop-off" and batches collapse their
     * destinations behind "Multiple drop-offs (N stops)". When CourierPilot briefly opens that
     * sheet, its text is accumulated with the card frame and this parser reconstructs the full route.
     */
    private fun parseModernWoltLayout(lines: List<String>): ModernWoltLayout? {
        val summaryIndexes = lines.indices.filter { index ->
            WoltOfferUiText.modernRouteSummaryRegex.matches(lines[index])
        }
        // Accessibility and OCR are intentionally concatenated. The redesigned Wolt card can
        // expose price/summary through Accessibility while omitting the visible stop addresses;
        // the OCR copy that follows then contains the complete card. Prefer the last summary so
        // that richer OCR frame wins instead of the first incomplete copy ending at its label.
        val summaryIndex = summaryIndexes.lastOrNull() ?: -1
        val contentStart = (summaryIndex + 1).coerceAtLeast(0)
        val singleDropoffIndex = lines.indices.firstOrNull { index ->
            index >= contentStart && WoltOfferUiText.singleCustomerDropoffRegex.matches(lines[index])
        } ?: -1
        val collapsedDropoffIndexes = lines.indices.filter { index ->
            index >= contentStart && WoltOfferUiText.collapsedMultipleDropoffsRegex.matches(lines[index])
        }
        val expandedDropoffIndexes = lines.indices.filter { index ->
            WoltOfferUiText.standaloneMultipleDropoffsRegex.matches(lines[index])
        }
        val modern = summaryIndexes.isNotEmpty() ||
            lines.any(WoltOfferUiText::isModernEarningsLabel) ||
            singleDropoffIndex >= 0 ||
            collapsedDropoffIndexes.isNotEmpty() ||
            expandedDropoffIndexes.isNotEmpty()
        if (!modern) return null

        val pickupStart = contentStart
        val pickupEndCandidates = buildList {
            singleDropoffIndex.takeIf { it >= pickupStart }?.let(::add)
            collapsedDropoffIndexes.firstOrNull { it >= pickupStart }?.let(::add)
            expandedDropoffIndexes.firstOrNull { it >= pickupStart }?.let(::add)
            lines.indexOfFirstFrom(pickupStart, WoltOfferUiText::isEarningsLabel)
                .takeIf { it >= pickupStart }
                ?.let(::add)
        }
        val pickupEnd = pickupEndCandidates.minOrNull() ?: lines.size

        val merchants = mutableListOf<String>()
        val pickups = mutableListOf<String>()
        val ordered = mutableListOf<ParsedRouteStop>()
        var previousAddressIndex = pickupStart - 1
        for (index in pickupStart until pickupEnd) {
            val address = lines[index]
            if (!looksLikeModernStreetAddress(address)) continue
            val segmentStart = (previousAddressIndex + 1).coerceAtLeast(pickupStart)
            val name = lines.subList(segmentStart, index)
                .asReversed()
                .firstOrNull(::isModernWoltMerchantCandidate)
            if (name != null) {
                if (merchants.none { namesEquivalent(it, name) }) merchants += name
                if (pickups.none { addressesEquivalent(it, address) }) pickups += address
                if (ordered.none { it.kind == ParsedRouteStopKind.PICKUP && addressesEquivalent(it.address, address) }) {
                    ordered += ParsedRouteStop(ParsedRouteStopKind.PICKUP, name, address)
                }
            }
            previousAddressIndex = index
        }

        val dropoffs = mutableListOf<String>()
        fun addDropoff(address: String) {
            if (dropoffs.none { addressesEquivalent(it, address) }) {
                dropoffs += address
                ordered += ParsedRouteStop(ParsedRouteStopKind.DROPOFF, "Customer", address)
            }
        }

        if (singleDropoffIndex >= 0) {
            lines.drop(singleDropoffIndex + 1)
                .takeWhile { line ->
                    !WoltOfferUiText.isEarningsLabel(line) &&
                        !line.equals("Accept", ignoreCase = true) &&
                        !line.equals("Decline", ignoreCase = true)
                }
                .firstOrNull(::looksLikeModernStreetAddress)
                ?.let(::addDropoff)
        }

        // Prefer an expanded sheet that occurs after the collapsed row in accumulated frame text.
        // This avoids mistaking pickup addresses from the base card for destinations.
        val expandedIndex = expandedDropoffIndexes.lastOrNull()
        if (expandedIndex != null) {
            lines.drop(expandedIndex + 1)
                .takeWhile { line ->
                    !line.equals("Done", ignoreCase = true) &&
                        !WoltOfferUiText.isEarningsLabel(line) &&
                        !line.equals("Accept", ignoreCase = true)
                }
                .filter(::looksLikeModernStreetAddress)
                .forEach(::addDropoff)
        }

        // ML Kit text-block order is not guaranteed to be strictly top-to-bottom. On the current
        // Wolt build it can emit the pickup name/address *before* the duplicated compact route
        // summary. In that case the summary-bounded pass above sees the customer address but misses
        // the pickup completely (real-device telemetry: pickups=0, dropoffs=1). Recover remaining
        // street-address lines globally, excluding known customer addresses and the expanded
        // drop-off sheet. Prefer candidates that either carry Vilnius/postcode context themselves or
        // sit immediately after a merchant-like line, which keeps map labels out of the route.
        val expandedSheetEndExclusive = expandedIndex?.let { start ->
            val relativeEnd = lines.drop(start + 1).indexOfFirst { line ->
                line.equals("Done", ignoreCase = true) ||
                    WoltOfferUiText.isEarningsLabel(line) ||
                    line.equals("Accept", ignoreCase = true)
            }
            if (relativeEnd >= 0) start + 1 + relativeEnd else lines.size
        }
        val expectedTotalStops = summaryIndexes.mapNotNull { index ->
            WoltOfferUiText.modernRouteSummaryRegex.matchEntire(lines[index])
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.maxOrNull()
        val pickupLimit = expectedTotalStops
            ?.let { (it - dropoffs.size).coerceAtLeast(1) }
            ?: Int.MAX_VALUE

        lines.forEachIndexed { index, address ->
            if (pickups.size >= pickupLimit) return@forEachIndexed
            if (!looksLikeModernStreetAddress(address)) return@forEachIndexed
            if (dropoffs.any { addressesEquivalent(it, address) }) return@forEachIndexed
            if (pickups.any { addressesEquivalent(it, address) }) return@forEachIndexed
            if (expandedIndex != null && expandedSheetEndExclusive != null &&
                index in (expandedIndex + 1) until expandedSheetEndExclusive
            ) return@forEachIndexed

            val nearbyName = lines.subList((index - 4).coerceAtLeast(0), index)
                .asReversed()
                .firstOrNull(::isModernWoltMerchantCandidate)
            val lower = address.lowercase(Locale.ROOT)
            val hasCardAddressContext = lower.contains("vilnius") || lower.contains("lt-") ||
                Regex("(?i)\\bLT\\s*[- ]?\\d{4,5}\\b").containsMatchIn(address)
            if (nearbyName == null && !hasCardAddressContext) return@forEachIndexed

            if (nearbyName != null && merchants.none { namesEquivalent(it, nearbyName) }) merchants += nearbyName
            pickups += address
            val recoveredPickup = ParsedRouteStop(ParsedRouteStopKind.PICKUP, nearbyName, address)
            val firstDropoffIndex = ordered.indexOfFirst { it.kind == ParsedRouteStopKind.DROPOFF }
            if (firstDropoffIndex >= 0) ordered.add(firstDropoffIndex, recoveredPickup)
            else ordered += recoveredPickup
        }

        // Live 0.15.36 telemetry showed the new Wolt card can OCR both visible addresses while
        // dropping the literal "Customer drop-off" label. That produced pickups=2/dropoffs=0 for a
        // two-stop single delivery and prevented Valhalla from starting. A genuine two-stop Wolt
        // offer must be one pickup plus one destination, so when no multi-drop UI is present we can
        // safely reclassify the second visually ordered address as the customer destination. The
        // spatially sorted Wolt OCR path keeps this order deterministic; the older recovery logic
        // remains in place as fallback for incomplete Accessibility text.
        if (expectedTotalStops == 2 &&
            dropoffs.isEmpty() &&
            collapsedDropoffIndexes.isEmpty() &&
            expandedDropoffIndexes.isEmpty() &&
            pickups.size == 2
        ) {
            val inferredDropoff = pickups.removeAt(1)
            val recoveredIndex = ordered.indexOfLast { stop ->
                stop.kind == ParsedRouteStopKind.PICKUP && addressesEquivalent(stop.address, inferredDropoff)
            }
            if (recoveredIndex >= 0) ordered.removeAt(recoveredIndex)
            addDropoff(inferredDropoff)
        }

        val collapsedCount = collapsedDropoffIndexes.firstNotNullOfOrNull { index ->
            WoltOfferUiText.collapsedMultipleDropoffsRegex.matchEntire(lines[index])
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val expandedCount = expandedIndex?.let { index ->
            lines.drop(index + 1).take(3).firstNotNullOfOrNull { line ->
                WoltOfferUiText.standaloneStopsRegex.matchEntire(line)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
        val deliveryCount = maxOf(
            collapsedCount ?: 0,
            expandedCount ?: 0,
            dropoffs.size,
            if (singleDropoffIndex >= 0) 1 else 0,
        ).takeIf { it > 0 }

        return ModernWoltLayout(
            merchantNames = merchants,
            pickupAddresses = pickups,
            customerNames = List(dropoffs.size) { "Customer" },
            dropoffAddresses = dropoffs,
            deliveryCount = deliveryCount,
            orderedRouteStops = ordered,
        )
    }

    private fun List<String>.indexOfFirstFrom(start: Int, predicate: (String) -> Boolean): Int {
        for (index in start.coerceAtLeast(0) until size) if (predicate(this[index])) return index
        return -1
    }

    private fun isModernWoltMerchantCandidate(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if (!isStopNameCandidate(line)) return false
        if (WoltOfferUiText.modernRouteSummaryRegex.matches(line)) return false
        if (WoltOfferUiText.collapsedMultipleDropoffsRegex.matches(line)) return false
        if (WoltOfferUiText.standaloneMultipleDropoffsRegex.matches(line)) return false
        if (WoltOfferUiText.singleCustomerDropoffRegex.matches(line)) return false
        if (WoltOfferUiText.isEarningsLabel(line)) return false
        return lower !in MODERN_WOLT_NOISE_LINES
    }

    private fun addressesEquivalent(a: String, b: String): Boolean =
        a.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() ==
            b.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun parseWoltMerchantSummary(lines: List<String>): Pair<Int, List<String>>? {
        lines.forEachIndexed { index, line ->
            val count = when {
                line.equals("Delivery from", ignoreCase = true) -> 1
                stackedHeaderRegex.matches(line) -> stackedHeaderRegex.matchEntire(line)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachIndexed
                else -> return@forEachIndexed
            }
            val summary = lines.drop(index + 1).firstOrNull(::isMerchantSummaryCandidate)
                ?: return@forEachIndexed
            val merchants = if (count > 1) {
                summary.split(',').map(String::trim).filter(String::isNotEmpty)
            } else {
                listOf(summary)
            }
            return count to merchants
        }
        return null
    }

    /**
     * Bolt renders the order card on top of a live map. Each address in the bottom sheet anchors
     * one visible pickup row, so stacked offers must keep a merchant per row instead of choosing one
     * global winner for the whole screen. Merchant names are never rejected just for being short.
     */
    private fun parseBoltMerchants(lines: List<String>): List<String> {
        data class Candidate(val name: String, val score: Int, val lineIndex: Int)

        val merchants = mutableListOf<String>()
        var previousCardAddressIndex = -1
        lines.forEachIndexed { addressIndex, address ->
            if (!looksLikeAddress(address)) return@forEachIndexed

            val cardScore = boltCardAddressScore(lines, addressIndex)
            // Real pickup rows are followed by ETA and/or belong to the priced bottom sheet. Keep
            // price-only evidence as a fallback when OCR misses an ETA line; do not require a
            // particular merchant-name shape or length.
            if (cardScore < BOLT_CARD_PRICE_BONUS) return@forEachIndexed

            val start = maxOf(
                previousCardAddressIndex + 1,
                maxOf(0, addressIndex - BOLT_NAME_LOOKBACK_LINES),
            )
            val candidates = mutableListOf<Candidate>()
            for (index in start until addressIndex) {
                val candidate = lines[index]
                if (!isStopNameCandidate(candidate)) continue

                val distance = addressIndex - index
                var score = cardScore + (BOLT_NEARBY_NAME_SCORE - distance * 2)
                if (boltBranchNameRegex.matches(candidate)) score += BOLT_BRANCH_NAME_BONUS
                if (index == addressIndex - 1) score += BOLT_ADJACENT_NAME_BONUS
                if (looksLikeBoltMapLabel(candidate, address)) score -= BOLT_MAP_LABEL_PENALTY
                candidates += Candidate(candidate, score, index)
            }

            val bestCandidate = candidates
                .maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.lineIndex })
            // A venue can legitimately contain words such as "Park". The old map-label penalty is
            // only a ranking signal when several candidates compete; never let it discard the sole
            // card-title candidate for an address row.
            val chosen = bestCandidate?.takeIf { it.score >= BOLT_MIN_MERCHANT_SCORE }
                ?: candidates.singleOrNull()
            chosen?.name?.let { merchant ->
                if (merchants.none { namesEquivalent(it, merchant) }) merchants += merchant
            }

            previousCardAddressIndex = addressIndex
        }
        return merchants
    }

    private fun boltCardAddressScore(lines: List<String>, addressIndex: Int): Int {
        var score = 0
        if ((1..3).any { offset ->
                lines.getOrNull(addressIndex + offset)?.let(minuteOnlyRegex::matches) == true
            }) {
            score += BOLT_CARD_ETA_BONUS
        }
        if ((1..7).any { offset ->
                lines.getOrNull(addressIndex + offset)?.let(MarketCurrencyParser::containsMoney) == true
            }) {
            score += BOLT_CARD_PRICE_BONUS
        }
        return score
    }

    private fun looksLikeBoltMapLabel(candidate: String, address: String): Boolean {
        val normalizedCandidate = candidate.lowercase(Locale.ROOT).trim()

        // A standalone locality copied from the address ("Vilnius" in "..., Vilnius") is a map
        // label, not a merchant name. Strip postal codes before comparing address components.
        val addressLocalities = address.split(',')
            .drop(1)
            .map { component ->
                component
                    .replace(Regex("(?i)\\bLT-?\\d+\\b"), "")
                    .replace(Regex("\\b\\d{4,6}\\b"), "")
                    .trim()
                    .lowercase(Locale.ROOT)
            }
            .filter { it.isNotBlank() }
        if (normalizedCandidate in addressLocalities) return true

        return boltMapPoiRegex.matches(normalizedCandidate)
    }

    private data class AddressStop(val name: String?, val address: String, val isMerchant: Boolean)

    /**
     * Parses the route sequentially. A stop name may be separated from its address by Ready/ETA,
     * but it must live after the previous address. Looking four arbitrary lines backwards allowed a
     * previous merchant name to "leak" into the next customer address and produced fake P3/P4
     * pickups. Segment boundaries remove that ambiguity and naturally support 1..N pickups/dropoffs.
     */
    private fun parseAddressStops(lines: List<String>, merchants: List<String>): List<AddressStop> {
        val addresses = mutableListOf<AddressStop>()
        val usedMerchants = mutableSetOf<String>()
        var pickupPhaseEnded = false
        var previousAddressIndex = lines.indexOfFirst { it.equals("Timeline", ignoreCase = true) }

        lines.forEachIndexed { index, line ->
            if (!looksLikeAddress(line)) return@forEachIndexed
            if (index <= previousAddressIndex) return@forEachIndexed

            val segmentStart = (previousAddressIndex + 1).coerceAtLeast(0)
            val segment = lines.subList(segmentStart, index)
            // Known Wolt merchant names are stronger evidence than the generic stop-name filter.
            // This is important for interleaved stacked timelines where a later pickup can appear
            // after an earlier customer drop-off, and it also handles unusually short merchant names.
            val explicitMerchant = segment.asReversed().firstNotNullOfOrNull { candidate ->
                merchants.firstOrNull { known -> namesEquivalent(candidate, known) }
                    ?.let { known -> candidate to known }
            }
            val nearestNamedStop = segment.asReversed().firstOrNull(::isStopNameCandidate)

            val matchedMerchant = explicitMerchant?.second ?: nearestNamedStop?.let { candidate ->
                merchants.firstOrNull { known -> namesEquivalent(candidate, known) }
            }
            val matchedMerchantDisplayName = explicitMerchant?.first ?: nearestNamedStop

            // A customer-like named stop ends the fallback "consume remaining merchants" phase,
            // but an explicit known merchant can still legitimately appear later in a stacked route.
            if (explicitMerchant == null && nearestNamedStop != null && matchedMerchant == null) pickupPhaseEnded = true

            val fallbackMerchant = if (
                explicitMerchant == null &&
                nearestNamedStop == null &&
                !pickupPhaseEnded
            ) {
                merchants.firstOrNull { merchant -> merchantIdentity(merchant) !in usedMerchants }
            } else {
                null
            }

            val merchant = matchedMerchant ?: fallbackMerchant
            val isMerchant = merchant != null
            val name = when {
                matchedMerchant != null -> matchedMerchantDisplayName
                fallbackMerchant != null -> fallbackMerchant
                else -> nearestNamedStop
            }

            addresses += AddressStop(name, line, isMerchant)
            if (merchant != null) usedMerchants += merchantIdentity(merchant)
            previousAddressIndex = index
        }
        return addresses.distinctBy { "${it.name}|${it.address}|${it.isMerchant}" }
    }

    private fun merchantIdentity(value: String): String = value.lowercase(Locale.ROOT).trim()

    private fun namesEquivalent(a: String, b: String): Boolean {
        val aa = a.lowercase(Locale.ROOT).trim()
        val bb = b.lowercase(Locale.ROOT).trim()
        return aa == bb || aa.startsWith(bb) || bb.startsWith(aa)
    }

    private fun isMerchantSummaryCandidate(line: String): Boolean {
        if (!isStopNameCandidate(line)) return false
        val lower = line.lowercase(Locale.ROOT)
        return lower != "timeline" && lower != "route distance" && lower != "estimated"
    }

    private fun isStopNameCandidate(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if (line.length !in 2..140) return false
        if (MarketCurrencyParser.containsMoney(line) || distanceRegex.matches(line) || estimateRegex.containsMatchIn(line)) return false
        if (minuteOnlyRegex.matches(line) || progressStatusRegex.matches(line) || looksLikeAddress(line)) return false
        if (WoltOfferUiText.modernRouteSummaryRegex.matches(line) ||
            WoltOfferUiText.collapsedMultipleDropoffsRegex.matches(line) ||
            WoltOfferUiText.standaloneStopsRegex.matches(line)
        ) return false
        if (boltDropoffCountRegex.matches(line)) return false
        if (lower in GENERIC_LINES) return false
        if (lower.startsWith("pickup ") || lower.startsWith("delivery ")) return false
        if (lower.matches(Regex("^[\\d:.,%+\\- ]+$"))) return false
        return true
    }

    private fun looksLikeModernStreetAddress(line: String): Boolean {
        if (!Regex("\\d").containsMatchIn(line)) return false
        val lower = line.lowercase(Locale.ROOT)
        return lower.contains(" gatv") ||
            Regex("(?i)\\bg\\.\\s*\\d").containsMatchIn(line) ||
            Regex("(?i)\\bstr\\.?\\s*\\d").containsMatchIn(line) ||
            lower.contains(" street ") ||
            lower.contains(" avenue ") ||
            lower.contains(" road ")
    }

    private fun looksLikeAddress(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        val hasNumber = Regex("\\d").containsMatchIn(line)
        if (!hasNumber) return false
        return lower.contains("vilnius") ||
            lower.contains("lt-") ||
            lower.contains(" gatv") ||
            Regex("(?i)\\bg\\.\\s*\\d").containsMatchIn(line) ||
            Regex("(?i)\\bstr\\.?\\s*\\d").containsMatchIn(line)
    }

    private fun parseMoney(text: String, lines: List<String>): MoneyAmount? {
        val modernSummaryIndexes = lines.indices.filter { index ->
            WoltOfferUiText.modernRouteSummaryRegex.matches(lines[index])
        }
        if (modernSummaryIndexes.isNotEmpty() || lines.any(WoltOfferUiText::isModernEarningsLabel)) {
            modernSummaryIndexes.forEach { summaryIndex ->
                // New Wolt layout: the amount is the large line immediately above the compact
                // "N stops (X km) • ETA" summary. Keep this anchored and never trust map money.
                for (offset in listOf(-1, -2, 0, 1)) {
                    val candidate = lines.getOrNull(summaryIndex + offset) ?: continue
                    MarketCurrencyParser.parse(candidate)?.let { return it }
                }
            }
            // If Accessibility/OCR drops the compact summary, keep the modern explanatory label as
            // a conservative fallback. Spatial OCR handles the much larger real screen gap.
            lines.indices.filter { WoltOfferUiText.isModernEarningsLabel(lines[it]) }.forEach { earningsIndex ->
                for (offset in listOf(-1, -2, 0, 1, 2)) {
                    val candidate = lines.getOrNull(earningsIndex + offset) ?: continue
                    MarketCurrencyParser.parse(candidate)?.let { return it }
                }
            }
            return null
        }

        val earningsIndexes = lines.indices.filter { index ->
            lines[index].contains(WoltOfferUiText.LEGACY_EARNINGS_LABEL, ignoreCase = true)
        }
        if (earningsIndexes.isNotEmpty()) {
            // Accessibility text and OCR text are concatenated, so the same Wolt label can appear
            // twice. Search every anchored neighbourhood; the first Accessibility copy may still be
            // missing the amount while the later OCR copy already contains it.
            earningsIndexes.forEach { earningsIndex ->
                for (offset in listOf(-1, 0, 1, -2, 2)) {
                    val candidate = lines.getOrNull(earningsIndex + offset) ?: continue
                    MarketCurrencyParser.parse(candidate)?.let { return it }
                }
            }
            // Legacy Wolt earnings label remains authoritative. During loading, full-screen OCR can
            // also see unrelated balances/map/UI amounts; wait instead of accepting arbitrary money.
            return null
        }
        return MarketCurrencyParser.parse(text)
    }

    private fun parseDistanceMeters(text: String): Int? {
        return distanceRegex.findAll(text).mapNotNull { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val meters = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "km" -> (value * 1000.0).toInt()
                else -> value.toInt()
            }
            meters.takeIf { it in 1..MAX_DISTANCE_METERS }
        }.firstOrNull()
    }

    private val GENERIC_LINES = setOf(
        "wolt", "bolt", "new task", "new order", "new delivery", "offer",
        "accept", "decline", "reject", "pickup", "dropoff", "delivery",
        "delivery from", "timeline", "route distance", "estimated",
        WoltOfferUiText.LEGACY_EARNINGS_LABEL, WoltOfferUiText.MODERN_EARNINGS_LABEL,
        "customer drop-off", "multiple drop-offs", "collect cash", "done",
        "close drawer", "google map", "map marker",
        "ready", "show map", "priimti", "atmesti", "užduotis", "uzduot", "užsakymas", "uzsakymas",
        "принять", "отклонить", "заказ", "задание", "прийняти", "відхилити", "замовлення", "завдання"
    )

    private val MODERN_WOLT_NOISE_LINES = setOf(
        "collect cash", "customer drop-off", "multiple drop-offs", "done",
        WoltOfferUiText.LEGACY_EARNINGS_LABEL, WoltOfferUiText.MODERN_EARNINGS_LABEL,
    )

    private const val BOLT_NAME_LOOKBACK_LINES = 6
    private const val BOLT_NEARBY_NAME_SCORE = 18
    private const val BOLT_BRANCH_NAME_BONUS = 100
    private const val BOLT_ADJACENT_NAME_BONUS = 8
    private const val BOLT_MAP_LABEL_PENALTY = 120
    private const val BOLT_CARD_ETA_BONUS = 60
    private const val BOLT_CARD_PRICE_BONUS = 25
    private const val BOLT_MIN_MERCHANT_SCORE = 0
    private const val MIN_PRICE_CENTS = 20
    private const val MAX_PRICE_CENTS = 10_000
    private const val MAX_DISTANCE_METERS = 100_000
}
