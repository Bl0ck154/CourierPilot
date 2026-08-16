package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.Locale

internal data class SmartAddressSaveResult(
    val addressId: Long,
    val buildingKey: String,
    val displayAddress: String,
    val inserted: Boolean,
    val localAliasMatched: Boolean,
)

/**
 * One entry point for address-memory writes.
 *
 * CourierMetaDatabase predates the broader address identity rules and still has a deliberately
 * strict parser. This layer resolves aliases/fuzzy local matches first and writes by address id, so
 * compact forms such as `Vokiečių 7`, postcode variants and minor Latin-script typos cannot create
 * a second building row. Cross-language translations are handled later by AddressGeoAliasResolver.
 *
 * Every write must now carry AddressEvidenceSource. This is the durable safety boundary: OCR or a
 * one-frame compact guess cannot create a building simply because a parser returned a string.
 */
internal object AddressMemoryResolver {
    private const val PREFS = "courierpilot_address_aliases_v2"
    private const val ALIAS_PREFIX = "alias_"
    private const val FIELD_SEPARATOR = "\u001F"
    private const val RAW_OBSERVATION_DEDUPE_MS = 2L * 60L * 1000L
    private const val MAX_DETAILS_CHARS = 8_000
    private const val MAX_RAW_CHARS = 16_000
    private const val MAX_LOCAL_ADDRESSES = 1_000

    fun canonicalize(
        context: Context,
        database: CourierMetaDatabase,
        rawAddress: String,
    ): Pair<String, String>? {
        if (DeliveryAddressNormalizer.isRejectedAddressArtifact(rawAddress)) return null
        val normalized = DeliveryAddressNormalizer.normalize(rawAddress) ?: return null
        val existing = findSaved(context, database, rawAddress)
        if (existing != null) {
            rememberAlias(context, normalized.first, existing.buildingKey, existing.displayAddress)
            return existing.buildingKey to existing.displayAddress
        }
        return normalized
    }

    fun findSaved(
        context: Context,
        database: CourierMetaDatabase,
        rawAddress: String,
    ): AddressRecord? {
        if (DeliveryAddressNormalizer.isRejectedAddressArtifact(rawAddress)) return null
        val normalized = DeliveryAddressNormalizer.normalize(rawAddress) ?: return null
        resolveRememberedAlias(context, database, normalized.first)?.let { return it }

        val candidates = loadAddresses(database)
        candidates.firstOrNull { it.buildingKey == normalized.first }?.let {
            rememberAlias(context, normalized.first, it.buildingKey, it.displayAddress)
            return it
        }
        candidates.firstOrNull { DeliveryAddressNormalizer.key(it.displayAddress) == normalized.first }?.let {
            rememberAlias(context, normalized.first, it.buildingKey, it.displayAddress)
            return it
        }

        val scored = candidates.mapNotNull { candidate ->
            val score = DeliveryAddressNormalizer.matchScore(rawAddress, candidate.displayAddress)
            score.takeIf { it >= 0.86 }?.let { candidate to it }
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)
        val uniqueEnough = best.second >= 0.94 || runnerUp == null || best.second - runnerUp.second >= 0.08
        if (!uniqueEnough) return null

        rememberAlias(context, normalized.first, best.first.buildingKey, best.first.displayAddress)
        return best.first
    }

    fun saveObservation(
        context: Context,
        database: CourierMetaDatabase,
        address: String,
        platform: String,
        customerName: String?,
        detailsText: String?,
        rawText: String,
        evidence: AddressEvidenceSource,
        now: Long = System.currentTimeMillis(),
    ): SmartAddressSaveResult? {
        if (DeliveryAddressNormalizer.isRejectedAddressArtifact(address)) return null
        val normalized = DeliveryAddressNormalizer.normalize(address) ?: return null
        val existing = findSaved(context, database, address)

        if (existing == null && !evidence.canCreateAddress(address)) return null
        if (existing != null && !evidence.canUpdateExisting) return null

        val db = database.writableDatabase
        val safeCustomer = customerName?.trim()?.takeIf(String::isNotEmpty)?.take(240)
        val safeDetails = detailsText?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_DETAILS_CHARS)
        val safeRaw = rawText.trim().take(MAX_RAW_CHARS)

        val addressId: Long
        val buildingKey: String
        val chosenDisplay: String
        val inserted: Boolean
        val localAliasMatched = existing != null && existing.buildingKey != normalized.first

