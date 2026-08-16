package com.block154.courierpilot

import android.content.Context

/** Permanently removes one address and all local metadata tied to it. */
internal object AddressDeletion {
    private const val DELIVERY_MEMORY_PREFS = "courierpilot_delivery_memory"
    private const val ADDRESS_ALIAS_PREFS = "courierpilot_address_aliases_v2"

    fun delete(
        context: Context,
        database: CourierMetaDatabase,
        address: AddressRecord,
    ): Boolean {
        val db = database.writableDatabase
        var deleted = false
        db.beginTransaction()
        try {
            // access_codes predates addresses and has no foreign key, so remove it explicitly.
            db.delete("access_codes", "building_key = ?", arrayOf(address.buildingKey))
            deleted = db.delete("addresses", "id = ?", arrayOf(address.id.toString())) > 0
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (deleted) {
            // Observations/entities cascade from addresses. Clear small derived caches so a deleted
            // bad row cannot be resurrected through a stale alias or last-address fallback.
            context.getSharedPreferences(ADDRESS_ALIAS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            context.getSharedPreferences(DELIVERY_MEMORY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            AccessCodeSuggestions.clear(context)
        }
        return deleted
    }
}
