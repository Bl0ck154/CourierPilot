package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketCityResolverTest {
    @Test
    fun citySlugNormalizesLithuanianAccents() {
        assertEquals("siauliai", MarketCityResolver.citySlug("Šiauliai"))
        assertEquals("vilnius", MarketCityResolver.citySlug("Vilnius"))
    }

    @Test
    fun citySlugProducesStableServerKeyPart() {
        assertEquals("new-york", MarketCityResolver.citySlug("New York"))
        assertEquals("sao-paulo", MarketCityResolver.citySlug("São Paulo"))
    }
}
