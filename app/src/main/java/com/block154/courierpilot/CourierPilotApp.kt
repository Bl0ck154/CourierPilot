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
        // Scheduling only registers Android-owned periodic background work. Network access happens
        // later inside AppUpdateJobService, never on a live-offer process cold start.
        BackgroundAppUpdateScheduler.ensureScheduled(this)
        // This is a no-op unless the user explicitly enabled remote diagnostics. The actual queue
        // read/network work is scheduled on RemoteDiagnostics' dedicated background thread.
        RemoteDiagnostics.resume(this)
    }
}
