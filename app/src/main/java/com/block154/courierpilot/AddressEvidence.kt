package com.block154.courierpilot

/**
 * Text provenance matters for address memory.
 *
 * Accessibility is the durable source for building identity. OCR is deliberately allowed to enrich
 * offer parsing (price, distance, labels) but must never create or mutate address-memory rows.
 */
internal enum class ScreenTextSource {
    ACCESSIBILITY,
    OCR_AUGMENTED,
}

/**
 * Explicit evidence attached to every durable address write.
 *
 * This makes persistence fail closed: a future parser cannot accidentally turn arbitrary OCR/UI
 * text into a building unless it deliberately supplies an evidence class that is allowed to create
 * one.
 */
internal enum class AddressEvidenceSource(
    val priority: Int,
    val canCreate: Boolean,
    val canUpdateExisting: Boolean,
) {
    ACCESSIBILITY_EXPLICIT_SECTION(priority = 100, canCreate = true, canUpdateExisting = true),
    ACCESSIBILITY_PARSED_ROUTE(priority = 95, canCreate = true, canUpdateExisting = true),
    ACCESSIBILITY_STRICT_LINE(priority = 90, canCreate = true, canUpdateExisting = true),
    ACCESSIBILITY_COMPACT_CONFIRMED(priority = 80, canCreate = true, canUpdateExisting = true),
    ACCESSIBILITY_COMPACT_PENDING(priority = 20, canCreate = false, canUpdateExisting = true),
    CAPTURED_OFFER(priority = 90, canCreate = true, canUpdateExisting = true),
    OCR_AUGMENTED(priority = 0, canCreate = false, canUpdateExisting = false),
    ;

    fun canCreateAddress(raw: String): Boolean {
        if (!canCreate) return false
        if (DeliveryAddressNormalizer.isRejectedAddressArtifact(raw)) return false
        if (DeliveryAddressNormalizer.identity(raw) == null) return false
        return when (this) {
            ACCESSIBILITY_EXPLICIT_SECTION -> true
            ACCESSIBILITY_COMPACT_CONFIRMED -> DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(raw)
            ACCESSIBILITY_PARSED_ROUTE,
            ACCESSIBILITY_STRICT_LINE,
            CAPTURED_OFFER,
            -> DeliveryAddressNormalizer.hasStrongAddressEvidence(raw)
            ACCESSIBILITY_COMPACT_PENDING,
            OCR_AUGMENTED,
            -> false
        }
    }
}

internal data class AddressCandidate(
    val raw: String,
    val evidence: AddressEvidenceSource,
)

/**
 * Converts trusted Accessibility text into provenance-carrying address candidates.
 *
 * The old pipeline flattened every line into the same bucket. This extractor intentionally keeps
 * strong structural evidence separate from broad compact `Street 7` guesses.
 */
internal object AddressEvidenceExtractor {
    fun fromAccessibility(
        text: String,
        parsed: ParsedOffer = OfferParser.parse(text),
        screenDetails: DeliveryScreenDetails? = DeliveryScreenDetailsExtractor.extract(text),
    ): List<AddressCandidate> {
        val byBuilding = linkedMapOf<String, AddressCandidate>()

        fun add(raw: String?, evidence: AddressEvidenceSource) {
            val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return
            if (DeliveryAddressNormalizer.isRejectedAddressArtifact(value)) return
            val normalized = DeliveryAddressNormalizer.normalize(value) ?: return
            val previous = byBuilding[normalized.first]
            if (previous == null || evidence.priority > previous.evidence.priority) {
                byBuilding[normalized.first] = AddressCandidate(value, evidence)
            }
        }

        // Exact labelled Address fields are authoritative even when the courier app omits `g.`.
        add(screenDetails?.address, AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION)
        add(DeliveryScreenDetailsExtractor.addressValue(text), AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION)

        // Route parser output is already constrained to explicit street/city/postcode shapes.
        parsed.pickupAddresses.forEach { add(it, AddressEvidenceSource.ACCESSIBILITY_PARSED_ROUTE) }
        parsed.dropoffAddresses.forEach { add(it, AddressEvidenceSource.ACCESSIBILITY_PARSED_ROUTE) }

        // Strict line detector requires a street marker, city or postal-code evidence.
        CourierSignals.likelyAddresses(text).forEach {
            add(it, AddressEvidenceSource.ACCESSIBILITY_STRICT_LINE)
        }

        // Compact forms remain useful (`Pylimo 9`, `Vokiečių 7`) but are only candidates. A new
        // building needs stable repeated Accessibility evidence before persistence.
        DeliveryAddressNormalizer.likelyAddressLines(text).forEach {
            add(it, AddressEvidenceSource.ACCESSIBILITY_COMPACT_PENDING)
        }

        return byBuilding.values.toList()
    }
}

/**
 * Two-frame confirmation for compact no-marker addresses.
 *
 * Pending evidence is intentionally process-local. After a process restart the app asks for fresh
 * evidence rather than resurrecting a stale one-frame guess.
 */
internal object CompactAddressConfirmationGate {
    private const val WINDOW_MS = 30_000L
    private const val STALE_MS = 60_000L
    private const val MAX_PENDING = 96

    private data class Pending(
        val lastSeenAt: Long,
        val count: Int,
    )

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun confirm(
        packageName: String,
        rawAddress: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(rawAddress)) return false
        val normalized = DeliveryAddressNormalizer.normalize(rawAddress) ?: return false
        val key = "$packageName|${normalized.first}"

        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenAt > STALE_MS) iterator.remove()
        }

        val previous = pending[key]
        val count = if (previous != null && now - previous.lastSeenAt <= WINDOW_MS) {
            previous.count + 1
        } else {
            1
        }
        pending[key] = Pending(now, count)

        while (pending.size > MAX_PENDING) {
            val oldest = pending.entries.minByOrNull { it.value.lastSeenAt } ?: break
            pending.remove(oldest.key)
        }
        return count >= 2
    }

    @Synchronized
    internal fun resetForTests() {
        pending.clear()
    }
}
