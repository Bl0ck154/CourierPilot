package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AccessCodeNotifierTest {
    @Test
    fun savedAddressNotificationTargetsAddressDetailsWithSourceCodes() {
        val context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val rawAddress = "Notifierio g. 977"
        database.saveAddressObservation(
            address = rawAddress,
            platform = "Wolt",
            customerName = "Jelena",
            detailsText = "Door code: *2580*",
            rawText = "Dropoff to\nJelena\nNotifierio g. 977\nDoor code\n*2580*\nFloor\n1",
            now = 1_000L,
        )
        val savedAddress = requireNotNull(database.findAddressForDisplayAddress(rawAddress))
        val suggestion = AccessCodeSuggestion(
            displayAddress = savedAddress.displayAddress,
            codes = listOf(" *2580* ", "90KEY", "*2580*"),
            platform = "Wolt",
            updatedAt = 2_000L,
        )

        val intent = AccessCodeNotifier.navigationIntent(context, suggestion)

        assertEquals(AddressDetailsActivity::class.java.name, intent.component?.className)
        assertEquals(
            savedAddress.id,
            intent.getLongExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, -1L),
        )
        assertEquals(
            listOf("*2580*", "90KEY"),
            intent.getStringArrayListExtra(AddressDetailsActivity.EXTRA_ACCESS_CODES),
        )
    }

    @Test
    fun unknownAddressNotificationFallsBackToDashboard() {
        val context = RuntimeEnvironment.getApplication()
        val suggestion = AccessCodeSuggestion(
            displayAddress = "Definitely Missing Street 987654",
            codes = listOf("1234"),
            platform = "Wolt",
            updatedAt = 2_000L,
        )

        val intent = AccessCodeNotifier.navigationIntent(context, suggestion)

        assertEquals(CourierPilotDashboardActivity::class.java.name, intent.component?.className)
    }
}
