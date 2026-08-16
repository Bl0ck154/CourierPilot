package com.block154.courierpilot

import android.content.Context
import android.widget.Toast

/**
 * Learns local delivery context for buildings seen in the courier apps.
 *
 * Address memory stores observations of courier screens. It must never imply that the courier
 * physically visited a building merely because an offer displayed that address.
 */
internal object DeliveryMemory {
    private const val PREFS = "courierpilot_delivery_memory"

    private data class DetectedAddress(
        val raw: String,
        val normalized: Pair<String, String>,
    )

    fun observeScreen(context: Context, packageName: String, text: String) {
        if (text.isBlank()) return

        LiveAdvisorHub.attach(context)
        if (OfferState.pending(context) != null) LiveAdvisorHub.hideForCapture(context)
        LiveAdvisorHub.observeScreen(context, packageName, text)

        val parsed = OfferParser.parse(text)
        if (CourierSignals.looksLikeOfferScreen(text, parsed)) {
            CourierPresence.markOfferOnline(context, packageName, "offer screen")
        }

        // Add compact-address candidates to the legacy strict detector so user-entered forms such as
        // `Vokiečių 7` are not ignored merely because `g.` / `gatvė` or the city is absent.
        val detectedAddresses = (CourierSignals.likelyAddresses(text) + DeliveryAddressNormalizer.likelyAddressLines(text))
            .distinct()
        val allAddresses = (detectedAddresses + parsed.pickupAddresses + parsed.dropoffAddresses)
            .mapNotNull { raw -> DeliveryAddressNormalizer.normalize(raw)?.let { DetectedAddress(raw, it) } }
            .distinctBy { it.normalized.first }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = addressKey(packageName)
        val previous = prefs.getString(key, null)
        val fallback = detectedAddresses.lastOrNull() ?: previous
        if (detectedAddresses.isNotEmpty()) prefs.edit().putString(key, detectedAddresses.last()).apply()

        val platform = OfferState.platformLabel(packageName)
        val database = CourierMetaDatabase.get(context)

        allAddresses.forEach { detected ->
            val rawAddress = detected.raw
            val screenDetails = DeliveryScreenDetailsExtractor.forAddress(text, rawAddress)
            val customer = customerForAddress(parsed, rawAddress) ?: screenDetails?.customerName
            val merchant = merchantForAddress(parsed, rawAddress)
            val detailsText = screenDetails?.asDetailsText() ?: addressContext(text, rawAddress)

            runCatching {
                // Always let the resolver see the newest frame. It updates latest details/raw text on
                // the saved building while internally suppressing duplicate observation rows for a
                // short burst. This matters when a partial Bolt screen is followed by the richer
                // customer-details sheet with instructions/apartment/floor a few seconds later.
                val saved = AddressMemoryResolver.saveObservation(
                    context = context,
                    database = database,
                    address = rawAddress,
                    platform = platform,
                    customerName = customer,
                    detailsText = detailsText,
                    rawText = text,
                )

                if (saved != null) {
                    merchant?.let {
                        database.saveAddressEntity(
                            addressId = saved.addressId,
                            entityType = CourierMetaDatabase.ENTITY_VENUE,
                            name = it,
                            platform = platform,
                        )
                    }
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

    private fun merchantForAddress(parsed: ParsedOffer, address: String): String? {
        val identity = DeliveryAddressNormalizer.identity(address) ?: return null
        val index = parsed.pickupAddresses.indexOfFirst {
            DeliveryAddressNormalizer.matchScore(it, identity.display) >= 0.99
        }
        return parsed.merchantNames.getOrNull(index)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: parsed.restaurant?.trim()?.takeIf(String::isNotEmpty)?.takeIf { parsed.pickupAddresses.size <= 1 }
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
