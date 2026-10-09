package com.block154.courierpilot

/** Bolt shows per-leg ~N min labels and a separate unprefixed total in the accept button. */
internal data class BoltEtas(
    val toPickupMin: Int?,
    val toCustomerMin: Int?,
    val totalMin: Int?,
)

internal object BoltEtaExtractor {
    private val approximateMinutes = Regex("""(?i)~\s*(\d{1,3})\s*min\b""")
    private val acceptTotal = Regex("""(?i)(?<!~)\b(\d{1,3})\s*min\b[^\n]{0,35}(?:€|eur|£|zł|₴)""")
    private val customerHint = Regex("""(?i)customer|drop[\s-]?off|client|klient|pristatym""")
    private val pickupHint = Regex("""(?i)pickup|pick[\s-]?up|restaurant|merchant|atsi[eė]m""")

    fun extract(rawText: String): BoltEtas {
        val lines = rawText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val total = lines.firstNotNullOfOrNull { line ->
            acceptTotal.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..240 }
        }
        val legs = lines.flatMap { line ->
            approximateMinutes.findAll(line).mapNotNull { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it in 1..240 }?.let { minutes ->
                    minutes to line
                }
            }.toList()
        }
        val customer = legs.firstOrNull { customerHint.containsMatchIn(it.second) }?.first
        val pickup = legs.firstOrNull { pickupHint.containsMatchIn(it.second) }?.first
            ?: legs.firstOrNull { !customerHint.containsMatchIn(it.second) }?.first
            ?: legs.firstOrNull()?.first
        val toCustomer = customer ?: legs.drop(1).firstOrNull()?.first
        return BoltEtas(pickup, toCustomer, total)
    }
}
