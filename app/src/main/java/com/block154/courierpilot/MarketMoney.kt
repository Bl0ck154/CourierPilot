package com.block154.courierpilot

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

data class MoneyAmount(val amountMinor: Long, val currencyCode: String, val fractionDigits: Int) {
    init { require(currencyCode.matches(Regex("[A-Z]{3}"))); require(fractionDigits >= 0) }
    fun major(): BigDecimal = BigDecimal.valueOf(amountMinor, fractionDigits)
}

object MarketCurrencyParser {
    private val symbols = mapOf("€" to "EUR", "£" to "GBP", "zł" to "PLN", "₴" to "UAH", "kr" to "SEK")
    fun parse(text: String, locale: Locale = Locale.getDefault()): MoneyAmount? {
        val code = Regex("(?i)\\b([A-Z]{3})\\b").find(text)?.groupValues?.get(1)?.uppercase()
            ?: symbols.entries.firstOrNull { text.contains(it.key) }?.value
            ?: return null
        val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return null
        val number = Regex("(?:[0-9]{1,3}(?:[ ,.]\\d{3})*|[0-9]+)(?:[.,]\\d+)?").find(text)?.value ?: return null
        val normalized = if (number.contains(',') && number.contains('.')) number.replace(",", "") else number.replace(',', '.')
        val value = normalized.replace(" ", "").toBigDecimalOrNull() ?: return null
        val digits = currency.defaultFractionDigits.coerceAtLeast(0)
        return runCatching { MoneyAmount(value.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact(), code, digits) }.getOrNull()
    }
}
