package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotonAddressGeocoderTest {
    @Test
    fun parsePointPrefersRequestedCountry() {
        val body = """
            {
              "features": [
                {"properties":{"countrycode":"PL"},"geometry":{"coordinates":[21.0,52.0]}},
                {"properties":{"countrycode":"LT"},"geometry":{"coordinates":[25.2849119,54.6784386]}}
              ]
            }
        """.trimIndent()

        val point = PhotonAddressGeocoder.parsePoint(body, "LT")

        assertNotNull(point)
        assertEquals(54.6784386, point!!.latitude, 0.0000001)
        assertEquals(25.2849119, point.longitude, 0.0000001)
    }
}
