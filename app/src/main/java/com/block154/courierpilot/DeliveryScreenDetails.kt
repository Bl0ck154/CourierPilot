package com.block154.courierpilot

import java.util.Locale

internal data class DeliveryScreenDetails(
    val address: String?,
    val customerName: String?,
    val instructions: String?,
    val additionalNote: String?,
    val apartment: String?,
    val floor: String?,
    val entryCode: String? = null,
    val buildingName: String? = null,
    val companyName: String? = null,
    val deliverTo: String? = null,
) {
    fun asDetailsText(): String? {
        val parts = buildList {
            buildingName?.takeIf(String::isNotBlank)?.let { add("Building: $it") }
            companyName?.takeIf(String::isNotBlank)?.let { add("Company: $it") }
            deliverTo?.takeIf(String::isNotBlank)?.let { add("Deliver to: $it") }
            instructions?.takeIf(String::isNotBlank)?.let { add("Instructions: $it") }
            additionalNote?.takeIf(String::isNotBlank)?.let { add("Additional note: $it") }
            apartment?.takeIf(String::isNotBlank)?.let { add("Apartment: $it") }
            entryCode?.takeIf(String::isNotBlank)?.let { add("Entry code: $it") }
            floor?.takeIf(String::isNotBlank)?.let { add("Floor: $it") }
        }
        return parts.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    }
}

/** Extracts durable fields only from accepted customer-side courier screens. */
internal object DeliveryScreenDetailsExtractor {
    private val customerOrderSuffix = Regex("(?i)\\s+#[-_\\p{L}\\p{N}]{3,}\\s*$")

    private val sectionLabels = setOf(
        "address",
        "instructions",
        "additional note",
        "apartment, flat or suite number",
        "apartment",
        "flat",
        "suite number",
        "entry code",
        "floor",
        "building name",
        "company name",
        "deliver to",
        "notes",
    )

    private val uiBoundaries = setOf(
        "translate",
        "see translation",
        "customer notes",
        "suite/floor",
        "meet in person",
        "leave at door",
        "leave at the door",
        "hand it to me",
        "navigate",
        "message",
        "call",
        "chat",
        "delivery issues?",
        "get help",
        "delivered",
        "slide to confirm",
        "view",
        "address details",
        "order details",
        "info",
        "items",
    )

    fun extractForPlatform(packageName: String, text: String): DeliveryScreenDetails? = when (packageName) {
        CourierSignals.WOLT_PACKAGE -> extractWoltCustomer(text)
        CourierSignals.BOLT_PACKAGE -> extractBoltCustomer(text)
        else -> extractBoltCustomer(text)
    }

    fun addressValueForPlatform(packageName: String, text: String): String? =
        extractForPlatform(packageName, text)?.address ?: addressValue(text)

    /** Legacy/generic parser kept for tests and unknown layouts; Bolt currently exposes these fields. */
    fun extract(text: String): DeliveryScreenDetails? = extractBoltCustomer(text)

    fun addressValue(text: String): String? = singleValue(normalizedLines(text), "address")

    private fun extractBoltCustomer(text: String): DeliveryScreenDetails? {
        val lines = normalizedLines(text)
        if (lines.isEmpty()) return null

        val address = singleValue(lines, "address")
        val instructions = sectionValue(lines, "instructions")
        val additionalNote = sectionValue(lines, "additional note")
        val apartment = firstPresentSingleValue(
            lines,
            listOf("apartment, flat or suite number", "apartment", "flat", "suite number"),
        )
        val entryCode = singleValue(lines, "entry code")
        val floor = singleValue(lines, "floor")
        val customerName = customerName(lines)

        val hasDeliveryDetails = address != null && (
            instructions != null || additionalNote != null || apartment != null || entryCode != null ||
                floor != null || lines.any { it.equals("delivery issues?", ignoreCase = true) }
            )
        if (!hasDeliveryDetails) return null

        return DeliveryScreenDetails(
            address = address,
            customerName = customerName,
            instructions = instructions,
            additionalNote = additionalNote,
            apartment = apartment,
            floor = floor,
            entryCode = entryCode,
        )
    }

