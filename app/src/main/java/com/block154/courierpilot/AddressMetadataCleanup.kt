package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * One-time cleanup for metadata learned by the old offer/backfill pipeline.
 *
 * Address Memory is customer/dropoff-only. Pickup venues do not belong to a customer building, and
 * generic UI labels such as `Address details`, `Notes`, `Info hub` or progress text are not people.
 */
internal object AddressMetadataCleanup {
    fun run(context: Context) {
        val db = CourierMetaDatabase.get(context).writableDatabase
        val venueNamesByAddress = mutableMapOf<Long, MutableSet<String>>()
        db.query(
            "address_entities",
            arrayOf("address_id", "normalized_name"),
            "entity_type = ?",
            arrayOf(CourierMetaDatabase.ENTITY_VENUE),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                venueNamesByAddress.getOrPut(cursor.getLong(0)) { mutableSetOf() } += cursor.getString(1)
            }
        }

        val badCustomerEntityIds = mutableListOf<Long>()
        val removedCustomerNamesByAddress = mutableMapOf<Long, MutableSet<String>>()
        db.query(
            "address_entities",
            arrayOf("id", "address_id", "normalized_name", "display_name"),
            "entity_type = ?",
            arrayOf(CourierMetaDatabase.ENTITY_CUSTOMER),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val addressId = cursor.getLong(1)
                val normalized = cursor.getString(2)
                val display = cursor.getString(3)
                val copiedVenue = normalized in venueNamesByAddress[addressId].orEmpty()
                if (copiedVenue || isUiGarbage(display)) {
                    badCustomerEntityIds += id
                    removedCustomerNamesByAddress.getOrPut(addressId) { mutableSetOf() } += normalized
                }
            }
        }

        // Collect address rows before entering the write transaction. Some OEM SQLite builds are
        // unhappy when the same table is updated while an active cursor is iterating it.
        val clearLatestCustomerIds = mutableListOf<Long>()
        db.query(
            "addresses",
            arrayOf("id", "latest_customer_name"),
            "latest_customer_name IS NOT NULL",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val addressId = cursor.getLong(0)
                val customer = cursor.getString(1) ?: continue
                val normalized = normalize(customer)
                val copiedVenue = normalized in venueNamesByAddress[addressId].orEmpty()
                val removedEntity = normalized in removedCustomerNamesByAddress[addressId].orEmpty()
                if (copiedVenue || removedEntity || isUiGarbage(customer)) {
                    clearLatestCustomerIds += addressId
                }
            }
        }

        db.beginTransaction()
        try {
            badCustomerEntityIds.forEach { id ->
                db.delete("address_entities", "id = ?", arrayOf(id.toString()))
            }
            // Pickup venues were a modelling mistake: the Addresses tab represents customer buildings.
            db.delete(
                "address_entities",
                "entity_type = ?",
                arrayOf(CourierMetaDatabase.ENTITY_VENUE),
            )
            clearLatestCustomerIds.forEach { addressId ->
                db.update(
                    "addresses",
                    ContentValues().apply { putNull("latest_customer_name") },
                    "id = ?",
                    arrayOf(addressId.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun isUiGarbage(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return true
        if (normalized in UI_LABELS) return true
        if (normalized.matches(Regex("^\\d{1,3}%\\s+(?:completed|complete)$"))) return true
        if (normalized.matches(Regex("^(?:step|stage)\\s+\\d+$"))) return true
        return false
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}%]+"), " ")
        .trim()

    private val UI_LABELS = setOf(
        "address details",
        "order details",
        "delivery details",
        "notes",
        "note",
        "info",
        "info hub",
        "items",
        "instructions",
        "additional note",
        "pickup from",
        "dropoff to",
        "customer",
        "view",
        "translate",
        "call",
        "chat",
        "get help",
        "delivery issues",
    )
}
