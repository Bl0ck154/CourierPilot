package com.block154.courierpilot

import java.util.Locale

internal data class DeliveryScreenDetails(
    val address: String?,
    val customerName: String?,
    val instructions: String?,
    val additionalNote: String?,
    val apartment: String?,
    val floor: String?,
) {
    fun asDetailsText(): String? {
        val parts = buildList {
            instructions?.takeIf(String::isNotBlank)?.let { add("Instructions: $it") }
            additionalNote?.takeIf(String::isNotBlank)?.let { add("Additional note: $it") }
            apartment?.takeIf(String::isNotBlank)?.let { add("Apartment: $it") }
            floor?.takeIf(String::isNotBlank)?.let { add("Floor: $it") }
        }
        return parts.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    }
}

/**
 * Extracts stable delivery-detail fields from an accepted courier task screen.
 *
 * Free-form notes are intentionally preserved as text. We do not try to interpret every number or
 * token inside an `Additional note` as a door/intercom code because customers can write those
 * instructions in arbitrary formats and languages. The whole note is the durable source of truth.
 */
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
        "floor",
    )

    private val uiBoundaries = setOf(
        "translate",
        "call",
        "chat",
        "delivery issues?",
        "get help",
        "delivered",
        "slide to confirm",
        "view",
    )

    /**
     * Returns the value immediately following an explicit `Address` label even when the rest of the
     * accepted-delivery detail sheet is not visible yet. The label itself is strong structural
     * evidence, so compact values such as `Pylimo 9` do not need a broad line guess.
     */
    fun addressValue(text: String): String? = singleValue(normalizedLines(text), "address")

    fun extract(text: String): DeliveryScreenDetails? {
        val lines = normalizedLines(text)
        if (lines.isEmpty()) return null

        val address = singleValue(lines, "address")
        val instructions = sectionValue(lines, "instructions")
        val additionalNote = sectionValue(lines, "additional note")
        val apartment = firstPresentSingleValue(
            lines,
            listOf("apartment, flat or suite number", "apartment", "flat", "suite number"),
        )
        val floor = singleValue(lines, "floor")
        val customerName = customerName(lines)

        val hasDeliveryDetails = address != null && (
            instructions != null || additionalNote != null || apartment != null || floor != null ||
                lines.any { it.lowercase(Locale.ROOT).startsWith("items (") }
            )
        if (!hasDeliveryDetails) return null

        return DeliveryScreenDetails(
            address = address,
            customerName = customerName,
            instructions = instructions,
            additionalNote = additionalNote,
            apartment = apartment,
            floor = floor,
        )
    }

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
            lower.startsWith("items (")
    }
}
