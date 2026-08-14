package com.block154.courierpilot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Compatibility stub for installs/bookmarks that referenced the 0.6 Compose dashboard.
 * The old manual Start/End shift UI has been retired; all launches continue into the automatic
 * presence dashboard introduced in 0.7.0.
 */
@Deprecated("Use CourierPilotDashboardActivity")
class CourierPilotComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, CourierPilotDashboardActivity::class.java))
        finish()
    }
}
