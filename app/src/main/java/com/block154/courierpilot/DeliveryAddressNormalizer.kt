package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

internal data class DeliveryAddressIdentity(
    val key: String,
    val display: String,
    val streetCore: String,
    val streetType: String?,
    val houseNumber: String,
)

/**
 * Canonical delivery-address identity used before anything reaches local memory.
 *
 * The matcher intentionally ignores presentation-only differences (postcode/city suffixes,
 * Lithuanian diacritics, punctuation, apartment suffixes and common street-type spellings). A
 * missing street-type marker is allowed, but two explicitly different street types never match.
 * Small one/two-character street-name typos are accepted only by the higher-level unique-candidate
 * matcher; translated street names are left to the optional geocoder stage.
 */
internal object DeliveryAddressNormalizer {
    private val unicodeDashChars = charArrayOf('\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212')
    private val trailingCountry = Regex(
        "(?iu)[,;]?\\s*(?:Lietuva|Lithuania|Lituanie|Litauen|Литва|Літва|Летува)\\s*$"
    )
    private val trailingVilnius = Regex(
        "(?iu)[,;]?\\s*(?:Vilnius|Vilna|Wilno|Вильнюс|Вільнюс|Вільня|Вильна)\\s*$"
    )
    private val trailingPostalCode = Regex("(?iu)[,;]?\\s*(?:LT[- ]?)?\\d{5}\\s*$")
    private val apartmentLabelAfterHouse = Regex(
        "(?iu)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]\\s*(?:butas|but\\.?|apt\\.?|apartment|kv\\.?|кв\\.?|квартира)\\s*#?\\s*\\d{1,4}\\b.*$"
    )
    private val apartmentNumberThenLabel = Regex(
        "(?iu)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]\\s*\\d{1,4}\\s*(?:butas|but\\.?|apt\\.?|apartment|kv\\.?|кв\\.?|квартира)\\b.*$"
    )
    private val apartmentSuffix = Regex("(\\b\\d{1,4}[A-Za-z]?)\\s*[-/]\\s*\\d{1,4}\\b")
    private val trailingHouseNumber = Regex("(?iu)(?:^|[\\s,;])([0-9]{1,4}[A-Za-z]?)(?:\\s*)$")
    private val explicitStreetMarker = Regex(
        "(?iu)(?:\\bg\\.?\\b|\\bgatv(?:ė|e)\\b|\\bstreet\\b|\\bstr\\.?\\b|\\bst\\.?\\b|" +
            "\\bul(?:ica)?\\.?\\b|улиц(?:а|ы|е|у|ей)?|ул\\.?|" +
            "вулиц(?:я|і|ю|ею)?|вул\\.?|вуліца|" +
            "\\bpr\\.?\\b|\\bprospekt(?:as)?\\b|\\bavenue\\b|\\bave\\.?\\b|проспект|просп\\.?|" +
            "\\bal\\.?\\b|\\bal(?:ė|e)ja\\b|\\balle?y\\b|" +
            "\\bpl\\.?\\b|\\bplentas\\b|\\bskg\\.?\\b|\\bskersgatv(?:is|ė|e)\\b|" +
            "\\bkel\\.?\\b|\\bkelias\\b)"
    )

    fun normalize(raw: String): Pair<String, String>? = identity(raw)?.let { it.key to it.display }

