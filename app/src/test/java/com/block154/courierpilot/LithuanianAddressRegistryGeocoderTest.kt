package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LithuanianAddressRegistryGeocoderTest {
    @Test
    fun parsesCompactWoltHouseAndPostcode() {
        val compact = LithuanianAddressRegistryGeocoder.parseHouseAndPostcode("55, Vilnius, 09110")
        assertEquals("55", compact?.houseNumber)
        assertEquals("09110", compact?.postcode)
    }

    @Test
    fun stripsApartmentSuffixBeforeRegistryLookup() {
        val compact = LithuanianAddressRegistryGeocoder.parseHouseAndPostcode("55-12, Vilnius, LT-09110")
        assertEquals("55", compact?.houseNumber)
        assertEquals("09110", compact?.postcode)
    }

    @Test
    fun acceptsOnlyOneMatchingRegistryAddress() {
        val body = """
            {
              "total": 1,
              "items": [{
                "plot_or_building_number": "55",
                "postal_code": "LT-09110",
                "street": {"full_name": "Žirmūnų g."},
                "residential_area": {"name": "Vilniaus m."},
                "municipality": {"name": "Vilniaus m. sav."},
                "geometry": {"data": "SRID=4326;POINT(25.30764514880538 54.71617140258593)"}
              }]
            }
        """.trimIndent()
        val compact = LithuanianAddressRegistryGeocoder.CompactAddress("55", "09110")
        val city = MarketCity("lt-vilnius", "Vilnius", "LT", 1L)

        val result = LithuanianAddressRegistryGeocoder.parseResponse(body, compact, city)
        assertNotNull(result)
        assertEquals(54.71617140258593, result!!.point.latitude, 0.0000001)
        assertEquals(25.30764514880538, result.point.longitude, 0.0000001)
        assertEquals("Žirmūnų g. 55, Vilnius, LT-09110", result.displayAddress)
    }
}