    /**
     * True for Wolt's customer (drop-off) sheet in both layouts: the legacy `Dropoff to` sheet and
     * the October 2026 redesign headed `Deliver to` with Navigate / Customer notes / Suite/Floor.
     * Pickup sheets are rejected.
     */
    fun isWoltCustomerSheet(text: String): Boolean {
        val lines = normalizedLines(text)
        if (lines.any(::isWoltPickupHeader)) return false
        if (lines.any { it.equals("dropoff to", ignoreCase = true) }) return true
        return redesignedWoltHeaderIndex(lines) >= 0
    }

    /**
     * Wolt's customer screen does not label the street with `Address`. Legacy structure:
     * `Dropoff to` -> recipient/name -> street, followed by the INFO/Address details section.
     * `Pickup from` is deliberately rejected here.
     */
    private fun extractWoltCustomer(text: String): DeliveryScreenDetails? {
        val lines = normalizedLines(text)
        if (lines.isEmpty()) return null
        if (lines.any(::isWoltPickupHeader)) return null

        val dropoffIndex = lines.indexOfFirst { it.equals("dropoff to", ignoreCase = true) }
        if (dropoffIndex < 0) return extractRedesignedWoltCustomer(lines)

        val following = lines.drop(dropoffIndex + 1).take(5)
        val addressOffset = following.indexOfFirst { DeliveryAddressNormalizer.identity(it) != null }
        if (addressOffset < 0) return null
        val address = following[addressOffset]
        val customerName = following.take(addressOffset)
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                    DeliveryAddressNormalizer.identity(candidate) == null &&
                    !isBoundary(candidate)
            }
            ?.take(240)

        val buildingName = singleValue(lines, "building name")
        val companyName = singleValue(lines, "company name")
        val deliverTo = singleValue(lines, "deliver to")
        val floor = singleValue(lines, "floor")
        val notes = sectionValue(lines, "notes")

