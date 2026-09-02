package com.block154.courierpilot

import org.junit.Assert.*
import org.junit.Test

class MarketMoneyV2Test {
    @Test fun parsesCurrenciesAndFractionDigits() {
        assertEquals(MoneyAmount(438, "EUR", 2), MarketCurrencyParser.parse("€4.38"))
        assertEquals(MoneyAmount(620, "JPY", 0), MarketCurrencyParser.parse("JPY 620"))
        assertEquals(MoneyAmount(1250, "KWD", 3), MarketCurrencyParser.parse("KWD 1.250"))
    }
    @Test fun unknownCurrencyIsIneligible() { assertNull(MarketCurrencyParser.parse("12.50 ???")) }

    @Test fun offerParserCarriesNativeCurrencyIntoParsedOffer() {
        val parsed = OfferParser.parse("16 min, 19,50 PLN\nAccept")
        assertEquals(1950, parsed.priceCents)
        assertEquals(MoneyAmount(1950, "PLN", 2), parsed.money)
    }

    @Test fun ambiguousCurrencyIsNotGuessed() {
        val parsed = OfferParser.parse("16 min, $19.50\nAccept")
        assertNull(parsed.money)
        assertNull(parsed.priceCents)
    }
}
