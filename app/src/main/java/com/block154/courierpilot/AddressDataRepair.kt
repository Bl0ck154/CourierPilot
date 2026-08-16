package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context

/**
 * Re-runs canonical building merges when address normalization rules evolve.
 *
 * This deliberately lives outside the SQLite schema version: changing how apartment/post-code
 * variants map to one building must also repair already-saved rows when no table shape changed.
 */
internal object AddressDataRepair {
    private const val PREFS = "courierpilot_address_repairs"
    private const val KEY_REVISION = "canonical_revision"
    private const val CURRENT_REVISION = 2
    private const val OBSERVATION_BURST_MS = 2L * 60L * 1000L

    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return

        repair(appContext)
        prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply()
    }

    private fun repair(context: Context) {
        data class AddressRow(
            val id: Long,
            val platform: String,
            val firstSeenAt: Long,
            val lastSeenAt: Long,
            val seenCount: Int,
            val latestCustomer: String?,
            val latestDetails: String?,
            val latestRaw: String?,
            val canonicalKey: String,
            val canonicalDisplay: String,
        )

        data class CodeRow(
            val id: Long,
            val code: String,
            val platform: String,
            val firstSeenAt: Long,
            val lastSeenAt: Long,
            val seenCount: Int,
            val canonicalKey: String,
            val canonicalDisplay: String,
        )

        val db = CourierMetaDatabase.get(context).writableDatabase
        val addresses = mutableListOf<AddressRow>()
        db.query("addresses", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val normalized = DeliveryAddressNormalizer.normalize(display) ?: continue
                fun nullable(name: String): String? {
                    val index = cursor.getColumnIndexOrThrow(name)
                    return if (cursor.isNull(index)) null else cursor.getString(index)
                }
                addresses += AddressRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                    firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                    lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                    seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
                    latestCustomer = nullable("latest_customer_name"),
                    latestDetails = nullable("latest_details"),
                    latestRaw = nullable("latest_raw_text"),
                    canonicalKey = normalized.first,
                    canonicalDisplay = normalized.second,
                )
            }
        }

        val codes = mutableListOf<CodeRow>()
        db.query("access_codes", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val normalized = DeliveryAddressNormalizer.normalize(display) ?: continue
                codes += CodeRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    code = cursor.getString(cursor.getColumnIndexOrThrow("code")),
                    platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                    firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                    lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                    seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
                    canonicalKey = normalized.first,
                    canonicalDisplay = normalized.second,
                )
            }
        }

        db.beginTransaction()
        try {
            addresses.forEach { row ->
                db.update(
                    "addresses",
                    ContentValues().apply { put("building_key", "__canonical_address_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }
            codes.forEach { row ->
                db.update(
                    "access_codes",
                    ContentValues().apply { put("building_key", "__canonical_code_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }

            addresses.groupBy { it.canonicalKey }.values.forEach { group ->
                val survivor = group.minByOrNull { it.id } ?: return@forEach
                val latest = group.maxByOrNull { it.lastSeenAt } ?: survivor

                group.filter { it.id != survivor.id }.forEach { duplicate ->
                    db.execSQL(
                        "UPDATE OR IGNORE address_entities SET address_id = ? WHERE address_id = ?",
                        arrayOf(survivor.id, duplicate.id),
                    )
                    db.execSQL(
                        "UPDATE address_observations SET address_id = ? WHERE address_id = ?",
                        arrayOf(survivor.id, duplicate.id),
                    )
                    db.delete("addresses", "id = ?", arrayOf(duplicate.id.toString()))
                }

                db.update(
                    "addresses",
                    ContentValues().apply {
                        put("building_key", survivor.canonicalKey)
                        put("display_address", latest.canonicalDisplay)
                        put("platform", latest.platform)
                        put("first_seen_at", group.minOf { it.firstSeenAt })
                        put("last_seen_at", group.maxOf { it.lastSeenAt })
                        put("seen_count", group.sumOf { it.seenCount }.coerceAtLeast(1))
                        latest.latestCustomer?.let { put("latest_customer_name", it) }
                        latest.latestDetails?.let { put("latest_details", it) }
                        latest.latestRaw?.let { put("latest_raw_text", it) }
                    },
                    "id = ?",
                    arrayOf(survivor.id.toString()),
                )
            }

            codes.groupBy { "${it.canonicalKey}|${it.code}" }.values.forEach { group ->
                val survivor = group.minByOrNull { it.id } ?: return@forEach
                val latest = group.maxByOrNull { it.lastSeenAt } ?: survivor
                group.filter { it.id != survivor.id }.forEach { duplicate ->
                    db.delete("access_codes", "id = ?", arrayOf(duplicate.id.toString()))
                }
                db.update(
                    "access_codes",
                    ContentValues().apply {
                        put("building_key", survivor.canonicalKey)
                        put("display_address", latest.canonicalDisplay)
                        put("platform", latest.platform)
                        put("first_seen_at", group.minOf { it.firstSeenAt })
                        put("last_seen_at", group.maxOf { it.lastSeenAt })
                        put("seen_count", group.sumOf { it.seenCount }.coerceAtLeast(1))
                    },
                    "id = ?",
                    arrayOf(survivor.id.toString()),
                )
            }

            // Accessibility can emit several progressively richer frames for the same offer. These
            // are screen observations, not physical visits, so keep one row per address/platform
            // burst even when raw text differs between frames.
            db.execSQL(
                """
                DELETE FROM address_observations
                WHERE id IN (
                    SELECT newer.id
                    FROM address_observations AS newer
                    JOIN address_observations AS older
                      ON older.address_id = newer.address_id
                     AND older.platform = newer.platform
                     AND older.id < newer.id
                     AND ABS(older.seen_at - newer.seen_at) <= $OBSERVATION_BURST_MS
                )
                """.trimIndent()
            )

            // After canonical rows merge, `seen_count` must describe distinct captured screen bursts,
            // not the sum of duplicate legacy rows such as address-with-postcode + address-without.
            db.execSQL(
                """
                UPDATE addresses
                SET seen_count = CASE
                    WHEN EXISTS (
                        SELECT 1 FROM address_observations ao WHERE ao.address_id = addresses.id
                    ) THEN (
                        SELECT COUNT(*) FROM address_observations ao WHERE ao.address_id = addresses.id
                    )
                    ELSE MAX(seen_count, 1)
                END
                """.trimIndent()
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
