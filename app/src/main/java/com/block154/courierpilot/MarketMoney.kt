package com.block154.courierpilot

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

data class MoneyAmount(val amountMinor: Long, val currencyCode: String, val fractionDigits: Int) {
    init {
        require(currencyCode.matches(Regex("[A-Z]{3}")))
        require(fractionDigits in 0..6)
    }

    fun major(): BigDecimal = BigDecimal.valueOf(amountMinor, fractionDigits)
}

/** Parses an amount only when a currency is explicit. Ambiguous bare symbols such as `$` or `kr`
 * are intentionally not guessed. */
object MarketCurrencyParser {
    private val symbolToCode = linkedMapOf(
        "€" to "EUR",
        "£" to "GBP",
        "zł" to "PLN",
        "₴" to "UAH",
        "Kč" to "CZK",
        "Ft" to "HUF",
    )
    private val numberPattern = "(?:[0-9]{1,3}(?:[ .\'’][0-9]{3})+(?:[.,][0-9]{1,6})?|[0-9]+(?:[.,][0-9]{1,6})?)"
    private val codePrefix = Regex("""(?i)\b([A-Z]{3})\s*($numberPattern)""")
    private val codeSuffix = Regex("""(?i)($numberPattern)\s*([A-Z]{3})\b""")
    private val symbolPrefix = Regex("""(${symbolToCode.keys.joinToString("|") { Regex.escape(it) }})\s*($numberPattern)""", RegexOption.IGNORE_CASE)
    private val symbolSuffix = Regex("""($numberPattern)\s*(${symbolToCode.keys.joinToString("|") { Regex.escape(it) }})""", RegexOption.IGNORE_CASE)

    fun containsMoney(text: String): Boolean = parse(text) != null

    fun parse(text: String, locale: Locale = Locale.getDefault()): MoneyAmount? {
        val symbolCandidate = symbolPrefix.find(text)?.let { it.groupValues[2] to codeForSymbol(it.groupValues[1]) }
            ?: symbolSuffix.find(text)?.let { it.groupValues[1] to codeForSymbol(it.groupValues[2]) }
        val codeCandidate = sequenceOf(
            codePrefix.findAll(text).map { it.groupValues[2] to it.groupValues[1].uppercase(Locale.ROOT) },
            codeSuffix.findAll(text).map { it.groupValues[1] to it.groupValues[2].uppercase(Locale.ROOT) },
        ).flatten().firstOrNull { (_, code) -> runCatching { Currency.getInstance(code) }.isSuccess }
        val candidate = symbolCandidate ?: codeCandidate ?: return null
        val code = candidate.second ?: return null
        val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return null
        val digits = currency.defaultFractionDigits.takeIf { it in 0..6 } ?: return null
        val major = parseMajor(candidate.first, digits, locale) ?: return null
        return runCatching {
            MoneyAmount(
                major.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                code,
                digits,
            )
        }.getOrNull()
    }

    private fun codeForSymbol(symbol: String): String? = symbolToCode.entries
        .firstOrNull { it.key.equals(symbol, ignoreCase = true) }
        ?.value

    private fun parseMajor(raw: String, fractionDigits: Int, locale: Locale): BigDecimal? {
        var value = raw.trim().replace(" ", "").replace("'", "").replace("’", "")
        if (value.isBlank()) return null
        val comma = value.lastIndexOf(',')
        val dot = value.lastIndexOf('.')
        val decimalIndex = when {
            comma >= 0 && dot >= 0 -> maxOf(comma, dot)
            comma >= 0 -> separatorIndex(value, comma, fractionDigits, locale)
            dot >= 0 -> separatorIndex(value, dot, fractionDigits, locale)
            else -> -1
        }
        value = if (decimalIndex >= 0) {
            val integerPart = value.substring(0, decimalIndex).replace(",", "").replace(".", "")
            val fractionalPart = value.substring(decimalIndex + 1).replace(",", "").replace(".", "")
            "$integerPart.$fractionalPart"
        } else {
            value.replace(",", "").replace(".", "")
        }
        return value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }

    private fun separatorIndex(value: String, index: Int, fractionDigits: Int, locale: Locale): Int {
        val after = value.length - index - 1
        if (fractionDigits == 0) return -1
        if (after == fractionDigits) return index
        if (after == 3 && fractionDigits != 3) return -1
        val localeSeparator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (after in 1..fractionDigits && value[index] == localeSeparator) index else if (after in 1..fractionDigits) index else -1
    }
}
