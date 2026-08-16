package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context

/**
 * Re-runs canonical building merges when address identity rules evolve.
 *
 * Revision 3 introduced broad compact-address matching. Revision 4 keeps those useful aliases but
 * removes obvious courier-UI metadata (`Bag/Unit 1`, `Apartment 18`, `Floor 2`, etc.) that older
 * builds could accidentally persist as buildings.
 */
internal object AddressDataRepair {
    private const val PREFS = "courierpilot_address_repairs"
    private const val KEY_REVISION = "canonical_revision"
    private const val CURRENT_REVISION = 4
    private const val OBSERVATION_BURST_MS = 2L * 60L * 1000L

    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return
        repair(appContext)
        prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply()
    }

    private data class ArtifactRow(
        val id: Long,
        val buildingKey: String,
    )

    private data class AddressRow(
        val id: Long,
        val display: String,
        val platform: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val seenCount: Int,
        val latestCustomer: String?,
        val latestDetails: String?,
        val latestRaw: String?,
        val identity: DeliveryAddressIdentity,
    )

    private data class CodeRow(
        val id: Long,
        val display: String,
        val code: String,
        val platform: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val seenCount: Int,
        val identity: DeliveryAddressIdentity,
    )

    private fun repair(context: Context) {
        val db = CourierMetaDatabase.get(context).writableDatabase
        val artifacts = mutableListOf<ArtifactRow>()
        val addresses = mutableListOf<AddressRow>()
        db.query("addresses", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val display = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val buildingKey = cursor.getString(cursor.getColumnIndexOrThrow("building_key"))
                if (DeliveryAddressNormalizer.isRejectedAddressArtifact(display)) {
                    artifacts += ArtifactRow(id, buildingKey)
                    continue
                }
                val identity = DeliveryAddressNormalizer.identity(display) ?: continue
                fun nullable(name: String): String? {
                    val index = cursor.getColumnIndexOrThrow(name)
                    return if (cursor.isNull(index)) null else cursor.getString(index)
                }
                addresses += AddressRow(
                    id = id,
                    display = display,
                    platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                    firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                    lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                    seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
                    latestCustomer = nullable("latest_customer_name"),
                    latestDetails = nullable("latest_details"),
                    latestRaw = nullable("latest_raw_text"),
                    identity = identity,
                )
            }
        }

        val codes = mutableListOf<CodeRow>()
        db.query("access_codes", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                if (DeliveryAddressNormalizer.isRejectedAddressArtifact(display)) continue
                val identity = DeliveryAddressNormalizer.identity(display) ?: continue
                codes += CodeRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    display = display,
                    code = cursor.getString(cursor.getColumnIndexOrThrow("code")),
                    platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                    firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                    lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                    seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
                    identity = identity,
                )
            }
        }

        val addressGroups = fuzzyGroups(addresses) { left, right ->
            left.identity.houseNumber.equals(right.identity.houseNumber, ignoreCase = true) &&
                DeliveryAddressNormalizer.isLikelySameBuilding(left.display, right.display)
        }
        val codeGroups = fuzzyGroups(codes) { left, right ->
            left.code == right.code &&
                left.identity.houseNumber.equals(right.identity.houseNumber, ignoreCase = true) &&
                DeliveryAddressNormalizer.isLikelySameBuilding(left.display, right.display)
        }

        db.beginTransaction()
        try {
            // Remove the accidental rows and everything that only belonged to those fake buildings.
            // Do this explicitly instead of depending on OEM SQLite foreign-key settings.
            artifacts.forEach { artifact ->
                db.delete("address_entities", "address_id = ?", arrayOf(artifact.id.toString()))
                db.delete("address_observations", "address_id = ?", arrayOf(artifact.id.toString()))
                db.delete("access_codes", "building_key = ?", arrayOf(artifact.buildingKey))
                db.delete("addresses", "id = ?", arrayOf(artifact.id.toString()))
            }

            addresses.forEach { row ->
                db.update(
                    "addresses",
                    ContentValues().apply { put("building_key", "__address_identity_v4_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }
            codes.forEach { row ->
                db.update(
                    "access_codes",
                    ContentValues().apply { put("building_key", "__code_identity_v4_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }

            addressGroups.forEach { group ->
                val survivor = group.minByOrNull { it.id } ?: return@forEach
                val latest = group.maxByOrNull { it.lastSeenAt } ?: survivor
                val preferredDisplay = group.map { it.display }.reduce(DeliveryAddressNormalizer::preferredDisplay)
                val canonical = DeliveryAddressNormalizer.identity(preferredDisplay) ?: survivor.identity

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
                        put("building_key", canonical.key)
                        put("display_address", canonical.display)
                        put("platform", latest.platform)
                        put("first_seen_at", group.minOf { it.firstSeenAt })
                        put("last_seen_at", group.maxOf { it.lastSeenAt })
                        put("seen_count", group.maxOf { it.seenCount }.coerceAtLeast(1))
                        latest.latestCustomer?.let { put("latest_customer_name", it) }
                        latest.latestDetails?.let { put("latest_details", it) }
                        latest.latestRaw?.let { put("latest_raw_text", it) }
                    },
                    "id = ?",
                    arrayOf(survivor.id.toString()),
                )
            }

            codeGroups.forEach { group ->
                val survivor = group.minByOrNull { it.id } ?: return@forEach
                val latest = group.maxByOrNull { it.lastSeenAt } ?: survivor
                val preferredDisplay = group.map { it.display }.reduce(DeliveryAddressNormalizer::preferredDisplay)
                val canonical = DeliveryAddressNormalizer.identity(preferredDisplay) ?: survivor.identity
                group.filter { it.id != survivor.id }.forEach { duplicate ->
                    db.delete("access_codes", "id = ?", arrayOf(duplicate.id.toString()))
                }
                db.update(
                    "access_codes",
                    ContentValues().apply {
                        put("building_key", canonical.key)
                        put("display_address", canonical.display)
                        put("platform", latest.platform)
                        put("first_seen_at", group.minOf { it.firstSeenAt })
                        put("last_seen_at", group.maxOf { it.lastSeenAt })
                        put("seen_count", group.maxOf { it.seenCount }.coerceAtLeast(1))
                    },
                    "id = ?",
                    arrayOf(survivor.id.toString()),
                )
            }

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

    private fun <T> fuzzyGroups(values: List<T>, matches: (T, T) -> Boolean): List<List<T>> {
        if (values.isEmpty()) return emptyList()
        val parent = IntArray(values.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                if (matches(values[i], values[j])) union(i, j)
            }
        }
        return values.indices.groupBy { root(it) }.values.map { indexes -> indexes.map { values[it] } }
    }
}