        return DeliveryScreenDetails(
            address = address,
            customerName = customerName,
            instructions = null,
            additionalNote = notes,
            apartment = null,
            floor = floor,
            buildingName = buildingName,
            companyName = companyName,
            deliverTo = deliverTo,
        )
    }

    /**
     * October 2026 Wolt customer sheet:
     * `Deliver to` -> name -> Call / Message -> street -> "City, postcode" -> `Suite/Floor` value ->
     * `Customer notes` text -> `See translation` -> handover method (`Meet in person`) -> order
     * line -> venue -> `Navigate`. Labels may arrive as their own node or inline with the value.
     */
    private fun extractRedesignedWoltCustomer(lines: List<String>): DeliveryScreenDetails? {
        val headerIndex = redesignedWoltHeaderIndex(lines)
        if (headerIndex < 0) return null

        val following = lines.drop(headerIndex + 1).take(8)
        val addressOffset = following.indexOfFirst { DeliveryAddressNormalizer.identity(it) != null }
        if (addressOffset < 0) return null
        val street = following[addressOffset]
        val locality = following.getOrNull(addressOffset + 1)?.takeIf(woltLocalityLine::matches)
        val address = listOfNotNull(street, locality).joinToString(", ")
        val customerName = following.take(addressOffset)
            .firstOrNull { candidate ->
                DeliveryAddressNormalizer.identity(candidate) == null && !isBoundary(candidate)
            }
            ?.take(240)

        val handover = lines.firstOrNull { line -> line.lowercase(Locale.ROOT) in woltHandoverMethods }
        return DeliveryScreenDetails(
            address = address,
            customerName = customerName,
            instructions = null,
            additionalNote = sectionValue(lines, "customer notes"),
            apartment = labelledValue(lines, listOf("apartment", "flat", "suite number")),
            floor = labelledValue(lines, listOf("suite/floor", "floor")),
            entryCode = labelledValue(lines, listOf("entry code", "door code", "gate code")),
            buildingName = labelledValue(lines, listOf("building name")),
            companyName = labelledValue(lines, listOf("company name")),
            deliverTo = handover,
        )
    }

    private fun redesignedWoltHeaderIndex(lines: List<String>): Int {
        val index = lines.indexOfFirst { it.equals("deliver to", ignoreCase = true) }
        if (index < 0) return -1
        val hasRedesignCue = lines.any { line ->
            val lower = line.lowercase(Locale.ROOT)
            lower in woltRedesignCues || woltRedesignCues.any { cue -> lower.startsWith("$cue ") }
        }
        return index.takeIf { hasRedesignCue } ?: -1
    }

    /** Label on its own line followed by the value, or `Label value` / `Label: value` inline. */
    private fun labelledValue(lines: List<String>, labels: List<String>): String? {
        labels.forEach { label ->
            singleValue(lines, label)?.let { return it }
            lines.firstNotNullOfOrNull { line ->
                val lower = line.lowercase(Locale.ROOT)
                if (!lower.startsWith(label)) return@firstNotNullOfOrNull null
                line.substring(label.length).trim().removePrefix(":").trim().takeIf(String::isNotEmpty)
            }?.let { return it }
        }
        return null
    }

    private fun isWoltPickupHeader(line: String): Boolean =
        line.equals("pickup from", ignoreCase = true) || line.equals("pick up from", ignoreCase = true)

    private val woltLocalityLine = Regex("""^\p{L}[\p{L} .'-]{1,40},\s*(?:LT-?)?\d{4,5}$""")
    private val woltRedesignCues = setOf("navigate", "customer notes", "meet in person", "suite/floor", "message")
    private val woltHandoverMethods = setOf(
        "meet in person", "leave at door", "leave at the door", "hand it to me", "meet outside", "meet at the door",
    )

    fun forAddress(text: String, address: String): DeliveryScreenDetails? {
        val details = extract(text) ?: return null
        val extractedAddress = details.address ?: return null
        val score = DeliveryAddressNormalizer.matchScore(extractedAddress, address)
        return details.takeIf { score >= 0.86 }
    }

    private fun normalizedLines(text: String): List<String> = text.lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotEmpty)
        .toList()

    private fun singleValue(lines: List<String>, label: String): String? {
        val index = labelIndex(lines, label)
        if (index < 0) return null
        return lines.getOrNull(index + 1)
            ?.takeUnless(::isBoundary)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun firstPresentSingleValue(lines: List<String>, labels: List<String>): String? =
        labels.firstNotNullOfOrNull { singleValue(lines, it) }

    private fun sectionValue(lines: List<String>, label: String): String? {
        val index = labelIndex(lines, label)
        if (index < 0) return null
        val values = mutableListOf<String>()
        for (i in index + 1 until lines.size) {
            val line = lines[i]
            if (isBoundary(line)) break
            values += line
        }
        return values.takeIf(List<String>::isNotEmpty)
            ?.joinToString(" ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun customerName(lines: List<String>): String? {
        val itemsIndex = lines.indexOfFirst { it.lowercase(Locale.ROOT).startsWith("items (") }
        if (itemsIndex <= 0) return null
        val candidate = lines[itemsIndex - 1]
            .replace(customerOrderSuffix, "")
            .trim()
        if (candidate.isBlank() || isBoundary(candidate)) return null
        if (DeliveryAddressNormalizer.identity(candidate) != null) return null
        return candidate.take(240)
    }

    private fun labelIndex(lines: List<String>, label: String): Int = lines.indexOfFirst {
        it.equals(label, ignoreCase = true)
    }

    private fun isBoundary(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT).trim()
        return lower in sectionLabels ||
            lower in uiBoundaries ||
            lower.startsWith("items (") ||
            lower.startsWith("pickup from") ||
            lower.startsWith("dropoff to")
    }
}
