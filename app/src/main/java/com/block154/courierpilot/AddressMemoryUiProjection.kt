package com.block154.courierpilot

import java.text.Normalizer
import java.util.Locale

/**
 * Read-only UI projection over durable address memory.
 *
 * Persistence deliberately keeps source/platform rows separate. The UI may safely collapse only
 * exact canonical equivalents so a long-lived building does not become a wall of duplicate cards.
 */
internal data class AddressCustomerSummary(
    val key: String,
    val displayName: String,
    val platforms: List<String>,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
)

internal data class AddressCodeSummary(
    val key: String,
    val code: String,
    val platforms: List<String>,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
)

internal object AddressMemoryUiProjection {
    fun summarizeCustomers(rows: List<AddressEntityRecord>): List<AddressCustomerSummary> = rows
        .asSequence()
        .filter { it.entityType == CourierMetaDatabase.ENTITY_CUSTOMER }
        .filterNot { AddressMetadataCleanup.isUiGarbage(it.name) }
        .groupBy { canonicalName(it.name) }
        .filterKeys(String::isNotBlank)
        .map { (key, group) ->
            val latest = group.maxBy { it.lastSeenAt }
            AddressCustomerSummary(
                key = key,
                displayName = latest.name.trim().replace(Regex("\\s+"), " "),
                platforms = group.map { it.platform.trim() }.filter(String::isNotBlank).distinct().sorted(),
                firstSeenAt = group.minOf { it.firstSeenAt },
                lastSeenAt = group.maxOf { it.lastSeenAt },
                seenCount = group.sumOf { it.seenCount.coerceAtLeast(0) },
            )
        }
        .sortedWith(compareByDescending<AddressCustomerSummary> { it.lastSeenAt }.thenBy { it.displayName.lowercase(Locale.ROOT) })

    fun summarizeCodes(
        rows: List<AccessCodeRecord>,
        observations: List<AddressObservationRecord>,
    ): List<AddressCodeSummary> {
        val rawHistory = observations.map { it.rawText }
        return rows
            .asSequence()
            .filter { row ->
                rawHistory.none { historicalText ->
                    !AccessCodeHintPolicy.shouldLearnCandidate(historicalText, row.code)
                }
            }
            .groupBy { canonicalCode(it.code) }
            .filterKeys(String::isNotBlank)
            .map { (key, group) ->
                val latest = group.maxBy { it.lastSeenAt }
                AddressCodeSummary(
                    key = key,
                    code = latest.code.trim(),
                    platforms = group.map { it.platform.trim() }.filter(String::isNotBlank).distinct().sorted(),
                    firstSeenAt = group.minOf { it.firstSeenAt },
                    lastSeenAt = group.maxOf { it.lastSeenAt },
                    seenCount = group.sumOf { it.seenCount.coerceAtLeast(0) },
                )
            }
            .sortedWith(compareByDescending<AddressCodeSummary> { it.lastSeenAt }.thenByDescending { it.seenCount })
    }

    internal fun canonicalName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun canonicalCode(value: String): String = value
        .trim()
        .uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
}
