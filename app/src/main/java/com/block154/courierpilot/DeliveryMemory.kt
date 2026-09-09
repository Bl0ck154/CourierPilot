package com.block154.courierpilot

import android.content.Context
import android.widget.Toast

/**
 * Learns local delivery context for customer buildings seen in the courier apps.
 * OCR never mutates durable address memory. Accessibility text must additionally pass the
 * platform-aware DeliveryAddressPersistenceGate.
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
        val parsed = OfferParser.parse(text)
        OfferState.pending(context)?.let { pending -> LiveAdvisorHub.hideForCapture(context, pending) }

        if (CourierSignals.looksLikeOfferScreen(text, parsed)) {
            CourierPresence.markOfferOnline(context, packageName, "offer screen")
        }

        // OCR may enrich live offer parsing, but cannot advance lifecycle or mutate address memory.
        if (source != ScreenTextSource.ACCESSIBILITY) return
        LiveAdvisorHub.observeScreen(context, packageName, text)

        val platform = OfferState.platformLabel(packageName)
        val database = CourierMetaDatabase.get(context)
        val screenDetails = DeliveryScreenDetailsExtractor.extractForPlatform(packageName, text)
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
            // Parsed fields are only optional convenience metadata. The complete trusted
            // Accessibility frame is always passed as rawText and remains the durable source of
            // truth for later parsing/re-processing.
            val customer = matchedScreenDetails?.customerName
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.takeUnless(AddressMetadataCleanup::isUiGarbage)
            val detailsText = matchedScreenDetails?.asDetailsText() ?: addressContext(text, rawAddress)

            runCatching {
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

        fun unitHintFor(address: String): String? = screenDetails?.takeIf { details ->
            val detailsAddress = details.address ?: return@takeIf false
            DeliveryAddressNormalizer.matchScore(detailsAddress, address) >= 0.86
        }?.apartment

        val deliveryKeys = detectedAddresses.mapNotNull { address ->
            val canonical = AddressMemoryResolver.canonicalize(context, database, address)
                ?: DeliveryAddressNormalizer.normalize(address)
                ?: return@mapNotNull null
            AccessCodeNotificationGate.deliveryKey(
                packageName = packageName,
                buildingKey = canonical.first,
                rawAddress = address,
                unitHint = unitHintFor(address),
            )
        }.distinct()

        // Code extraction is only a best-effort derived view over the raw snapshot. Never let a
        // numeric apartment/flat value become a learned code. If the current order already exposes
        // access-code information, suppress historical hints for this delivery even when the exact
        // code format is too unusual for our parser; the courier can already see the authoritative
        // current-order text.
        val extractedCodeObservations = CourierSignals.extractAccessCodeObservations(text, fallback)
        val observations = extractedCodeObservations
            .filter { AccessCodeHintPolicy.shouldLearnCandidate(text, it.code) }
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

        val currentOrderShowsAccessInfo = extractedCodeObservations.isNotEmpty() ||
            AccessCodeHintPolicy.screenContainsAccessCodeInfo(text)
        if (currentOrderShowsAccessInfo) {
            AccessCodeSuggestions.clear(context)
            deliveryKeys.forEach { AccessCodeNotificationGate.consume(context, it) }
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
            val known = usableHistoricalCodes(
                context = context,
                database = database,
                address = address,
                buildingKey = canonical.first,
                currentText = text,
            )
            if (known.isEmpty()) continue

            val deliveryKey = AccessCodeNotificationGate.deliveryKey(
                packageName = packageName,
                buildingKey = canonical.first,
                rawAddress = address,
                unitHint = unitHintFor(address),
            )

            val suggestion = AccessCodeSuggestion(
                displayAddress = canonical.second,
                codes = known,
                platform = platform,
                updatedAt = System.currentTimeMillis(),
            )
            AccessCodeSuggestions.save(context, suggestion)

            if (AccessCodeNotificationGate.claim(context, deliveryKey)) {
                AccessCodeNotifier.show(context, suggestion)
                Toast.makeText(
                    context,
                    "Possible door code · ${canonical.second}: ${known.joinToString(" / ")}",
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

    private fun usableHistoricalCodes(
        context: Context,
        database: CourierMetaDatabase,
        address: String,
        buildingKey: String,
        currentText: String,
    ): List<String> {
        val rawHistory = AddressMemoryResolver.findSaved(context, database, address)
            ?.let { saved -> database.observationsForAddress(saved.id, limit = 200) }
            .orEmpty()
            .map { it.rawText }

        return database.codesForBuilding(buildingKey)
            .map { it.code }
            .distinct()
            .filterNot { code -> AccessCodeHintPolicy.isAlreadyVisible(currentText, code) }
            .filter { code ->
                // Existing installs may already contain bad numeric rows learned before this fix.
                // If any preserved raw snapshot proves that candidate was actually an apartment or
                // flat number, keep the raw history but never surface that derived row as a hint.
                rawHistory.none { historicalText ->
                    !AccessCodeHintPolicy.shouldLearnCandidate(historicalText, code)
                }
            }
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
