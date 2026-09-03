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

/** Parses an offer amount only when a currency is explicit. Ambiguous bare symbols such as `$` or
 * `kr` are intentionally not guessed. OCR can expose several money-looking tokens, so implausible
 * delivery amounts are skipped instead of poisoning the capture with the first noisy token. */
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

    private data class Candidate(
        val rawAmount: String,
        val currencyCode: String,
    )

    /** Money-looking text must stay classified as money even when the amount itself is rejected. */
    fun containsMoney(text: String): Boolean {
        if (symbolPrefix.containsMatchIn(text) || symbolSuffix.containsMatchIn(text)) return true
        if (codePrefix.findAll(text).any { match ->
                runCatching { Currency.getInstance(match.groupValues[1].uppercase(Locale.ROOT)) }.isSuccess
            }) return true
        return codeSuffix.findAll(text).any { match ->
            runCatching { Currency.getInstance(match.groupValues[2].uppercase(Locale.ROOT)) }.isSuccess
        }
    }

    fun parse(text: String, locale: Locale = Locale.getDefault()): MoneyAmount? {
        // Preserve the previous parser's symbol-before-ISO preference while allowing it to skip a
        // noisy candidate and continue to the next explicit amount.
        val candidates = sequenceOf(
            symbolPrefix.findAll(text).mapNotNull { match ->
                codeForSymbol(match.groupValues[1])?.let { Candidate(match.groupValues[2], it) }
            },
            symbolSuffix.findAll(text).mapNotNull { match ->
                codeForSymbol(match.groupValues[2])?.let { Candidate(match.groupValues[1], it) }
            },
            codePrefix.findAll(text).map { match ->
                Candidate(match.groupValues[2], match.groupValues[1].uppercase(Locale.ROOT))
            },
            codeSuffix.findAll(text).map { match ->
                Candidate(match.groupValues[1], match.groupValues[2].uppercase(Locale.ROOT))
            },
        ).flatten()

        candidates.forEach { candidate ->
            val currency = runCatching { Currency.getInstance(candidate.currencyCode) }.getOrNull()
                ?: return@forEach
            val digits = currency.defaultFractionDigits.takeIf { it in 0..6 }
                ?: return@forEach
            val major = parseMajor(candidate.rawAmount, digits, locale)
                ?: return@forEach
            val money = runCatching {
                MoneyAmount(
                    major.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                    candidate.currencyCode,
                    digits,
                )
            }.getOrNull() ?: return@forEach
            if (isPlausibleOfferAmount(money)) return money
        }
        return null
    }

    private fun isPlausibleOfferAmount(money: MoneyAmount): Boolean {
        if (money.amountMinor <= 0L) return false
        // CourierPilot historically guarded EUR offers to €0.20..€100. The currency-aware parser
        // accidentally dropped that safeguard, allowing OCR such as "€200" to replace a normal
        // Wolt fee. Keep the proven EUR bound while leaving native non-EUR markets uncapped here;
        // their scales differ too much to impose an invented FX-based threshold.
        return money.currencyCode != "EUR" || money.amountMinor in MIN_EUR_OFFER_MINOR..MAX_EUR_OFFER_MINOR
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

    private const val MIN_EUR_OFFER_MINOR = 20L
    private const val MAX_EUR_OFFER_MINOR = 10_000L
}
