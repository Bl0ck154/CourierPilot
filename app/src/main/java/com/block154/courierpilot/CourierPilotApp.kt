package com.block154.courierpilot

import android.app.Application

class CourierPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep process startup tiny. Notification/accessibility services may cold-start this process
        // while a live offer is arriving, so historical repair work must never run from Application.
        CaptureServiceRecovery.install(this)
        DayRolloverUiRefresh.install(this)
        HeartbeatScheduler.ensureScheduled(this)
    }
}
