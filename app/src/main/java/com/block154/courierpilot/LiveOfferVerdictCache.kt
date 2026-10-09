package com.block154.courierpilot

/**
 * A verified route verdict belongs to an offer, not to an overlay window generation.
 * Keep only process-local presentation data: no customer coordinates or addresses reach telemetry.
 */
internal class LiveOfferVerdictCache(
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val capacity: Int = 32,
    private val ttlMs: Long = 30L * 60L * 1000L,
) {
    internal data class Verdict(
        val rateLine: String,
        val band: OfferDecisionBand,
        val routeLine: String,
        val walkingMeters: Int?,
        val cyclingMeters: Int?,
        val thresholdSource: String,
        val createdAtMs: Long,
    )

    private val byIdentity = object : LinkedHashMap<String, Verdict>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verdict>): Boolean =
            size > capacity
    }
    private val identityOwners = mutableMapOf<String, Long>()

    @Synchronized
    fun find(platform: String, parsed: ParsedOffer, offerId: Long? = null): Verdict? {
        purgeExpired()
        val identity = identityKey(platform, parsed)
        val key = if (offerId != null) {
            val direct = "id:$offerId"
            if (direct in byIdentity) direct
            else if (identityOwners[identity] == null) "preview:$identity"
            else direct
        } else {
            identityOwners[identity]?.let { "id:$it" } ?: "preview:$identity"
        }
        return byIdentity[key]
    }

    /** First verified verdict wins, even if a retry has fresher GPS or adaptive market samples. */
    @Synchronized
    fun remember(platform: String, parsed: ParsedOffer, offerId: Long? = null, verdict: Verdict): Verdict {
        purgeExpired()
        val identity = identityKey(platform, parsed)
        val key = offerId?.let { "id:$it" } ?: "preview:$identity"
        byIdentity[key]?.let { return it }
        val chosen = if (offerId != null && identityOwners[identity] == null) {
            byIdentity.remove("preview:$identity") ?: verdict
        } else {
            verdict
        }
        byIdentity[key] = chosen
        if (offerId != null) identityOwners[identity] = offerId
        pruneAliases()
        return chosen
    }

    @Synchronized
    fun size(): Int {
        purgeExpired()
        return byIdentity.size
    }

    private fun purgeExpired() {
        val now = nowMs()
        byIdentity.entries.removeAll { (_, value) ->
            now - value.createdAtMs !in 0 until ttlMs
        }
        pruneAliases()
    }

    private fun pruneAliases() {
        identityOwners.entries.removeAll { "id:${it.value}" !in byIdentity }
    }

    companion object {
        val shared = LiveOfferVerdictCache()

        fun identityKey(platform: String, parsed: ParsedOffer): String {
            val packageName = when {
                platform.equals("Bolt", ignoreCase = true) -> CourierSignals.BOLT_PACKAGE
                platform.equals("Wolt", ignoreCase = true) -> CourierSignals.WOLT_PACKAGE
                else -> platform.lowercase()
            }
            fun addressKey(raw: String): String =
                DeliveryAddressNormalizer.identity(raw)?.key
                    ?: LiveOfferUserDismissalPolicy.merchantIdentity(raw).orEmpty()
            val pickups = parsed.pickupAddresses.map(::addressKey).joinToString("|")
                .ifBlank { LiveOfferUserDismissalPolicy.merchantIdentity(
                    parsed.merchantNames.firstOrNull() ?: parsed.restaurant,
                ).orEmpty() }
            val dropoffs = parsed.dropoffAddresses.map(::addressKey).joinToString("|")
            val routeFingerprint = if (packageName == CourierSignals.WOLT_PACKAGE) {
                AutomaticWoltRouteCoordinator.routeFingerprint(parsed).orEmpty()
            } else {
                ""
            }
            return listOf(
                packageName,
                parsed.priceCents?.toString().orEmpty(),
                pickups,
                dropoffs,
                routeFingerprint,
                parsed.isIncrementalOffer.toString(),
            ).joinToString("¦")
        }
    }
}

/** Window attachment is presentation state, not an offer lifetime transition. */
internal object LiveOfferSessionVisibilityPolicy {
    fun shouldAttach(userHidden: Boolean, temporarilyHidden: Boolean): Boolean =
        !userHidden && !temporarilyHidden

    /** Only a confirmed different offer may replace a user-hidden card session. */
    fun sameSession(transactionMatches: Boolean, current: ParsedOffer?, incoming: ParsedOffer): Boolean =
        transactionMatches && current != null && !LiveOfferResumePolicy.definitelyDifferent(current, incoming)
}
