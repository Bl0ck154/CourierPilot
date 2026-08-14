package com.block154.courierpilot

import android.app.Application

class CourierPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OfferDataRepair.runIfNeeded(this)
        AddressBackfill.schedule(this)
        HeartbeatScheduler.ensureScheduled(this)
    }
}
