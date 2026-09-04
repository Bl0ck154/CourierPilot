package com.block154.courierpilot

internal object WoltOfferUiText {
    const val LEGACY_EARNINGS_LABEL = "expected earnings for the full delivery"
    const val MODERN_EARNINGS_LABEL = "estimated earnings for the full delivery"

    val modernRouteSummaryRegex = Regex(
        "(?i)^\\s*(\\d+)\\s+stops?\\s*\\(\\s*\\d+(?:[.,]\\d+)?\\s*(?:km|m)\\s*\\)\\s*(?:[•·]\\s*)?\\d{1,3}\\s*[-–—]\\s*\\d{1,3}\\s*min\\s*$"
    )
    val collapsedMultipleDropoffsRegex = Regex(
        "(?i)^\\s*multiple\\s+drop[- ]?offs?\\s*\\(\\s*(\\d+)\\s+stops?\\s*\\)\\s*$"
    )
    val standaloneMultipleDropoffsRegex = Regex("(?i)^\\s*multiple\\s+drop[- ]?offs?\\s*$")
    val singleCustomerDropoffRegex = Regex("(?i)^\\s*customer\\s+drop[- ]?off\\s*$")
    val standaloneStopsRegex = Regex("(?i)^\\s*(\\d+)\\s+stops?\\s*$")

    fun isEarningsLabel(line: String): Boolean {
        val normalized = normalize(line)
        return normalized.contains(LEGACY_EARNINGS_LABEL) || normalized.contains(MODERN_EARNINGS_LABEL)
    }

    fun isModernEarningsLabel(line: String): Boolean =
        normalize(line).contains(MODERN_EARNINGS_LABEL)

    fun hasEarningsLabel(text: String): Boolean = text.lineSequence().any(::isEarningsLabel)

    fun hasModernOfferStructure(text: String): Boolean = text.lineSequence()
        .map(String::trim)
        .any { line ->
            isModernEarningsLabel(line) ||
                modernRouteSummaryRegex.matches(line) ||
                collapsedMultipleDropoffsRegex.matches(line) ||
                standaloneMultipleDropoffsRegex.matches(line) ||
                singleCustomerDropoffRegex.matches(line)
        }

    fun hasCollapsedMultipleDropoffs(text: String): Boolean = text.lineSequence()
        .map(String::trim)
        .any(collapsedMultipleDropoffsRegex::matches)

    fun hasExpandedMultipleDropoffSheet(text: String): Boolean {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return lines.indices.any { index ->
            standaloneMultipleDropoffsRegex.matches(lines[index]) &&
                lines.drop(index + 1).take(6).any(standaloneStopsRegex::matches)
        }
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}
