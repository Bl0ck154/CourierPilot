package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotonReverseAddressTest {
    @Test fun parsesStreetAndHouseFromPhotonFixture() {
        val json = """{"features":[{"properties":{"street":"Pilies gatvė","housenumber":"12","city":"Vilnius","country":"Lithuania"},"geometry":{"coordinates":[25.28,54.68]}}]}"""
        assertEquals("Pilies gatvė 12", PhotonAddressGeocoder.parseReverseAddress(json))
    }

    @Test fun missingStreetFailsClosed() {
        assertNull(PhotonAddressGeocoder.parseReverseAddress("""{"features":[]}"""))
        assertNull(PhotonAddressGeocoder.parseReverseAddress("{}"))
    }
}
