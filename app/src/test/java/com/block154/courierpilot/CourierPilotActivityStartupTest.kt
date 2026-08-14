package com.block154.courierpilot

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CourierPilotActivityStartupTest {
    @Test
    fun launcherActivityStartsAndResumes() {
        Robolectric.buildActivity(CourierPilotActivity::class.java).setup().use { controller ->
            check(controller.get() != null)
        }
    }
}
