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
    private val estimateRegex = Regex("(?i)\\b(\\d{1,3})\\s*-\\s*(\\d{1,3})\\s*min\\b")
    private val singleMinuteRegex = Regex("(?i)~?\\s*(\\d{1,3})\\s*min\\b")
    private val stackedHeaderRegex = Regex("(?i)^\\s*(\\d+)\\s+deliver(?:y|ies)\\s+from\\s*$")
    private val minuteOnlyRegex = Regex("(?i)^~?\\s*\\d{1,3}\\s*min$")
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
        val merchantSummary = parseWoltMerchantSummary(lines)
        val merchantNames = merchantSummary?.second ?: parseBoltMerchants(lines)
        val stops = parseAddressStops(lines, merchantNames)
        val merchantStops = stops.filter { it.isMerchant }
        val customerStops = stops.filterNot { it.isMerchant }
        val pickups = merchantStops.map { it.address }.distinct()
        // Keep a placeholder when Accessibility/OCR exposes a destination address but omits the
        // customer label. This preserves name/address alignment in Offer details instead of pairing
        // the next customer's name with the wrong destination.
        val customers = customerStops.map { it.name ?: "Customer" }
        val dropoffs = customerStops.map { it.address }
        val orderedStops = stops.map { stop ->
            ParsedRouteStop(
                kind = if (stop.isMerchant) ParsedRouteStopKind.PICKUP else ParsedRouteStopKind.DROPOFF,
                name = stop.name,
                address = stop.address,
            )
        }
        val estimate = parseEstimate(lines)

        // Restaurant count and delivery count are different concepts. Wolt can batch any number of
        // customer orders from one or several venues. Prefer the explicit N-deliveries header when
        // present, but also count parsed customer stops because Wolt occasionally keeps a singular
        // "Delivery from" heading for a multi-drop route.
        val headerDeliveryCount = merchantSummary?.first ?: 0
        val boltDropoffCount = lines.firstNotNullOfOrNull { line ->
            boltDropoffCountRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 0
        val customerStopCount = customerStops.size
        val baselineCount = if (merchantNames.isNotEmpty()) 1 else 0
        val deliveryCount = maxOf(headerDeliveryCount, boltDropoffCount, customerStopCount, baselineCount)
            .takeIf { it > 0 }

        val money = parseMoney(lines.joinToString("\n"), lines)
        return ParsedOffer(
            priceCents = money?.amountMinor?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(),
            money = money,
            distanceMeters = parseDistanceMeters(lines.joinToString("\n")),
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
        if (minuteOnlyRegex.matches(line) || looksLikeAddress(line)) return false
        if (boltDropoffCountRegex.matches(line)) return false
        if (lower in GENERIC_LINES) return false
        if (lower.startsWith("pickup ") || lower.startsWith("delivery ")) return false
        if (lower.matches(Regex("^[\\d:.,%+\\- ]+$"))) return false
        return true
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
        val earningsIndex = lines.indexOfFirst {
            it.contains("expected earnings for the full delivery", ignoreCase = true)
        }
        if (earningsIndex >= 0) {
            for (offset in listOf(-1, 0, 1, -2, 2)) {
                val candidate = lines.getOrNull(earningsIndex + offset) ?: continue
                MarketCurrencyParser.parse(candidate)?.let { return it }
            }
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
        "expected earnings for the full delivery", "close drawer", "google map", "map marker",
        "ready", "show map", "priimti", "atmesti", "užduotis", "uzduot", "užsakymas", "uzsakymas",
        "принять", "отклонить", "заказ", "задание", "прийняти", "відхилити", "замовлення", "завдання"
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
