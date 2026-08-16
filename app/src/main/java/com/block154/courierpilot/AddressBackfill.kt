package com.block154.courierpilot

import android.content.Context

/**
 * Maintains local customer-address memory without replaying old offer cards.
 *
 * Older revisions rebuilt addresses/entities from captured offers. That mixed restaurant pickup
 * addresses, merchant names and parser/UI artifacts into the customer Addresses tab. From revision
 * 7 onward we only repair/clean existing local memory; new addresses come from trusted live
 * customer screens through DeliveryMemory.
 */
internal object AddressBackfill {
    private const val PREFS = "courierpilot_address_backfill"
    private const val KEY_REVISION = "revision"
    private const val CURRENT_REVISION = 7

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fromRevision = prefs.getInt(KEY_REVISION, 0)
        if (fromRevision >= CURRENT_REVISION) return

        Thread({
            runCatching {
                AddressDataRepair.runIfNeeded(appContext)
                AddressMetadataCleanup.run(appContext)
            }.onSuccess {
                prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply()
            }.onFailure {
                CaptureEventLog.append(
                    appContext,
                    stage = "address_backfill_failed",
                    message = it.javaClass.simpleName,
                    dedupeWindowMs = 60_000L,
                )
            }
        }, "CourierPilot-address-maintenance").start()
    }
}
