package com.block154.courierpilot

import java.util.Locale

internal data class WoltAccessibilityDropoffRecoveryResult(
    val resolvedAddresses: List<String>,
    val candidateCount: Int,
)

/**
 * Conservative classifier for Wolt destination rows that exist in the Accessibility semantics tree
 * while the "Multiple drop-offs" sheet is still collapsed. It resolves only when the number of
 * unique hidden street addresses exactly matches the expected delivery count. Any ambiguity falls
 * back to the existing click/OCR path.
 */
internal object WoltAccessibilityDropoffRecovery {
    private val streetNumberPatterns = listOf(
        Regex("(?i)\\bg\\.\\s*\\d"),
        Regex("(?i)\\bpr\\.\\s*\\d"),
        Regex("(?i)\\bpl\\.\\s*\\d"),
        Regex("(?i)\\bal\\.\\s*\\d"),
        Regex("(?i)\\bskg\\.\\s*\\d"),
        Regex("(?i)\\bstr\\.?\\s*\\d"),
    )

    fun recover(
        hiddenTextPieces: List<String>,
        excludedAddresses: List<String>,
        expectedCount: Int,
    ): WoltAccessibilityDropoffRecoveryResult {
        if (expectedCount <= 0) return WoltAccessibilityDropoffRecoveryResult(emptyList(), 0)

        val excludedKeys = excludedAddresses.map(::addressKey).filter { it.isNotBlank() }.toSet()
        val candidates = hiddenTextPieces
            .asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() && looksLikeStreetAddress(it) }
            .filterNot { addressKey(it) in excludedKeys }
            .distinctBy(::addressKey)
            .toList()

        return WoltAccessibilityDropoffRecoveryResult(
            resolvedAddresses = candidates.takeIf { it.size == expectedCount }.orEmpty(),
            candidateCount = candidates.size,
        )
    }

    fun expandedFrame(addresses: List<String>, expectedCount: Int): String = buildString {
        appendLine("Multiple drop-offs")
        appendLine("$expectedCount stops")
        addresses.forEach(::appendLine)
        append("Done")
    }

    private fun looksLikeStreetAddress(value: String): Boolean {
        if (!Regex("\\d").containsMatchIn(value)) return false
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains(" gatv") ||
            lower.contains(" street ") ||
            lower.contains(" avenue ") ||
            lower.contains(" road ") ||
            lower.contains(" prospekt") ||
            lower.contains(" plentas") ||
            lower.contains(" alėja") ||
            lower.contains(" skersgat") ||
            streetNumberPatterns.any { it.containsMatchIn(value) }
    }

    private fun addressKey(value: String): String {
        val streetPart = value.substringBefore(',')
        return streetPart
            .lowercase(Locale.ROOT)
            .replace("gatvė", "g")
            .replace("gatve", "g")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}
