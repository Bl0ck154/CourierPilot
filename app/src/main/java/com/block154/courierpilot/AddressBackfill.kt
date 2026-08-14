package com.block154.courierpilot

import android.content.Context
import kotlin.math.ceil

/** Backfills the new address memory from offer history once, without blocking app startup. */
internal object AddressBackfill {
    private const val PREFS = "courierpilot_address_backfill"
    private const val KEY_REVISION = "revision"
    private const val CURRENT_REVISION = 1
    private const val PAGE_SIZE = 200

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return

        Thread({
            runCatching { run(appContext) }
                .onSuccess { prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply() }
                .onFailure {
                    CaptureEventLog.append(
                        appContext,
                        stage = "address_backfill_failed",
                        message = it.javaClass.simpleName,
                        dedupeWindowMs = 60_000L,
                    )
                }
        }, "CourierPilot-address-backfill").start()
    }

    private fun run(context: Context) {
        val offers = OfferDatabase.get(context)
        val meta = CourierMetaDatabase.get(context)
        val total = offers.offerCount()
        if (total <= 0) return

        // Offer pages are newest-first. Process the oldest page first so first/last-seen metadata
        // stays chronological when a building appears in several historical offers.
        val pageCount = ceil(total / PAGE_SIZE.toDouble()).toInt()
        for (page in pageCount - 1 downTo 0) {
            val records = offers.searchPage("", PAGE_SIZE, page * PAGE_SIZE)
                .map { it.withCurrentParsedStructure() }
                .sortedBy { it.capturedAt }
            records.forEach { record -> backfillRecord(meta, record) }
        }
    }

    private fun backfillRecord(meta: CourierMetaDatabase, record: OfferRecord) {
        record.pickupAddresses.forEachIndexed { index, address ->
            meta.saveAddressObservation(
                address = address,
                platform = record.platform,
                customerName = null,
                detailsText = record.merchantNames.getOrNull(index)?.let { "Pickup · $it" },
                rawText = record.rawText,
                now = record.capturedAt,
            )
        }
        record.dropoffAddresses.forEachIndexed { index, address ->
            meta.saveAddressObservation(
                address = address,
                platform = record.platform,
                customerName = record.customerNames.getOrNull(index)
                    ?.takeUnless { it.equals("Customer", ignoreCase = true) },
                detailsText = "Drop-off from captured offer",
                rawText = record.rawText,
                now = record.capturedAt,
            )
        }
    }
}
