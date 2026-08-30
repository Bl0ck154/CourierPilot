package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context

/**
 * Re-runs canonical building merges when address identity rules evolve.
 *
 * Revision 5 repairs the architectural bug from pre-0.13 builds: Accessibility and OCR text were
 * concatenated before address detection, so a clipped OCR line could become a durable building.
 * The migration searches each row's preserved raw observations for a better same-house variant,
 * uses a migration-only OCR matcher to merge malformed variants into trusted ones, and removes
 * disposable one-frame fragments that have no useful address-linked metadata.
 */
internal object AddressDataRepair {
    private const val PREFS = "courierpilot_address_repairs"
    private const val KEY_REVISION = "canonical_revision"
    private const val CURRENT_REVISION = 5
    private const val OBSERVATION_BURST_MS = 2L * 60L * 1000L
    private const val MAX_RAW_SAMPLES_PER_ADDRESS = 20

    @Synchronized
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
        val buildingKey: String,
        val originalDisplay: String,
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
        val buildingKey: String,
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

        val rawSamplesByAddress = mutableMapOf<Long, MutableList<String>>()
        db.query(
            "address_observations",
            arrayOf("address_id", "raw_text"),
            null,
            null,
            null,
            null,
            "address_id ASC, seen_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val raw = cursor.getString(1)?.takeIf(String::isNotBlank) ?: continue
                val samples = rawSamplesByAddress.getOrPut(id) { mutableListOf() }
                if (samples.size < MAX_RAW_SAMPLES_PER_ADDRESS) samples += raw
            }
        }

        val entityAddressIds = mutableSetOf<Long>()
        db.query("address_entities", arrayOf("address_id"), null, null, "address_id", null, null).use { cursor ->
            while (cursor.moveToNext()) entityAddressIds += cursor.getLong(0)
        }
        val codeBuildingKeys = mutableSetOf<String>()
        db.query("access_codes", arrayOf("building_key"), null, null, "building_key", null, null).use { cursor ->
            while (cursor.moveToNext()) codeBuildingKeys += cursor.getString(0)
        }

        val artifacts = mutableListOf<ArtifactRow>()
        val addresses = mutableListOf<AddressRow>()
        db.query("addresses", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val originalDisplay = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val buildingKey = cursor.getString(cursor.getColumnIndexOrThrow("building_key"))
                fun nullable(name: String): String? {
                    val index = cursor.getColumnIndexOrThrow(name)
                    return if (cursor.isNull(index)) null else cursor.getString(index)
                }

                if (DeliveryAddressNormalizer.isRejectedAddressArtifact(originalDisplay)) {
                    artifacts += ArtifactRow(id, buildingKey)
                    continue
                }

                val latestCustomer = nullable("latest_customer_name")
                val latestDetails = nullable("latest_details")
                val latestRaw = nullable("latest_raw_text")
                val rawSamples = buildList {
                    latestRaw?.takeIf(String::isNotBlank)?.let(::add)
                    rawSamplesByAddress[id].orEmpty().forEach { if (it !in this) add(it) }
                }
                val recoveredDisplay = recoverLegacyDisplay(originalDisplay, rawSamples)
                val identity = DeliveryAddressNormalizer.identity(recoveredDisplay) ?: continue
                val seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count"))

                val disposableFragment = recoveredDisplay == originalDisplay &&
                    isDisposableLegacyFragment(
                        display = originalDisplay,
                        seenCount = seenCount,
                        latestCustomer = latestCustomer,
                        latestDetails = latestDetails,
                        hasEntities = id in entityAddressIds,
                        hasCodes = buildingKey in codeBuildingKeys,
                    )
                if (disposableFragment) {
                    artifacts += ArtifactRow(id, buildingKey)
                    continue
                }

                addresses += AddressRow(
                    id = id,
                    buildingKey = buildingKey,
                    originalDisplay = originalDisplay,
                    display = recoveredDisplay,
                    platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                    firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                    lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                    seenCount = seenCount,
                    latestCustomer = latestCustomer,
                    latestDetails = latestDetails,
                    latestRaw = latestRaw,
                    identity = identity,
                )
            }
        }

        val recoveredDisplayByBuildingKey = addresses.associate { it.buildingKey to it.display }
        val artifactKeys = artifacts.mapTo(mutableSetOf()) { it.buildingKey }

        val codes = mutableListOf<CodeRow>()
        db.query("access_codes", null, null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val buildingKey = cursor.getString(cursor.getColumnIndexOrThrow("building_key"))
                if (buildingKey in artifactKeys) continue
                val originalDisplay = cursor.getString(cursor.getColumnIndexOrThrow("display_address"))
                val display = recoveredDisplayByBuildingKey[buildingKey] ?: originalDisplay
                if (DeliveryAddressNormalizer.isRejectedAddressArtifact(display)) continue
                val identity = DeliveryAddressNormalizer.identity(display) ?: continue
                codes += CodeRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    buildingKey = buildingKey,
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
            repairSameBuilding(left.display, right.display)
        }
        val codeGroups = fuzzyGroups(codes) { left, right ->
            left.code == right.code && repairSameBuilding(left.display, right.display)
        }

        db.beginTransaction()
        try {
            // Remove accidental rows and everything that only belonged to those fake buildings.
            // Do this explicitly instead of depending on OEM SQLite foreign-key settings.
            artifacts.forEach { artifact ->
                db.delete("address_entities", "address_id = ?", arrayOf(artifact.id.toString()))
                db.delete("address_observations", "address_id = ?", arrayOf(artifact.id.toString()))
                db.delete("access_codes", "building_key = ?", arrayOf(artifact.buildingKey))
                db.delete("addresses", "id = ?", arrayOf(artifact.id.toString()))
            }

            // Temporary keys avoid UNIQUE collisions while repaired variants collapse together.
            addresses.forEach { row ->
                db.update(
                    "addresses",
                    ContentValues().apply { put("building_key", "__address_identity_v5_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }
            codes.forEach { row ->
                db.update(
                    "access_codes",
                    ContentValues().apply { put("building_key", "__code_identity_v5_${row.id}") },
                    "id = ?",
                    arrayOf(row.id.toString()),
                )
            }

            addressGroups.forEach { group ->
                val survivor = group.minByOrNull { it.id } ?: return@forEach
                val latest = group.maxByOrNull { it.lastSeenAt } ?: survivor
                val preferredDisplay = group.map { it.display }
                    .maxByOrNull(DeliveryAddressNormalizer::legacyDisplayQuality)
                    ?: survivor.display
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
                val preferredDisplay = group.map { it.display }
                    .maxByOrNull(DeliveryAddressNormalizer::legacyDisplayQuality)
                    ?: survivor.display
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

        // v5 changed identity aggressively. Forget aliases/last-address hints created by polluted
        // builds so a stale preference cannot point a corrected screen back at a removed row.
        context.getSharedPreferences("courierpilot_address_aliases_v2", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("courierpilot_delivery_memory", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun recoverLegacyDisplay(current: String, rawSamples: List<String>): String {
        val currentIdentity = DeliveryAddressNormalizer.identity(current) ?: return current
        val currentQuality = DeliveryAddressNormalizer.legacyDisplayQuality(current)
        val currentPlausible = DeliveryAddressNormalizer.hasStrongAddressEvidence(current) ||
            DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(current)

        data class Candidate(val value: String, val order: Int, val score: Double, val quality: Int)
        val candidates = mutableListOf<Candidate>()
        var order = 0
        var currentOrder = Int.MAX_VALUE

        rawSamples.forEach { raw ->
            DeliveryAddressNormalizer.likelyAddressLines(raw).forEach { line ->
                val lineIdentity = DeliveryAddressNormalizer.identity(line) ?: return@forEach
                if (!lineIdentity.houseNumber.equals(currentIdentity.houseNumber, ignoreCase = true)) {
                    order++
                    return@forEach
                }
                if (line.trim().equals(current.trim(), ignoreCase = false)) currentOrder = minOf(currentOrder, order)
                val repairScore = DeliveryAddressNormalizer.legacyOcrRepairScore(current, line)
                if (repairScore >= 0.80) {
                    candidates += Candidate(
                        value = line,
                        order = order,
                        score = repairScore,
                        quality = DeliveryAddressNormalizer.legacyDisplayQuality(line),
                    )
                }
                order++
            }
        }

        val eligible = candidates.filter { candidate ->
            val candidateDisplay = DeliveryAddressNormalizer.display(candidate.value) ?: return@filter false
            val currentDisplay = DeliveryAddressNormalizer.display(current) ?: current
            if (candidateDisplay == currentDisplay) return@filter false

            val candidatePlausible = DeliveryAddressNormalizer.hasStrongAddressEvidence(candidate.value) ||
                DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(candidate.value)
            val earlierInSameMergedFrame = currentOrder != Int.MAX_VALUE && candidate.order < currentOrder
            candidate.quality > currentQuality ||
                (!currentPlausible && candidatePlausible) ||
                (earlierInSameMergedFrame && candidate.quality >= currentQuality && candidate.score >= 0.88)
        }

        return eligible.maxWithOrNull(
            compareBy<Candidate> { it.quality }
                .thenBy { it.score }
                .thenBy { -it.order },
        )?.value ?: current
    }

    private fun repairSameBuilding(first: String, second: String): Boolean {
        if (DeliveryAddressNormalizer.isLikelySameBuilding(first, second)) return true

        val firstIdentity = DeliveryAddressNormalizer.identity(first) ?: return false
        val secondIdentity = DeliveryAddressNormalizer.identity(second) ?: return false
        if (!firstIdentity.houseNumber.equals(secondIdentity.houseNumber, ignoreCase = true)) return false

        val firstPlausible = DeliveryAddressNormalizer.hasStrongAddressEvidence(first) ||
            DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(first)
        val secondPlausible = DeliveryAddressNormalizer.hasStrongAddressEvidence(second) ||
            DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(second)
        if (firstPlausible == secondPlausible) return false

        return DeliveryAddressNormalizer.legacyOcrRepairScore(first, second) >= 0.86
    }

    private fun isDisposableLegacyFragment(
        display: String,
        seenCount: Int,
        latestCustomer: String?,
        latestDetails: String?,
        hasEntities: Boolean,
        hasCodes: Boolean,
    ): Boolean {
        if (seenCount > 1 || hasEntities || hasCodes) return false
        if (!latestCustomer.isNullOrBlank() || !latestDetails.isNullOrBlank()) return false
        if (DeliveryAddressNormalizer.hasStrongAddressEvidence(display)) return false
        return !DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(display)
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
