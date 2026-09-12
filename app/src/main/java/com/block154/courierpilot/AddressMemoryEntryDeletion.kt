package com.block154.courierpilot

import android.content.ContentValues

/** Granular deletion for derived address-memory fields without destroying raw screen evidence. */
internal object AddressMemoryEntryDeletion {
    fun deleteCustomer(
        database: CourierMetaDatabase,
        addressId: Long,
        customerKey: String,
    ): Int {
        val key = customerKey.trim()
        if (addressId <= 0L || key.isBlank()) return 0

        val db = database.writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            deleted = db.delete(
                "address_entities",
                "address_id = ? AND entity_type = ? AND normalized_name = ?",
                arrayOf(addressId.toString(), CourierMetaDatabase.ENTITY_CUSTOMER, key),
            )

            if (deleted > 0) {
                var newestRemainingCustomer: String? = null
                db.query(
                    "address_entities",
                    arrayOf("display_name"),
                    "address_id = ? AND entity_type = ?",
                    arrayOf(addressId.toString(), CourierMetaDatabase.ENTITY_CUSTOMER),
                    null,
                    null,
                    "last_seen_at DESC, id DESC",
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) newestRemainingCustomer = cursor.getString(0)
                }

                db.update(
                    "addresses",
                    ContentValues().apply {
                        newestRemainingCustomer?.let { put("latest_customer_name", it) }
                            ?: putNull("latest_customer_name")
                    },
                    "id = ?",
                    arrayOf(addressId.toString()),
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deleted
    }

    fun clearLatestDeliveryInfo(
        database: CourierMetaDatabase,
        addressId: Long,
    ): Boolean {
        if (addressId <= 0L) return false
        return database.writableDatabase.update(
            "addresses",
            ContentValues().apply { putNull("latest_details") },
            "id = ?",
            arrayOf(addressId.toString()),
        ) > 0
    }
}
