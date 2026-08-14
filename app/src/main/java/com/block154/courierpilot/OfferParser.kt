package com.block154.courierpilot

import java.math.BigDecimal
import java.math.RoundingMode
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
        "(?i)(?:€\\s*|EUR\\s*)(\\d+(?:[.,]\\d{1,2})?)|(\\d+(?:[.,]\\d{1,2})?)\\s*(?:€|EUR)"
    )
    private val distanceRegex = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b")
    private val estimateRegex = Regex("(?i)\\b(\\d{1,3})\\s*-\\s*(\\d{1,3})\\s*min\\b")
    private val singleMinuteRegex = Regex("(?i)~?\\s*(\\d{1,3})\\s*min\\b")
    private val stackedHeaderRegex = Regex("(?i)^\\s*(\\d+)\\s+deliver(?:y|ies)\\s+from\\s*$")
    private val minuteOnlyRegex = Regex("(?i)^~?\\s*\\d{1,3}\\s*min$")

    fun parse(text: String): ParsedOffer {
        val lines = normalizedLines(text)
        val merchantSummary = parseWoltMerchantSummary(lines)
        val merchantNames = merchantSummary?.second ?: parseBoltMerchant(lines)?.let(::listOf).orEmpty()
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
        val customerStopCount = customerStops.size
        val baselineCount = if (merchantNames.isNotEmpty()) 1 else 0
        val deliveryCount = maxOf(headerDeliveryCount, customerStopCount, baselineCount)
            .takeIf { it > 0 }

        return ParsedOffer(
            priceCents = parsePriceCents(text, lines),
            distanceMeters = parseDistanceMeters(text),
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

        lines.firstOrNull { priceRegex.containsMatchIn(it) }?.let { priceLine ->
            singleMinuteRegex.find(priceLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..240 }
                ?.let { return it to it }
        }
        return null
    }

    private fun normalizedLines(text: String): List<String> = text.lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() }
        .toList()

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

    private fun parseBoltMerchant(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            if (!looksLikeAddress(line)) return@forEachIndexed
            for (i in index - 1 downTo maxOf(0, index - 3)) {
                val candidate = lines[i]
                if (isStopNameCandidate(candidate)) return candidate
            }
        }
        return null
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
            val nearestNamedStop = (index - 1 downTo segmentStart)
                .asSequence()
                .map { lines[it] }
                .firstOrNull(::isStopNameCandidate)

            val matchedMerchant = nearestNamedStop?.let { candidate ->
                merchants.firstOrNull { known -> namesEquivalent(candidate, known) }
            }
            if (nearestNamedStop != null && matchedMerchant == null) pickupPhaseEnded = true

            val fallbackMerchant = if (
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
                matchedMerchant != null -> nearestNamedStop
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
        if (priceRegex.containsMatchIn(line) || distanceRegex.matches(line) || estimateRegex.containsMatchIn(line)) return false
        if (minuteOnlyRegex.matches(line) || looksLikeAddress(line)) return false
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

    private fun parsePriceCents(text: String, lines: List<String>): Int? {
        // Accessibility/OCR can expose more than one euro amount on a stacked Wolt screen.
        // The total offer amount is the one semantically attached to this label, so prefer the
        // closest amount around it instead of blindly taking the first currency token in raw text.
        val earningsIndex = lines.indexOfFirst {
            it.contains("expected earnings for the full delivery", ignoreCase = true)
        }
        if (earningsIndex >= 0) {
            for (offset in listOf(-1, 0, 1, -2, 2)) {
                val candidate = lines.getOrNull(earningsIndex + offset) ?: continue
                firstValidPrice(candidate)?.let { return it }
            }
        }
        return firstValidPrice(text)
    }

    private fun firstValidPrice(value: String): Int? = priceRegex.findAll(value)
        .mapNotNull { match ->
            val raw = match.groupValues[1].ifBlank { match.groupValues[2] }.replace(',', '.')
            runCatching {
                BigDecimal(raw).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).intValueExact()
            }.getOrNull()
        }
        .firstOrNull { it in MIN_PRICE_CENTS..MAX_PRICE_CENTS }

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

    private const val MIN_PRICE_CENTS = 20
    private const val MAX_PRICE_CENTS = 10_000
    private const val MAX_DISTANCE_METERS = 100_000
}