        if (existing == null) {
            buildingKey = uniqueBuildingKey(db, normalized.first)
            chosenDisplay = normalized.second
            addressId = db.insertOrThrow(
                "addresses",
                null,
                ContentValues().apply {
                    put("building_key", buildingKey)
                    put("display_address", chosenDisplay)
                    put("platform", platform)
                    put("first_seen_at", now)
                    put("last_seen_at", now)
                    put("seen_count", 1)
                    safeCustomer?.let { put("latest_customer_name", it) }
                    safeDetails?.let { put("latest_details", it) }
                    if (safeRaw.isNotBlank()) put("latest_raw_text", safeRaw)
                },
            )
            inserted = true
        } else {
            addressId = existing.id
            buildingKey = existing.buildingKey
            chosenDisplay = DeliveryAddressNormalizer.preferredDisplay(existing.displayAddress, normalized.second)
            db.update(
                "addresses",
                ContentValues().apply {
                    put("display_address", chosenDisplay)
                    put("platform", platform)
                    put("last_seen_at", maxOf(existing.lastSeenAt, now))
                    put("seen_count", if (now - existing.lastSeenAt >= RAW_OBSERVATION_DEDUPE_MS) existing.seenCount + 1 else existing.seenCount)
                    safeCustomer?.let { put("latest_customer_name", it) }
                    safeDetails?.let { put("latest_details", it) }
                    if (safeRaw.isNotBlank()) put("latest_raw_text", safeRaw)
                },
                "id = ?",
                arrayOf(addressId.toString()),
            )
            inserted = false
        }

        if (safeRaw.isNotBlank() && !hasRecentObservation(db, addressId, platform, now)) {
            db.insert(
                "address_observations",
                null,
                ContentValues().apply {
                    put("address_id", addressId)
                    put("seen_at", now)
                    put("platform", platform)
                    safeCustomer?.let { put("customer_name", it) }
                    safeDetails?.let { put("details_text", it) }
                    put("raw_text", safeRaw)
                },
            )
        }

