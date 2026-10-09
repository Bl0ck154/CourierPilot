package com.block154.courierpilot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Process-wide memo for [withCurrentParsedStructure]. Re-parsing a stored offer can cost tens of
 * milliseconds on a phone (raw text holds up to 12k characters of accumulated frames), so History
 * must never re-parse the same unchanged row on every tab switch or resume.
 *
 * A hit requires the stored source row to be identical, so edits by repairs, route backfills or
 * re-captures invalidate the entry automatically.
 */
internal object OfferEnrichmentCache {
    private const val MAX_ENTRIES = 1_500

    private val entries = object : LinkedHashMap<Long, Pair<OfferRecord, OfferRecord>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<OfferRecord, OfferRecord>>?): Boolean =
            size > MAX_ENTRIES
    }

    fun cached(record: OfferRecord): OfferRecord? = synchronized(entries) {
        entries[record.id]?.takeIf { it.first == record }?.second
    }

    fun enriched(record: OfferRecord): OfferRecord {
        cached(record)?.let { return it }
        val result = record.withCurrentParsedStructure()
        if (record.id > 0L) synchronized(entries) { entries[record.id] = record to result }
        return result
    }

    /** Enriches uncached rows in parallel on all cores; order is preserved. */
    suspend fun enrichAll(records: List<OfferRecord>): List<OfferRecord> = withContext(Dispatchers.Default) {
        records.map { record -> async { enriched(record) } }.awaitAll()
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
