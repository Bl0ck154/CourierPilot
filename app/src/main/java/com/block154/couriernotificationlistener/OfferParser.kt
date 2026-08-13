package com.block154.couriernotificationlistener

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal data class ParsedOffer(
    val priceCents: Int?,
    val distanceMeters: Int?,
    val restaurant: String?,
)

internal object OfferParser {
    private val priceRegex = Regex(
        "(?i)(?:€\\s*|EUR\\s*)(\\d+(?:[.,]\\d{1,2})?)|(\\d+(?:[.,]\\d{1,2})?)\\s*(?:€|EUR)"
    )
    private val distanceRegex = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b")

    fun parse(text: String): ParsedOffer {
        return ParsedOffer(
            priceCents = parsePriceCents(text),
            distanceMeters = parseDistanceMeters(text),
            restaurant = guessRestaurant(text),
        )
    }

    fun platformName(packageName: String, sourceName: String): String {
        val value = "$packageName $sourceName".lowercase(Locale.ROOT)
        return when {
            "wolt" in value -> "Wolt"
            "bolt" in value -> "Bolt"
            else -> sourceName.ifBlank { packageName }
        }
    }

    private fun parsePriceCents(text: String): Int? {
        val match = priceRegex.find(text) ?: return null
        val raw = match.groupValues[1].ifBlank { match.groupValues[2] }
            .replace(',', '.')
        return runCatching {
            BigDecimal(raw)
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact()
        }.getOrNull()
    }

    private fun parseDistanceMeters(text: String): Int? {
        val match = distanceRegex.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "km" -> (value * 1000.0).toInt()
            else -> value.toInt()
        }.takeIf { it > 0 }
    }

    private fun guessRestaurant(text: String): String? {
        val generic = setOf(
            "wolt", "bolt", "new task", "new order", "new delivery", "offer",
            "accept", "decline", "reject", "pickup", "dropoff", "delivery",
            "priimti", "atmesti", "užduotis", "uzduotis", "užsakymas", "uzsakymas",
            "принять", "отклонить", "заказ", "задание",
            "прийняти", "відхилити", "замовлення", "завдання"
        )

        return text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..100 }
            .filterNot { priceRegex.containsMatchIn(it) }
            .filterNot { distanceRegex.matches(it) }
            .filterNot { it.lowercase(Locale.ROOT) in generic }
            .filterNot { line ->
                val lower = line.lowercase(Locale.ROOT)
                lower.startsWith("€") ||
                    lower.startsWith("eur") ||
                    lower.matches(Regex("^[\\d:.,%+\\- ]+$"))
            }
            .firstOrNull()
    }
}
