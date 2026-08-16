package com.block154.courierpilot

import android.content.Context
import kotlin.math.ceil

/** Backfills address memory and address-linked people/venues without blocking app startup. */
internal object AddressBackfill {
    private const val PREFS = "courierpilot_address_backfill"
    private const val KEY_REVISION = "revision"
    private const val CURRENT_REVISION = 5
    private const val PAGE_SIZE = 200

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fromRevision = prefs.getInt(KEY_REVISION, 0)
        if (fromRevision >= CURRENT_REVISION) return

        Thread({
            runCatching { run(appContext, fromRevision) }
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

    private fun run(context: Context, fromRevision: Int) {
        val offers = OfferDatabase.get(context)
        val meta = CourierMetaDatabase.get(context)

        // The old in-database repair uses the legacy strict parser. v0.11.4 has one source of truth:
        // AddressDataRepair + AddressMemoryResolver.
        AddressDataRepair.runIfNeeded(context)

        val total = offers.offerCount()
        if (total <= 0) return

        val pageCount = ceil(total / PAGE_SIZE.toDouble()).toInt()
        for (page in pageCount - 1 downTo 0) {
            val records = offers.searchPage("", PAGE_SIZE, page * PAGE_SIZE)
                .map { it.withCurrentParsedStructure() }
                .sortedBy { it.capturedAt }
            records.forEach { record ->
                if (fromRevision < 1) backfillFullRecord(context, meta, record)
                else backfillEntitiesOnly(context, meta, record)
            }
        }
    }

    private fun backfillFullRecord(context: Context, meta: CourierMetaDatabase, record: OfferRecord) {
        record.pickupAddresses.forEachIndexed { index, address ->
            val result = AddressMemoryResolver.saveObservation(
                context = context,
                database = meta,
                address = address,
                platform = record.platform,
                customerName = null,
                detailsText = record.merchantNames.getOrNull(index)?.let { "Pickup · $it" },
                rawText = record.rawText,
                now = record.capturedAt,
            ) ?: return@forEachIndexed
            val venue = record.merchantNames.getOrNull(index)
                ?: record.restaurant.takeIf { record.pickupAddresses.size <= 1 }
            if (!venue.isNullOrBlank()) {
                meta.saveAddressEntity(
                    result.addressId,
                    CourierMetaDatabase.ENTITY_VENUE,
                    venue,
                    record.platform,
                    record.capturedAt,
                )
            }
        }
        record.dropoffAddresses.forEachIndexed { index, address ->
            val customer = record.customerNames.getOrNull(index)
                ?.takeUnless { it.equals("Customer", ignoreCase = true) }
            val result = AddressMemoryResolver.saveObservation(
                context = context,
                database = meta,
                address = address,
                platform = record.platform,
                customerName = customer,
                detailsText = "Drop-off from captured offer",
                rawText = record.rawText,
                now = record.capturedAt,
            ) ?: return@forEachIndexed
            if (!customer.isNullOrBlank()) {
                meta.saveAddressEntity(
                    result.addressId,
                    CourierMetaDatabase.ENTITY_CUSTOMER,
                    customer,
                    record.platform,
                    record.capturedAt,
                )
            }
        }
    }

    /** Existing installs already have observations; relink entities without recounting observations. */
    private fun backfillEntitiesOnly(context: Context, meta: CourierMetaDatabase, record: OfferRecord) {
        record.pickupAddresses.forEachIndexed { index, address ->
            val addressId = AddressMemoryResolver.findSaved(context, meta, address)?.id ?: return@forEachIndexed
            val venue = record.merchantNames.getOrNull(index)
                ?: record.restaurant.takeIf { record.pickupAddresses.size <= 1 }
            if (!venue.isNullOrBlank()) {
                meta.saveAddressEntity(
                    addressId,
                    CourierMetaDatabase.ENTITY_VENUE,
                    venue,
                    record.platform,
                    record.capturedAt,
                )
            }
        }
        record.dropoffAddresses.forEachIndexed { index, address ->
            val addressId = AddressMemoryResolver.findSaved(context, meta, address)?.id ?: return@forEachIndexed
            val customer = record.customerNames.getOrNull(index)
                ?.takeUnless { it.equals("Customer", ignoreCase = true) }
            if (!customer.isNullOrBlank()) {
                meta.saveAddressEntity(
                    addressId,
                    CourierMetaDatabase.ENTITY_CUSTOMER,
                    customer,
                    record.platform,
                    record.capturedAt,
                )
            }
        }
    }
}
