package com.block154.courierpilot

import android.content.Context
import android.widget.Toast

/**
 * Learns local delivery context for customer buildings seen in the courier apps.
 *
 * Address memory stores observations of customer-side courier screens. It must never imply that the
 * courier physically visited a building merely because an offer displayed that address.
 *
 * OCR-augmented text is useful to the live offer parser/advisor but is never allowed to mutate
 * address memory. Accessibility text must additionally pass DeliveryAddressPersistenceGate so
 * restaurant/menu screens cannot teach durable addresses.
 */
internal object DeliveryMemory {
    private const val PREFS = "courierpilot_delivery_memory"
    private const val ADDRESS_CONTEXT_TTL_MS = 90_000L

    private data class DetectedAddress(
        val raw: String,
        val normalized: Pair<String, String>,
        val evidence: AddressEvidenceSource,
    )

    fun observeScreen(
        context: Context,
        packageName: String,
        text: String,
        source: ScreenTextSource = ScreenTextSource.ACCESSIBILITY,
    ) {
        if (text.isBlank()) return

        LiveAdvisorHub.attach(context)
        if (OfferState.pending(context) != null) LiveAdvisorHub.hideForCapture(context)

        val parsed = OfferParser.parse(text)
        if (CourierSignals.looksLikeOfferScreen(text, parsed)) {
            CourierPresence.markOfferOnline(context, packageName, "offer screen")
        }

        // OCR may enrich the live offer card, but it cannot advance delivery lifecycle state or
        // create/update buildings, customers, access codes or aliases. Otherwise a hallucinated OCR
        // pickup cue could unlock durable persistence for a later restaurant frame.
        if (source != ScreenTextSource.ACCESSIBILITY) return
        LiveAdvisorHub.observeScreen(context, packageName, text)

        val platform = OfferState.platformLabel(packageName)
        val database = CourierMetaDatabase.get(context)
        val screenDetails = DeliveryScreenDetailsExtractor.extract(text)
        val persistence = DeliveryAddressPersistenceGate.evaluate(context, packageName, text, screenDetails)
        if (!persistence.allowed) {
            CaptureEventLog.append(
                context,
                stage = "address_memory_skipped",
                platform = platform,
                message = persistence.reason.name,
                dedupeWindowMs = 15_000L,
            )
            return
        }

        val candidates = AddressEvidenceExtractor.fromAccessibility(text, parsed, screenDetails)
        val allAddresses = candidates.mapNotNull { candidate ->
            val normalized = DeliveryAddressNormalizer.normalize(candidate.raw) ?: return@mapNotNull null
            val evidence = if (candidate.evidence == AddressEvidenceSource.ACCESSIBILITY_COMPACT_PENDING) {
                val existing = AddressMemoryResolver.findSaved(context, database, candidate.raw)
                when {
                    existing != null -> AddressEvidenceSource.ACCESSIBILITY_COMPACT_PENDING
                    CompactAddressConfirmationGate.confirm(packageName, candidate.raw) ->
                        AddressEvidenceSource.ACCESSIBILITY_COMPACT_CONFIRMED
                    else -> return@mapNotNull null
                }
            } else {
                candidate.evidence
            }
            DetectedAddress(candidate.raw, normalized, evidence)
        }.distinctBy { it.normalized.first }

        val detectedAddresses = allAddresses.map { it.raw }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = addressKey(packageName)
        val now = System.currentTimeMillis()
        val previous = prefs.getString(key, null)
        val previousAt = prefs.getLong("${key}_at", 0L)
        val recentPrevious = previous?.takeIf { now - previousAt in 0..ADDRESS_CONTEXT_TTL_MS }
        val fallback = detectedAddresses.lastOrNull() ?: recentPrevious
        if (detectedAddresses.isNotEmpty()) {
            prefs.edit()
                .putString(key, detectedAddresses.last())
                .putLong("${key}_at", now)
                .apply()
        }

        allAddresses.forEach { detected ->
            val rawAddress = detected.raw
            val matchedScreenDetails = screenDetails?.takeIf { details ->
                val detailsAddress = details.address ?: return@takeIf false
                DeliveryAddressNormalizer.matchScore(detailsAddress, rawAddress) >= 0.86
            }
            val customer = customerForAddress(parsed, rawAddress) ?: matchedScreenDetails?.customerName
            val detailsText = matchedScreenDetails?.asDetailsText() ?: addressContext(text, rawAddress)

            runCatching {
                // Always let the resolver see the newest trusted customer frame. It updates latest
                // details/raw text while internally suppressing duplicate observation rows.
                val saved = AddressMemoryResolver.saveObservation(
                    context = context,
                    database = database,
                    address = rawAddress,
                    platform = platform,
                    customerName = customer,
                    detailsText = detailsText,
                    rawText = text,
                    evidence = detected.evidence,
                )

                if (saved != null) {
                    customer?.let {
                        database.saveAddressEntity(
                            addressId = saved.addressId,
                            entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
                            name = it,
                            platform = platform,
                        )
                    }
                    // Only a genuinely new local identity reaches the network fallback. Repeated
                    // normal captures stay entirely local.
                    AddressGeoAliasResolver.scheduleForPossibleAlias(context, database, saved, rawAddress)
                }
            }.onFailure {
                CaptureEventLog.append(
                    context,
                    stage = "address_memory_failed",
                    platform = platform,
                    message = it.javaClass.simpleName,
                    dedupeWindowMs = 30_000L,
                )
            }
        }

        // Access-code learning may reuse only a very recent customer address from the same platform.
        // The old unbounded fallback could attach a code from a later screen/order to stale data.
        val observations = CourierSignals.extractAccessCodeObservations(text, fallback)
            .mapNotNull { observation ->
                val canonical = AddressMemoryResolver.canonicalize(context, database, observation.displayAddress)
                    ?: DeliveryAddressNormalizer.normalize(observation.displayAddress)
                    ?: return@mapNotNull null
                AccessCodeObservation(
                    buildingKey = canonical.first,
                    displayAddress = canonical.second,
                    code = observation.code,
                )
            }
            .distinctBy { "${it.buildingKey}|${it.code}" }
        if (observations.isNotEmpty()) {
            AccessCodeSuggestions.clear(context)
            observations.forEach { observation ->
                runCatching { database.saveAccessCode(observation, platform) }
                    .onSuccess {
                        CaptureEventLog.append(
                            context,
                            stage = "access_code",
                            platform = platform,
                            message = "Building access code learned locally",
                            dedupeWindowMs = 30_000L,
                        )
                    }
                    .onFailure {
                        CaptureEventLog.append(
                            context,
                            stage = "access_code_failed",
                            platform = platform,
                            message = it.javaClass.simpleName,
                            dedupeWindowMs = 30_000L,
                        )
                    }
            }
            return
        }

        var matched = false
        for (address in detectedAddresses.asReversed().distinct()) {
            val canonical = AddressMemoryResolver.canonicalize(context, database, address)
                ?: DeliveryAddressNormalizer.normalize(address)
                ?: continue
            val known = database.codesForBuilding(canonical.first)
            if (known.isEmpty()) continue
            val codes = known.map { it.code }.distinct()
            val oldSuggestion = AccessCodeSuggestions.latest(context)
            val sameSuggestion = oldSuggestion?.displayAddress == canonical.second && oldSuggestion.codes == codes
            val suggestion = AccessCodeSuggestion(
                displayAddress = canonical.second,
                codes = codes,
                platform = platform,
                updatedAt = System.currentTimeMillis(),
            )
            if (!sameSuggestion) {
                AccessCodeSuggestions.save(context, suggestion)
                Toast.makeText(
                    context,
                    "Possible door code · ${canonical.second}: ${codes.joinToString(" / ")}",
                    Toast.LENGTH_LONG,
                ).show()
                CaptureEventLog.append(
                    context,
                    stage = "access_code_match",
                    platform = platform,
                    message = "Possible historical building access code matched locally",
                    dedupeWindowMs = 30_000L,
                )
            }
            matched = true
            break
        }
        if (!matched && detectedAddresses.isNotEmpty()) AccessCodeSuggestions.clear(context)
    }

    private fun customerForAddress(parsed: ParsedOffer, address: String): String? {
        val identity = DeliveryAddressNormalizer.identity(address) ?: return null
        val index = parsed.dropoffAddresses.indexOfFirst {
            DeliveryAddressNormalizer.matchScore(it, identity.display) >= 0.99
        }
        return parsed.customerNames.getOrNull(index)
            ?.takeUnless { it.equals("Customer", ignoreCase = true) }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun addressContext(text: String, address: String): String? {
        val target = DeliveryAddressNormalizer.identity(address) ?: return null
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .toList()
        val index = lines.indexOfFirst { line ->
            DeliveryAddressNormalizer.matchScore(line, target.display) >= 0.99
        }
        if (index < 0) return null
        val from = (index - 2).coerceAtLeast(0)
        val to = (index + 14).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n").take(4_000)
    }

    private fun addressKey(packageName: String): String = "last_address_${packageName.replace('.', '_')}"
}
