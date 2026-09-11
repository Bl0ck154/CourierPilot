package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressMemoryUiProjectionTest {
    @Test
    fun equivalentCustomerNamesAcrossPlatformsBecomeOneUiRowWithoutFuzzyMerging() {
        val rows = listOf(
            customer(id = 1, name = "Živilė A.", platform = "Wolt", seen = 3, last = 100),
            customer(id = 2, name = "  zivile   a  ", platform = "Bolt", seen = 2, last = 200),
            customer(id = 3, name = "Zivile Aleks", platform = "Wolt", seen = 1, last = 300),
        )

        val projected = AddressMemoryUiProjection.summarizeCustomers(rows)

        assertEquals(2, projected.size)
        val merged = projected.first { it.key == "zivile a" }
        assertEquals(5, merged.seenCount)
        assertEquals(listOf("Bolt", "Wolt"), merged.platforms)
        assertEquals(200, merged.lastSeenAt)
        assertTrue(projected.any { it.key == "zivile aleks" })
    }

    @Test
    fun apartmentLikeLegacyCodeIsHiddenFromAddressUiButOtherHintRemains() {
        val codes = listOf(
            code(id = 1, value = "145", platform = "Bolt", last = 200),
            code(id = 2, value = "*2580*", platform = "Wolt", last = 100),
        )
        val observations = listOf(
            AddressObservationRecord(
                id = 10,
                addressId = 5,
                seenAt = 300,
                platform = "Bolt",
                customerName = "Indre",
                detailsText = "Apartment: 145",
                rawText = "Address\nTestu g. 1\nApartment, flat or suite number\n145\nFloor\n2",
            )
        )

        val projected = AddressMemoryUiProjection.summarizeCodes(codes, observations)

        assertEquals(listOf("*2580*"), projected.map { it.code })
    }

    @Test
    fun accessHintSourceUsesNewestRawScreenThatActuallyCarriesAccessCue() {
        val observations = listOf(
            AddressObservationRecord(
                id = 12,
                addressId = 5,
                seenAt = 500,
                platform = "Wolt",
                customerName = "Jelena",
                detailsText = "Apartment 2580",
                rawText = "Dropoff to\nJelena\nTestu g. 1\nApartment, flat or suite number\n2580\nFloor\n1",
            ),
            AddressObservationRecord(
                id = 11,
                addressId = 5,
                seenAt = 400,
                platform = "Wolt",
                customerName = "Jelena",
                detailsText = "Entry code *2580*",
                rawText = "Dropoff to\nJelena\nTestu g. 1\nEntry code\n*2580*\nFloor\n1",
            ),
            AddressObservationRecord(
                id = 10,
                addressId = 5,
                seenAt = 300,
                platform = "Bolt",
                customerName = "Jelena",
                detailsText = "Door code *2580*",
                rawText = "Address\nTestu g. 1\nDoor code: *2580*",
            ),
        )

        val source = AddressMemoryUiProjection.findAccessHintSource(observations, listOf("*2580*"))

        assertEquals(11L, source?.id)
    }

    @Test
    fun accessHintSourceDoesNotTreatBareNumericCoincidenceAsProof() {
        val observations = listOf(
            AddressObservationRecord(
                id = 20,
                addressId = 5,
                seenAt = 600,
                platform = "Wolt",
                customerName = "Jelena",
                detailsText = "Apartment 145",
                rawText = "Dropoff to\nJelena\nTestu g. 1\nApartment, flat or suite number\n145\nFloor\n1",
            )
        )

        val source = AddressMemoryUiProjection.findAccessHintSource(observations, listOf("145"))

        assertNull(source)
    }

    private fun customer(
        id: Long,
        name: String,
        platform: String,
        seen: Int,
        last: Long,
    ) = AddressEntityRecord(
        id = id,
        addressId = 7,
        entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
        name = name,
        platform = platform,
        firstSeenAt = 10,
        lastSeenAt = last,
        seenCount = seen,
    )

    private fun code(
        id: Long,
        value: String,
        platform: String,
        last: Long,
    ) = AccessCodeRecord(
        id = id,
        buildingKey = "testu g 1",
        displayAddress = "Testu g. 1",
        code = value,
        platform = platform,
        firstSeenAt = 10,
        lastSeenAt = last,
        seenCount = 1,
    )
}
