package com.block154.courierpilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteResearchDatabaseTest {

    @Test
    fun createsExpectedResearchTablesWithoutTouchingOfferDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("route_research.db")

        val db = RouteResearchDatabase.get(context).writableDatabase
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }

        assertTrue("gps_sessions" in tables)
        assertTrue("gps_samples" in tables)
        assertTrue("matched_edge_traversals" in tables)
        assertTrue("venue_wait_observations" in tables)
        assertTrue("route_validation_samples" in tables)
        assertTrue("bolt_map_ground_truth" in tables)
    }
}
