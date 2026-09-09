package com.block154.courierpilot

internal object WoltOfferUiText {
    const val LEGACY_EARNINGS_LABEL = "expected earnings for the full delivery"
    const val MODERN_EARNINGS_LABEL = "estimated earnings for the full delivery"

    val modernRouteSummaryRegex = Regex(
        "(?i)^\\s*\\+?\\s*(\\d+)\\s+stops?\\s*\\(\\s*\\d+(?:[.,]\\d+)?\\s*(?:km|m)\\s*\\)\\s*(?:[•·]\\s*)?\\d{1,3}\\s*[-–—]\\s*\\d{1,3}\\s*min(?:\\s+extra)?\\s*$"
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

    /** Service labels shown on restricted-item offers are card metadata, never venue names. */
    fun isMerchantUiNoise(line: String): Boolean {
        val normalized = normalize(line)
        return normalized in MERCHANT_UI_NOISE ||
            normalized.startsWith("id check ") ||
            normalized.startsWith("id verification ") ||
            normalized.startsWith("age verification ")
    }

    fun hasCollapsedMultipleDropoffs(text: String): Boolean = text.lineSequence()
        .map(String::trim)
        .any(collapsedMultipleDropoffsRegex::matches)

    fun hasExpandedMultipleDropoffSheet(text: String): Boolean {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return lines.indices.any { index ->
            standaloneMultipleDropoffsRegex.matches(lines[index]) &&
                lines.drop(index + 1).take(6).any(standaloneStopsRegex::matches) &&
                lines.drop(index + 1).take(24).any { it.equals("Done", ignoreCase = true) }
        }
    }

    private val MERCHANT_UI_NOISE = setOf(
        "id check",
        "id verification",
        "verify id",
        "age check",
        "age verification",
        "verification required",
    )

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}