        rememberAlias(context, normalized.first, buildingKey, chosenDisplay)
        return SmartAddressSaveResult(addressId, buildingKey, chosenDisplay, inserted, localAliasMatched)
    }

    fun mergeRecords(
        context: Context,
        database: CourierMetaDatabase,
        firstId: Long,
        secondId: Long,
        preferredDisplay: String? = null,
    ): AddressRecord? {
        if (firstId == secondId) return database.findAddressById(firstId)
        val first = database.findAddressById(firstId) ?: return database.findAddressById(secondId)
        val second = database.findAddressById(secondId) ?: return first
        val survivor = if (first.id <= second.id) first else second
        val duplicate = if (survivor.id == first.id) second else first
        val db = database.writableDatabase
        val latest = if (first.lastSeenAt >= second.lastSeenAt) first else second
        val mergedDisplay = preferredDisplay
            ?.let { DeliveryAddressNormalizer.preferredDisplay(survivor.displayAddress, it) }
            ?: DeliveryAddressNormalizer.preferredDisplay(survivor.displayAddress, duplicate.displayAddress)

        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE OR IGNORE address_entities SET address_id = ? WHERE address_id = ?",
                arrayOf(survivor.id, duplicate.id),
            )
            db.delete("address_entities", "address_id = ?", arrayOf(duplicate.id.toString()))
            db.execSQL(
                "UPDATE address_observations SET address_id = ? WHERE address_id = ?",
                arrayOf(survivor.id, duplicate.id),
            )

            db.execSQL(
                "UPDATE OR IGNORE access_codes SET building_key = ?, display_address = ? WHERE building_key = ?",
                arrayOf(survivor.buildingKey, mergedDisplay, duplicate.buildingKey),
            )
            db.delete("access_codes", "building_key = ?", arrayOf(duplicate.buildingKey))
            db.delete("addresses", "id = ?", arrayOf(duplicate.id.toString()))

            dedupeObservations(db, survivor.id)
            val observationCount = observationCount(db, survivor.id)
            db.update(
                "addresses",
                ContentValues().apply {
                    put("display_address", mergedDisplay)
                    put("platform", latest.platform)
                    put("first_seen_at", minOf(first.firstSeenAt, second.firstSeenAt))
                    put("last_seen_at", maxOf(first.lastSeenAt, second.lastSeenAt))
                    put("seen_count", if (observationCount > 0) observationCount else maxOf(first.seenCount, second.seenCount, 1))
                    latest.latestCustomerName?.let { put("latest_customer_name", it) }
                    latest.latestDetails?.let { put("latest_details", it) }
                    latest.latestRawText?.let { put("latest_raw_text", it) }
                },
                "id = ?",
                arrayOf(survivor.id.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        DeliveryAddressNormalizer.key(first.displayAddress)?.let {
            rememberAlias(context, it, survivor.buildingKey, mergedDisplay)
        }
        DeliveryAddressNormalizer.key(second.displayAddress)?.let {
            rememberAlias(context, it, survivor.buildingKey, mergedDisplay)
        }
        return database.findAddressById(survivor.id)
    }

    fun rememberAlias(context: Context, aliasKey: String, buildingKey: String, displayAddress: String) {
        val slot = aliasSlot(aliasKey)
        val value = listOf(aliasKey, buildingKey, displayAddress).joinToString(FIELD_SEPARATOR)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(slot, value).apply()
    }

    fun addressesWithHouseNumber(database: CourierMetaDatabase, houseNumber: String, excludeId: Long): List<AddressRecord> =
        loadAddresses(database)
            .filter { it.id != excludeId }
            .filter { DeliveryAddressNormalizer.identity(it.displayAddress)?.houseNumber.equals(houseNumber, ignoreCase = true) }

    private fun resolveRememberedAlias(
        context: Context,
        database: CourierMetaDatabase,
        aliasKey: String,
    ): AddressRecord? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(aliasSlot(aliasKey), null)
            ?: return null
        val fields = stored.split(FIELD_SEPARATOR, limit = 3)
        if (fields.size != 3 || fields[0] != aliasKey) return null
        val buildingKey = fields[1]
        loadAddresses(database).firstOrNull { it.buildingKey == buildingKey }?.let { return it }
        val display = fields[2]
        return loadAddresses(database).firstOrNull {
            DeliveryAddressNormalizer.matchScore(display, it.displayAddress) >= 0.99
        }
    }

    private fun loadAddresses(database: CourierMetaDatabase): List<AddressRecord> {
        val out = mutableListOf<AddressRecord>()
        var offset = 0
        while (out.size < MAX_LOCAL_ADDRESSES) {
            val page = database.searchAddresses("", limit = 200, offset = offset)
            if (page.isEmpty()) break
            out += page
            if (page.size < 200) break
            offset += page.size
        }
        return out
    }

    private fun uniqueBuildingKey(db: SQLiteDatabase, requested: String): String {
        var key = requested
        var suffix = 1
        while (db.query("addresses", arrayOf("id"), "building_key = ?", arrayOf(key), null, null, null, "1").use { it.moveToFirst() }) {
            suffix++
            key = "$requested #$suffix"
        }
        return key
    }

    private fun hasRecentObservation(db: SQLiteDatabase, addressId: Long, platform: String, now: Long): Boolean =
        db.query(
            "address_observations",
            arrayOf("id"),
            "address_id = ? AND platform = ? AND seen_at >= ?",
            arrayOf(addressId.toString(), platform, (now - RAW_OBSERVATION_DEDUPE_MS).toString()),
            null,
            null,
            "seen_at DESC",
            "1",
        ).use { it.moveToFirst() }

    private fun dedupeObservations(db: SQLiteDatabase, addressId: Long) {
        db.execSQL(
            """
            DELETE FROM address_observations
            WHERE address_id = ? AND id IN (
                SELECT newer.id
                FROM address_observations newer
                JOIN address_observations older
                  ON older.address_id = newer.address_id
                 AND older.platform = newer.platform
                 AND older.id < newer.id
                 AND ABS(older.seen_at - newer.seen_at) <= $RAW_OBSERVATION_DEDUPE_MS
                WHERE newer.address_id = ?
            )
            """.trimIndent(),
            arrayOf(addressId, addressId),
        )
    }

    private fun observationCount(db: SQLiteDatabase, addressId: Long): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM address_observations WHERE address_id = ?",
            arrayOf(addressId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun aliasSlot(aliasKey: String): String =
        ALIAS_PREFIX + aliasKey.hashCode().toUInt().toString(16).lowercase(Locale.ROOT)
}
