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

    @Test
    fun strictParsePrefersMatchingVilniusAddressOverEarlierSameCountryResult() {
        val body = """
            {
              "features": [
                {"properties":{"countrycode":"LT","city":"Kaunas","street":"Smolensko g.","housenumber":"10B","postcode":"44310"},"geometry":{"coordinates":[23.90,54.90]}},
                {"properties":{"countrycode":"LT","city":"Vilnius","street":"Smolensko g.","housenumber":"10B","postcode":"03201"},"geometry":{"coordinates":[25.2589167,54.6732561]}}
              ]
            }
        """.trimIndent()

        val point = PhotonAddressGeocoder.parsePoint(
            body = body,
            countryCode = "LT",
            requestedAddress = "Smolensko gatvė 10 B, Vilnius, 03201",
            cityName = "Vilnius",
            reference = RoutePoint(54.68, 25.28),
        )

        assertNotNull(point)
        assertEquals(54.6732561, point!!.latitude, 0.0000001)
        assertEquals(25.2589167, point.longitude, 0.0000001)
    }

    @Test
    fun poiWithPostcodePrefersMatchingNameInsteadOfNearbyPostcodeFeature() {
        val body = """
            {
              "features": [
                {
                  "properties":{
                    "countrycode":"LT",
                    "city":"Vilnius",
                    "name":"Unrelated place",
                    "housenumber":"03114",
                    "postcode":"03114"
                  },
                  "geometry":{"coordinates":[25.2800,54.6800]}
                },
                {
                  "properties":{
                    "countrycode":"LT",
                    "city":"Vilnius",
                    "name":"Mona Lisa",
                    "street":"A. Vivulskio g.",
                    "housenumber":"34",
                    "postcode":"03114"
                  },
                  "geometry":{"coordinates":[25.2580,54.6740]}
                }
              ]
            }
        """.trimIndent()

        val point = PhotonAddressGeocoder.parsePoint(
            body = body,
            countryCode = "LT",
            requestedAddress = "Šunų kirpimo studija “Mona Lisa”, Vilnius, 03114",
            cityName = "Vilnius",
            reference = RoutePoint(54.6800, 25.2800),
        )

        assertNotNull(point)
        assertEquals(54.6740, point!!.latitude, 0.0000001)
        assertEquals(25.2580, point.longitude, 0.0000001)
    }
}
