package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer
import java.util.Locale

internal data class AutomaticWorkSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val startReason: String,
    val endReason: String?,
)

internal data class AutomaticWorkSummary(
    val totalMillis: Long,
    val sessionCount: Int,
    val active: Boolean,
)

internal data class AccessCodeRecord(
    val id: Long,
    val buildingKey: String,
    val displayAddress: String,
    val code: String,
    val platform: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
)

internal data class AddressRecord(
    val id: Long,
    val buildingKey: String,
    val displayAddress: String,
    val platform: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
    val latestCustomerName: String?,
    val latestDetails: String?,
    val latestRawText: String?,
)

internal data class AddressObservationRecord(
    val id: Long,
    val addressId: Long,
    val seenAt: Long,
    val platform: String,
    val customerName: String?,
    val detailsText: String?,
    val rawText: String,
)

internal data class AddressEntityRecord(
    val id: Long,
    val addressId: Long,
    val entityType: String,
    val name: String,
    val platform: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
)

/**
 * Local metadata learned while CourierPilot is running.
 *
 * Address memory intentionally keeps richer local context than the original access-code-only
 * implementation: every detected building is stored, repeated visits are counted, the latest
 * customer/details text is retained, and raw screen observations are preserved for future parsers.
 */
internal class CourierMetaDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createWorkSessionsTable(db)
        createAccessCodesTable(db)
        createAddressTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAddressTables(db)
            db.execSQL(
                """
                INSERT OR IGNORE INTO addresses(
                    building_key, display_address, platform, first_seen_at, last_seen_at, seen_count
                )
                SELECT building_key,
                       MAX(display_address),
                       MAX(platform),
                       MIN(first_seen_at),
                       MAX(last_seen_at),
                       MAX(seen_count)
                FROM access_codes
                GROUP BY building_key
                """.trimIndent()
            )
        }
        if (oldVersion < 3) {
            createAddressEntityTable(db)
        }
    }

    private fun createWorkSessionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                start_reason TEXT NOT NULL,
                end_reason TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_sessions_started_at ON work_sessions(started_at)")
    }

    private fun createAccessCodesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS access_codes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                building_key TEXT NOT NULL,
                display_address TEXT NOT NULL,
                code TEXT NOT NULL,
                platform TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                UNIQUE(building_key, code)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_codes_last_seen ON access_codes(last_seen_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_codes_building ON access_codes(building_key)")
    }

    private fun createAddressTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS addresses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                building_key TEXT NOT NULL UNIQUE,
                display_address TEXT NOT NULL,
                platform TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                latest_customer_name TEXT,
                latest_details TEXT,
                latest_raw_text TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_addresses_last_seen ON addresses(last_seen_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_addresses_display ON addresses(display_address)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS address_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address_id INTEGER NOT NULL,
                seen_at INTEGER NOT NULL,
                platform TEXT NOT NULL,
                customer_name TEXT,
                details_text TEXT,
                raw_text TEXT NOT NULL,
                FOREIGN KEY(address_id) REFERENCES addresses(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_address_observations_address ON address_observations(address_id, seen_at DESC)")
        createAddressEntityTable(db)
    }

    private fun createAddressEntityTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS address_entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address_id INTEGER NOT NULL,
                entity_type TEXT NOT NULL,
                normalized_name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                platform TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(address_id) REFERENCES addresses(id) ON DELETE CASCADE,
                UNIQUE(address_id, entity_type, normalized_name, platform)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_address_entities_address ON address_entities(address_id, entity_type, last_seen_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_address_entities_name ON address_entities(normalized_name)")
    }

    fun activeWorkSession(): AutomaticWorkSession? {
        readableDatabase.query(
            "work_sessions",
            null,
            "ended_at IS NULL",
            null,
            null,
            null,
            "started_at DESC",
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toWorkSession() else null
        }
    }

    fun startAutomaticSession(reason: String, now: Long = System.currentTimeMillis()): Long {
        activeWorkSession()?.let { return it.id }
        return writableDatabase.insertOrThrow(
            "work_sessions",
            null,
            ContentValues().apply {
                put("started_at", now)
                put("start_reason", reason.take(160))
            },
        )
    }

    fun endAutomaticSession(reason: String, now: Long = System.currentTimeMillis()): Boolean {
        val active = activeWorkSession() ?: return false
        val safeEnd = now.coerceAtLeast(active.startedAt)
        return writableDatabase.update(
            "work_sessions",
            ContentValues().apply {
                put("ended_at", safeEnd)
                put("end_reason", reason.take(160))
            },
            "id = ? AND ended_at IS NULL",
            arrayOf(active.id.toString()),
        ) > 0
    }

    fun workSummarySince(since: Long, now: Long = System.currentTimeMillis()): AutomaticWorkSummary {
        var total = 0L
        var count = 0
        var active = false
        readableDatabase.query(
            "work_sessions",
            arrayOf("started_at", "ended_at"),
            "started_at >= ? OR ended_at >= ? OR ended_at IS NULL",
            arrayOf(since.toString(), since.toString()),
            null,
            null,
            "started_at DESC",
            "1000",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val start = cursor.getLong(0).coerceAtLeast(since)
                val end = if (cursor.isNull(1)) {
                    active = true
                    now
                } else {
                    cursor.getLong(1)
                }.coerceAtLeast(start)
                total += end - start
                count++
            }
        }
        return AutomaticWorkSummary(total, count, active)
    }

    fun saveAddressObservation(
        address: String,
        platform: String,
        customerName: String?,
        detailsText: String?,
        rawText: String,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val normalized = CourierSignals.normalizeBuildingAddress(address) ?: return null
        val buildingKey = normalized.first
        val displayAddress = normalized.second
        val db = writableDatabase
        val safeCustomer = customerName?.trim()?.takeIf(String::isNotEmpty)?.take(240)
        val safeDetails = detailsText?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_DETAILS_CHARS)
        val safeRaw = rawText.trim().take(MAX_RAW_CHARS)

        var addressId: Long? = null
        var previousSeenAt = 0L
        var previousCount = 0
        db.query(
            "addresses",
            arrayOf("id", "last_seen_at", "seen_count"),
            "building_key = ?",
            arrayOf(buildingKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                addressId = cursor.getLong(0)
                previousSeenAt = cursor.getLong(1)
                previousCount = cursor.getInt(2)
            }
        }

        if (addressId == null) {
            addressId = db.insertOrThrow(
                "addresses",
                null,
                ContentValues().apply {
                    put("building_key", buildingKey)
                    put("display_address", displayAddress)
                    put("platform", platform)
                    put("first_seen_at", now)
                    put("last_seen_at", now)
                    put("seen_count", 1)
                    safeCustomer?.let { put("latest_customer_name", it) }
                    safeDetails?.let { put("latest_details", it) }
                    if (safeRaw.isNotBlank()) put("latest_raw_text", safeRaw)
                },
            )
        } else {
            val values = ContentValues().apply {
                put("display_address", displayAddress)
                put("platform", platform)
                put("last_seen_at", now)
                put("seen_count", if (now - previousSeenAt >= OBSERVATION_DEDUPE_MS) previousCount + 1 else previousCount)
                safeCustomer?.let { put("latest_customer_name", it) }
                safeDetails?.let { put("latest_details", it) }
                if (safeRaw.isNotBlank()) put("latest_raw_text", safeRaw)
            }
            db.update("addresses", values, "id = ?", arrayOf(addressId.toString()))
        }

        val id = addressId ?: return null
        val duplicateObservation = db.query(
            "address_observations",
            arrayOf("id"),
            "address_id = ? AND seen_at >= ? AND raw_text = ?",
            arrayOf(id.toString(), (now - RAW_OBSERVATION_DEDUPE_MS).toString(), safeRaw),
            null,
            null,
            "seen_at DESC",
            "1",
        ).use { it.moveToFirst() }

        if (!duplicateObservation && safeRaw.isNotBlank()) {
            db.insert(
                "address_observations",
                null,
                ContentValues().apply {
                    put("address_id", id)
                    put("seen_at", now)
                    put("platform", platform)
                    safeCustomer?.let { put("customer_name", it) }
                    safeDetails?.let { put("details_text", it) }
                    put("raw_text", safeRaw)
                },
            )
        }
        return id
    }

    fun findAddressById(id: Long): AddressRecord? {
        readableDatabase.query(
            "addresses",
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toAddressRecord() else null
        }
    }

    fun findAddressForDisplayAddress(address: String): AddressRecord? {
        val normalized = CourierSignals.normalizeBuildingAddress(address) ?: return null
        readableDatabase.query(
            "addresses",
            null,
            "building_key = ?",
            arrayOf(normalized.first),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toAddressRecord() else null
        }
    }

    fun searchAddresses(query: String, limit: Int = 50, offset: Int = 0): List<AddressRecord> {
        val clean = query.trim().lowercase()
        val selection = if (clean.isBlank()) null else """
            LOWER(display_address) LIKE ? OR
            LOWER(platform) LIKE ? OR
            LOWER(COALESCE(latest_customer_name, '')) LIKE ? OR
            LOWER(COALESCE(latest_details, '')) LIKE ? OR
            EXISTS (
                SELECT 1 FROM access_codes ac
                WHERE ac.building_key = addresses.building_key AND LOWER(ac.code) LIKE ?
            ) OR
            EXISTS (
                SELECT 1 FROM address_entities ae
                WHERE ae.address_id = addresses.id AND LOWER(ae.display_name) LIKE ?
            )
        """.trimIndent()
        val args = if (clean.isBlank()) null else Array(6) { "%$clean%" }
        val out = mutableListOf<AddressRecord>()
        readableDatabase.query(
            "addresses",
            null,
            selection,
            args,
            null,
            null,
            "last_seen_at DESC",
            "${limit.coerceIn(1, 200)} OFFSET ${offset.coerceAtLeast(0)}",
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toAddressRecord()
        }
        return out
    }

    fun addressCount(query: String = ""): Int {
        val clean = query.trim().lowercase()
        val where = if (clean.isBlank()) "" else """
            WHERE LOWER(display_address) LIKE ? OR
                  LOWER(platform) LIKE ? OR
                  LOWER(COALESCE(latest_customer_name, '')) LIKE ? OR
                  LOWER(COALESCE(latest_details, '')) LIKE ? OR
                  EXISTS (
                      SELECT 1 FROM access_codes ac
                      WHERE ac.building_key = addresses.building_key AND LOWER(ac.code) LIKE ?
                  ) OR
                  EXISTS (
                      SELECT 1 FROM address_entities ae
                      WHERE ae.address_id = addresses.id AND LOWER(ae.display_name) LIKE ?
                  )
        """.trimIndent()
        val args = if (clean.isBlank()) null else Array(6) { "%$clean%" }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM addresses $where", args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun observationsForAddress(addressId: Long, limit: Int = 50): List<AddressObservationRecord> {
        val out = mutableListOf<AddressObservationRecord>()
        readableDatabase.query(
            "address_observations",
            null,
            "address_id = ?",
            arrayOf(addressId.toString()),
            null,
            null,
            "seen_at DESC",
            limit.coerceIn(1, 200).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toAddressObservationRecord()
        }
        return out
    }

    fun saveAddressEntity(
        addressId: Long,
        entityType: String,
        name: String,
        platform: String,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val type = entityType.trim().lowercase(Locale.ROOT)
        if (type !in setOf(ENTITY_VENUE, ENTITY_CUSTOMER)) return null
        val displayName = name.trim().replace(Regex("\\s+"), " ").take(240)
        if (displayName.isBlank()) return null
        val normalizedName = normalizeEntityName(displayName)
        if (normalizedName.isBlank()) return null
        val db = writableDatabase

        db.query(
            "address_entities",
            arrayOf("id", "first_seen_at", "last_seen_at", "seen_count"),
            "address_id = ? AND entity_type = ? AND normalized_name = ? AND platform = ?",
            arrayOf(addressId.toString(), type, normalizedName, platform),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val firstSeen = cursor.getLong(1)
                val lastSeen = cursor.getLong(2)
                val count = cursor.getInt(3)
                db.update(
                    "address_entities",
                    ContentValues().apply {
                        put("display_name", displayName)
                        put("first_seen_at", minOf(firstSeen, now))
                        put("last_seen_at", maxOf(lastSeen, now))
                        put("seen_count", if (now - lastSeen >= OBSERVATION_DEDUPE_MS) count + 1 else count)
                    },
                    "id = ?",
                    arrayOf(id.toString()),
                )
                return id
            }
        }

        return db.insertOrThrow(
            "address_entities",
            null,
            ContentValues().apply {
                put("address_id", addressId)
                put("entity_type", type)
                put("normalized_name", normalizedName)
                put("display_name", displayName)
                put("platform", platform)
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
            },
        )
    }

    fun entitiesForAddress(addressId: Long, entityType: String, limit: Int = 100): List<AddressEntityRecord> {
        val type = entityType.trim().lowercase(Locale.ROOT)
        val out = mutableListOf<AddressEntityRecord>()
        readableDatabase.query(
            "address_entities",
            null,
            "address_id = ? AND entity_type = ?",
            arrayOf(addressId.toString(), type),
            null,
            null,
            "last_seen_at DESC, display_name COLLATE NOCASE",
            limit.coerceIn(1, 300).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toAddressEntityRecord()
        }
        return out
    }

    /** Merge rows created by older/less strict address normalization rules. */
    fun repairNormalizedAddresses() {
        data class AddressRepairRow(
            val id: Long,
            val display: String,
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
        data class CodeRepairRow(
            val id: Long,
            val display: String,
            val code: String,
            val platform: String,
            val firstSeenAt: Long,
            val lastSeenAt: Long,
            val seenCount: Int,
            val canonicalKey: String,
            val canonicalDisplay: String,
        )

        val db = writableDatabase
        val addressRows = mutableListOf<AddressRepairRow>()
        db.query("addresses", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val normalized = CourierSignals.normalizeBuildingAddress(display) ?: continue
                addressRows += AddressRepairRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    display = display,
                    platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                    firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                    lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                    seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
                    latestCustomer = cursor.nullableString("latest_customer_name"),
                    latestDetails = cursor.nullableString("latest_details"),
                    latestRaw = cursor.nullableString("latest_raw_text"),
                    canonicalKey = normalized.first,
                    canonicalDisplay = normalized.second,
                )
            }
        }

        val codeRows = mutableListOf<CodeRepairRow>()
        db.query("access_codes", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val normalized = CourierSignals.normalizeBuildingAddress(display) ?: continue
                codeRows += CodeRepairRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    display = display,
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
            // Avoid transient UNIQUE collisions while two legacy keys swap/collapse into the same
            // canonical key. All normalized rows receive transaction-local keys before merging.
            addressRows.forEach { row ->
                db.update(
                    "addresses",
                    ContentValues().apply { put("building_key", "__repair_address_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }
            codeRows.forEach { row ->
                db.update(
                    "access_codes",
                    ContentValues().apply { put("building_key", "__repair_code_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }

            addressRows.groupBy { it.canonicalKey }.values.forEach { group ->
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

            codeRows.groupBy { "${it.canonicalKey}|${it.code}" }.values.forEach { group ->
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

            // After address IDs are merged, collapse repeated raw callbacks from the same short burst.
            db.execSQL(
                """
                DELETE FROM address_observations
                WHERE id NOT IN (
                    SELECT MIN(id)
                    FROM address_observations
                    GROUP BY address_id, platform, raw_text, (seen_at / 30000)
                )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAccessCode(
        observation: AccessCodeObservation,
        platform: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val db = writableDatabase
        db.query(
            "access_codes",
            arrayOf("id", "last_seen_at", "seen_count"),
            "building_key = ? AND code = ?",
            arrayOf(observation.buildingKey, observation.code),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val previousSeenAt = cursor.getLong(1)
                val previousCount = cursor.getInt(2)
                val values = ContentValues().apply {
                    put("display_address", observation.displayAddress)
                    put("platform", platform)
                    put("last_seen_at", now)
                    put("seen_count", if (now - previousSeenAt >= OBSERVATION_DEDUPE_MS) previousCount + 1 else previousCount)
                }
                db.update("access_codes", values, "id = ?", arrayOf(id.toString()))
                return id
            }
        }

        return db.insertOrThrow(
            "access_codes",
            null,
            ContentValues().apply {
                put("building_key", observation.buildingKey)
                put("display_address", observation.displayAddress)
                put("code", observation.code)
                put("platform", platform)
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
            },
        )
    }

    fun recentAccessCodes(limit: Int = 100): List<AccessCodeRecord> = queryAccessCodes(null, null, limit)

    fun searchAccessCodes(query: String, limit: Int = 100): List<AccessCodeRecord> {
        val clean = query.trim()
        if (clean.isEmpty()) return recentAccessCodes(limit)
        val key = clean.lowercase().replace(Regex("[^a-z0-9ąčęėįšųūž]+"), "%")
        return queryAccessCodes(
            "LOWER(display_address) LIKE ? OR LOWER(building_key) LIKE ? OR code LIKE ?",
            arrayOf("%${clean.lowercase()}%", "%$key%", "%$clean%"),
            limit,
        )
    }

    fun accessCodeCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM access_codes", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun queryAccessCodes(selection: String?, args: Array<String>?, limit: Int): List<AccessCodeRecord> {
        val out = mutableListOf<AccessCodeRecord>()
        readableDatabase.query(
            "access_codes",
            null,
            selection,
            args,
            null,
            null,
            "last_seen_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toAccessCode()
        }
        return out
    }

    private fun Cursor.toWorkSession(): AutomaticWorkSession = AutomaticWorkSession(
        id = getLong(getColumnIndexOrThrow("id")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        endedAt = getColumnIndexOrThrow("ended_at").let { if (isNull(it)) null else getLong(it) },
        startReason = getString(getColumnIndexOrThrow("start_reason")),
        endReason = getColumnIndexOrThrow("end_reason").let { if (isNull(it)) null else getString(it) },
    )

    private fun Cursor.toAccessCode(): AccessCodeRecord = AccessCodeRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        buildingKey = getString(getColumnIndexOrThrow("building_key")),
        displayAddress = getString(getColumnIndexOrThrow("display_address")),
        code = getString(getColumnIndexOrThrow("code")),
        platform = getString(getColumnIndexOrThrow("platform")),
        firstSeenAt = getLong(getColumnIndexOrThrow("first_seen_at")),
        lastSeenAt = getLong(getColumnIndexOrThrow("last_seen_at")),
        seenCount = getInt(getColumnIndexOrThrow("seen_count")),
    )

    private fun Cursor.toAddressRecord(): AddressRecord = AddressRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        buildingKey = getString(getColumnIndexOrThrow("building_key")),
        displayAddress = getString(getColumnIndexOrThrow("display_address")),
        platform = getString(getColumnIndexOrThrow("platform")),
        firstSeenAt = getLong(getColumnIndexOrThrow("first_seen_at")),
        lastSeenAt = getLong(getColumnIndexOrThrow("last_seen_at")),
        seenCount = getInt(getColumnIndexOrThrow("seen_count")),
        latestCustomerName = nullableString("latest_customer_name"),
        latestDetails = nullableString("latest_details"),
        latestRawText = nullableString("latest_raw_text"),
    )

    private fun Cursor.toAddressObservationRecord(): AddressObservationRecord = AddressObservationRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        addressId = getLong(getColumnIndexOrThrow("address_id")),
        seenAt = getLong(getColumnIndexOrThrow("seen_at")),
        platform = getString(getColumnIndexOrThrow("platform")),
        customerName = nullableString("customer_name"),
        detailsText = nullableString("details_text"),
        rawText = getString(getColumnIndexOrThrow("raw_text")),
    )

    private fun Cursor.toAddressEntityRecord(): AddressEntityRecord = AddressEntityRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        addressId = getLong(getColumnIndexOrThrow("address_id")),
        entityType = getString(getColumnIndexOrThrow("entity_type")),
        name = getString(getColumnIndexOrThrow("display_name")),
        platform = getString(getColumnIndexOrThrow("platform")),
        firstSeenAt = getLong(getColumnIndexOrThrow("first_seen_at")),
        lastSeenAt = getLong(getColumnIndexOrThrow("last_seen_at")),
        seenCount = getInt(getColumnIndexOrThrow("seen_count")),
    )

    private fun normalizeEntityName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun Cursor.nullableString(name: String): String? =
        getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getString(index) }

    companion object {
        private const val DB_NAME = "courier_meta.db"
        private const val DB_VERSION = 3
        private const val OBSERVATION_DEDUPE_MS = 5L * 60L * 1000L
        private const val RAW_OBSERVATION_DEDUPE_MS = 30L * 1000L
        const val ENTITY_VENUE = "venue"
        const val ENTITY_CUSTOMER = "customer"
        private const val MAX_DETAILS_CHARS = 8_000
        private const val MAX_RAW_CHARS = 16_000

        @Volatile private var instance: CourierMetaDatabase? = null

        fun get(context: Context): CourierMetaDatabase = instance ?: synchronized(this) {
            instance ?: CourierMetaDatabase(context).also { instance = it }
        }
    }
}
