package com.block154.courierpilot

/**
 * Re-runs the current parser against stored raw text so UI fixes also improve previously captured
 * records. Newly captured records already persist these fields, but older rows may contain parser
 * mistakes from earlier app versions.
 */
internal fun OfferRecord.withCurrentParsedStructure(): OfferRecord {
    if (rawText.isBlank()) return this
    val parsed = OfferParser.parse(rawText)
    return copy(
        distanceMeters = parsed.distanceMeters ?: distanceMeters,
        restaurant = parsed.restaurant ?: restaurant,
        merchantNames = parsed.merchantNames.takeIf { it.isNotEmpty() } ?: merchantNames,
        pickupAddresses = parsed.pickupAddresses.takeIf { it.isNotEmpty() } ?: emptyList(),
        customerNames = parsed.customerNames.takeIf { it.isNotEmpty() } ?: emptyList(),
        dropoffAddresses = parsed.dropoffAddresses.takeIf { it.isNotEmpty() } ?: emptyList(),
        deliveryCount = parsed.deliveryCount ?: deliveryCount,
        estimatedMinutesMin = parsed.estimatedMinutesMin ?: estimatedMinutesMin,
        estimatedMinutesMax = parsed.estimatedMinutesMax ?: estimatedMinutesMax,
    )
}
