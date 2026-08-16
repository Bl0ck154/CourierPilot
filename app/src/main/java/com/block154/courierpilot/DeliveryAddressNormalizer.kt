package com.block154.courierpilot

/**
 * Conservative canonicalization for delivery addresses before they enter local memory.
 *
 * Courier apps and customers use several visually similar dash characters for apartment suffixes.
 * CourierSignals already knows how to strip the common ASCII `house-apartment` form; this adapter
 * first folds Unicode punctuation and explicit apartment-label variants into that form. It also
 * removes a bare Lithuanian postal code that may remain after city stripping, e.g.
 * `M. Mironaitės gatvė 14, 04234 Vilnius` -> `M. Mironaitės gatvė 14`.
 */
internal object DeliveryAddressNormalizer {
    private val apartmentLabelAfterHouse = Regex(
        "(?i)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]\\s*(?:butas|but\\.?|apt\\.?|apartment)\\s*#?\\s*\\d{1,4}\\b.*$"
    )
    private val apartmentNumberThenLabel = Regex(
        "(?i)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]\\s*\\d{1,4}\\s*(?:butas|but\\.?|apt\\.?|apartment)\\b.*$"
    )
    private val trailingPostalCode = Regex("(?i)[,;]?\\s*(?:LT[- ]?)?\\d{5}\\s*$")
    private val streetMarker = Regex(
        "(?i)(?:\\bg\\.|gatv[eė]|\\bpr\\.|prospekt|\\bal\\.|al[eė]ja|\\bpl\\.|plentas|" +
            "\\bskg\\.|skersgatv|\\bkel\\.|kelias|\\bst\\.|street|\\bstr\\.|улиц|\\bул\\.|\\bвул\\.)"
    )
    private val houseNumber = Regex("\\b\\d{1,4}[A-Za-z]?\\b")

    fun normalize(raw: String): Pair<String, String>? {
        var cleaned = raw
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace("\u200B", "")
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

        cleaned = apartmentLabelAfterHouse.replace(cleaned) { match -> match.groupValues[1] }
        cleaned = apartmentNumberThenLabel.replace(cleaned) { match -> match.groupValues[1] }

        val normalized = CourierSignals.normalizeBuildingAddress(cleaned) ?: return null
        var display = normalized.second.trim(' ', ',', ';')

        // CourierSignals already strips `LT01122 Vilnius`, but a plain `01122 Vilnius` can leave
        // `..., 01122` behind. Only strip that remaining five-digit token for an actual street +
        // house-number address. Business/building-name destinations stay conservative.
        if (streetMarker.containsMatchIn(display) && houseNumber.containsMatchIn(display)) {
            val withoutPostal = trailingPostalCode.replace(display, "").trim(' ', ',', ';')
            if (withoutPostal != display) {
                CourierSignals.normalizeBuildingAddress(withoutPostal)?.let { return it }
                display = withoutPostal
            }
        }

        return if (display == normalized.second) normalized else normalized.first to display
    }

    fun key(raw: String): String? = normalize(raw)?.first

    fun display(raw: String): String? = normalize(raw)?.second
}
