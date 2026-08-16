package com.block154.courierpilot

import android.app.Application

class CourierPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AddressDataRepair.runIfNeeded(this)
        OfferDataRepair.runIfNeeded(this)
        AddressBackfill.schedule(this)
        DayRolloverUiRefresh.install(this)
        HeartbeatScheduler.ensureScheduled(this)
    }
}
