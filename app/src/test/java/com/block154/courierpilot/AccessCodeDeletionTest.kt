package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessCodeDeletionTest {

    @Test
    fun deleteRemovesCanonicalVariantsButKeepsOtherHints() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val buildingKey = "delete-test-${System.nanoTime()}"
        val displayAddress = "Delete Test 1"

        database.saveAccessCode(
            AccessCodeObservation(buildingKey, displayAddress, "12 34"),
            platform = "Wolt",
        )
        database.saveAccessCode(
            AccessCodeObservation(buildingKey, displayAddress, "1234"),
            platform = "Bolt",
        )
        database.saveAccessCode(
            AccessCodeObservation(buildingKey, displayAddress, "9876#"),
            platform = "Wolt",
        )

        assertEquals(2, AccessCodeDeletion.delete(database, buildingKey, " 1234 "))

        val remaining = database.codesForBuilding(buildingKey, limit = 20)
        assertEquals(listOf("9876#"), remaining.map { it.code })
        assertTrue(AccessCodeDeletion.equivalent(" 12 34 ", "1234"))
    }
}
