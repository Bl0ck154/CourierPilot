package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BoltNativeCurrencySanitizerTest {
    @Test
    fun keepsLastPlnBottomCardPriceAndDropsEarlierAccountMoney() {
        val raw = """
            PLN 120.00
            Today's earnings
            Map
            Sushi House
            Marszałkowska 10, Warszawa
            12 min
            19,50 PLN
            Accept
            Decline
        """.trimIndent()

        val sanitized = BoltOfferTextSanitizer.sanitizeStoredRawText(raw)
        val parsed = OfferParser.parse(sanitized)

        assertFalse(sanitized.contains("PLN 120.00"))
        assertEquals(MoneyAmount(1_950, "PLN", 2), parsed.money)
    }

    @Test
    fun keepsLastGbpBottomCardPriceAndDropsEarlierAccountMoney() {
        val raw = """
            £54.00
            Today's earnings
            Map
            Curry House
            12 King Street, London
            9 min
            £6.80
            Accept
            Decline
        """.trimIndent()

        val sanitized = BoltOfferTextSanitizer.sanitizeStoredRawText(raw)
        val parsed = OfferParser.parse(sanitized)

        assertFalse(sanitized.contains("£54.00"))
        assertEquals(MoneyAmount(680, "GBP", 2), parsed.money)
    }
}