    fun identity(raw: String): DeliveryAddressIdentity? {
        var display = clean(raw)
        if (display.length !in 3..180) return null

        repeat(3) {
            display = trailingCountry.replace(display, "").trim(' ', ',', ';')
            display = trailingVilnius.replace(display, "").trim(' ', ',', ';')
            display = trailingPostalCode.replace(display, "").trim(' ', ',', ';')
        }

        display = apartmentLabelAfterHouse.replace(display) { it.groupValues[1] }
        display = apartmentNumberThenLabel.replace(display) { it.groupValues[1] }
        display = apartmentSuffix.replace(display) { it.groupValues[1] }
        display = display.trim(' ', ',', ';')

        val houseMatch = trailingHouseNumber.find(display) ?: return null
        val house = normalizeHouseNumber(houseMatch.groupValues[1])
        if (house.isBlank()) return null
        val streetPart = display.substring(0, houseMatch.range.first).trim(' ', ',', ';')
        if (streetPart.length < 2 || streetPart.none(Char::isLetter)) return null

        val type = canonicalStreetType(streetPart)
        val coreDisplay = explicitStreetMarker.replace(streetPart, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', ',', ';')
        val core = identityToken(coreDisplay)
        if (core.length < 2 || core.none(Char::isLetter)) return null

        val key = buildString {
            append(core)
            type?.let { append(' ').append(it) }
            append(' ').append(house.lowercase(Locale.ROOT))
        }
        return DeliveryAddressIdentity(
            key = key,
            display = display,
            streetCore = core,
            streetType = type,
            houseNumber = house,
        )
    }

    fun key(raw: String): String? = identity(raw)?.key

    fun display(raw: String): String? = identity(raw)?.display

    /** Extra detector for compact customer input such as `Vokiečių 7` without `g.` or city. */
    fun likelyAddressLines(text: String): List<String> = text.lineSequence()
        .map(::clean)
        .filter { it.length in 3..180 }
        .filter { line ->
            val identity = identity(line) ?: return@filter false
            if (line.contains('€')) return@filter false
            val lower = line.lowercase(Locale.ROOT)
            if (GENERIC_NON_ADDRESS_PREFIXES.any(lower::startsWith)) return@filter false
            val coreLetters = identity.streetCore.count(Char::isLetter)
            coreLetters >= 3 && (explicitStreetMarker.containsMatchIn(line) || coreLetters >= 4)
        }
        .distinct()
        .toList()

    /** 1.0 = strong local identity; 0.0 = do not auto-merge. */
    fun matchScore(firstRaw: String, secondRaw: String): Double {
        val first = identity(firstRaw) ?: return 0.0
        val second = identity(secondRaw) ?: return 0.0
        if (!first.houseNumber.equals(second.houseNumber, ignoreCase = true)) return 0.0
        if (first.streetType != null && second.streetType != null && first.streetType != second.streetType) return 0.0

        val left = first.streetCore.replace(" ", "")
        val right = second.streetCore.replace(" ", "")
        if (left == right) return if (first.streetType == second.streetType) 1.0 else 0.99
        if (left.firstOrNull() != right.firstOrNull()) return 0.0
        if (!sameScriptFamily(left, right)) return 0.0

        val longest = max(left.length, right.length)
        if (longest < 6) return 0.0
        val distance = levenshtein(left, right)
        val allowed = if (longest >= 11) 2 else 1
        if (distance > allowed) return 0.0
        val similarity = 1.0 - distance.toDouble() / longest.toDouble()
        return similarity.takeIf { it >= 0.86 } ?: 0.0
    }

    fun isLikelySameBuilding(firstRaw: String, secondRaw: String): Boolean =
        matchScore(firstRaw, secondRaw) >= 0.86

    fun preferredDisplay(first: String, second: String): String {
        val firstNormalized = display(first) ?: first.trim()
        val secondNormalized = display(second) ?: second.trim()
        val firstScore = displayQuality(firstNormalized)
        val secondScore = displayQuality(secondNormalized)
        return if (secondScore > firstScore) secondNormalized else firstNormalized
    }

    private fun displayQuality(value: String): Int {
        var score = 0
        if (explicitStreetMarker.containsMatchIn(value)) score += 4
        if (value.any { it in "ąčęėįšųūžĄČĘĖĮŠŲŪŽ" }) score += 2
        if (!trailingPostalCode.containsMatchIn(value)) score += 1
        if (!trailingVilnius.containsMatchIn(value)) score += 1
        return score
    }

    private fun clean(raw: String): String {
        var value = raw
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace("\u200B", "")
        unicodeDashChars.forEach { value = value.replace(it, '-') }
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun canonicalStreetType(streetPart: String): String? {
        val token = explicitStreetMarker.find(streetPart)?.value?.let(::identityToken) ?: return null
        return when {
            token in setOf("g", "gatve", "street", "str", "st", "ul", "ulica", "ulitsa", "ул", "улица", "улицы", "улице", "улицу", "улицей", "вулиця", "вулиці", "вулицю", "вулицею", "вул", "вуліца") -> "g"
            token in setOf("pr", "prospekt", "prospektas", "avenue", "ave", "проспект", "просп") -> "pr"
            token in setOf("al", "aleja", "alley") -> "al"
            token in setOf("pl", "plentas") -> "pl"
            token in setOf("skg", "skersgatvis", "skersgatve") -> "skg"
            token in setOf("kel", "kelias") -> "kel"
            else -> null
        }
    }

    private fun normalizeHouseNumber(value: String): String = value
        .replace(Regex("\\s+"), "")
        .uppercase(Locale.ROOT)

    private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun sameScriptFamily(left: String, right: String): Boolean {
        fun family(value: String): Int = when {
            value.any { it.code in 0x0400..0x052F } -> 2
            value.any { it in 'a'..'z' } -> 1
            else -> 0
        }
        return family(left) == family(right)
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, substitution)
            }
            previous = current
        }
        return previous[right.length]
    }

    private val GENERIC_NON_ADDRESS_PREFIXES = listOf(
        "customer", "pickup", "delivery", "route ", "estimated", "expected earnings",
        "accept", "decline", "reject", "wolt", "bolt", "ready", "timeline",
    )
}
