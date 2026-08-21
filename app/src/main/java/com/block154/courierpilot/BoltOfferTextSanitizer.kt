package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale

/**
 * Bolt's live map and offer bottom sheet share one rendered screen. Old builds occasionally fell
 * back to full-screen OCR, which let map labels, account totals and clipped branch suffixes leak into
 * persisted offer metadata. This helper keeps only the textual neighbourhood of the bottom-card
 * price and rejects obvious OCR branch fragments such as "str.)".
 */
internal object BoltOfferTextSanitizer {
    private val priceRegex = Regex(
        "(?i)(?:€\\s*|EUR\\s*)(\\d+(?:[.,]\\d{1,2})?)|(\\d+(?:[.,]\\d{1,2})?)\\s*(?:€|EUR)"
    )
    private val addressRegex = Regex(
        "(?i).*(?:\\bvilnius\\b|\\bLT-?\\d{5}\\b|\\bgatv(?:ė|e)\\b|\\bg\\.\\s*\\d|\\bstr\\.?\\s*\\d).*$"
    )
    private val orphanBranchFragmentRegex = Regex(
        "(?i)^\\s*\\(?[^()]{0,70}(?:\\bstr\\.?|\\bstreet|\\bg\\.?|\\bgatv(?:ė|e)?|\\bpr\\.?|\\bprospektas|\\bave\\.?|\\bavenue|\\brd\\.?|\\broad)[^()]{0,30}\\)\\s*$"
    )

    fun sanitizeStoredRawText(rawText: String): String {
        val lines = rawText.lineSequence()
            .map(::cleanLine)
            .filter(String::isNotBlank)
            .filterNot(::isOrphanBranchFragment)
            .toList()
        if (lines.isEmpty()) return ""

        // When an older capture accidentally contains the whole Bolt screen, the offer price lives
        // in the lower card and therefore appears after account/earnings amounts. Anchor on the last
        // currency line, then retain enough lines above it to include venue + address + ETA.
        val priceIndices = lines.indices.filter { priceRegex.containsMatchIn(lines[it]) }
        if (priceIndices.isEmpty()) return lines.joinToString("\n")
        val priceIndex = priceIndices.last()

        val nearbyAddressIndices = lines.indices.filter { index ->
            index in (priceIndex - 14).coerceAtLeast(0)..(priceIndex + 4).coerceAtMost(lines.lastIndex) &&
                looksLikeAddress(lines[index])
        }
        val start = if (nearbyAddressIndices.isNotEmpty()) {
            (nearbyAddressIndices.minOrNull()!! - 7).coerceAtLeast(0)
        } else {
            (priceIndex - 10).coerceAtLeast(0)
        }
        val end = (priceIndex + 6).coerceAtMost(lines.lastIndex)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    fun isOrphanBranchFragment(value: String): Boolean =
        orphanBranchFragmentRegex.matches(cleanLine(value))

    fun looksLikeAddress(value: String): Boolean {
        val line = cleanLine(value)
        if (!Regex("\\d").containsMatchIn(line)) return false
        return addressRegex.matches(line)
    }

    private fun cleanLine(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)
        .replace(Regex("\\p{Cf}+"), "")
        .replace("\uFFFD", "")
        .trim()
        .replace(Regex("\\s+"), " ")
}
