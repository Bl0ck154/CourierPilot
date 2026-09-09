package com.block154.courierpilot

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

internal data class AccessCodeSuggestion(
    val displayAddress: String,
    val codes: List<String>,
    val platform: String,
    val updatedAt: Long,
)

internal fun CourierMetaDatabase.codesForBuilding(buildingKey: String, limit: Int = 5): List<AccessCodeRecord> {
    val out = mutableListOf<AccessCodeRecord>()
    readableDatabase.query(
        "access_codes",
        null,
        "building_key = ?",
        arrayOf(buildingKey),
        null,
        null,
        "last_seen_at DESC, seen_count DESC",
        limit.coerceIn(1, 20).toString(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            out += AccessCodeRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                buildingKey = cursor.getString(cursor.getColumnIndexOrThrow("building_key")),
                displayAddress = cursor.getString(cursor.getColumnIndexOrThrow("display_address")),
                code = cursor.getString(cursor.getColumnIndexOrThrow("code")),
                platform = cursor.getString(cursor.getColumnIndexOrThrow("platform")),
                firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
                lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
            )
        }
    }
    return out
}

/**
 * Access-code parsing is deliberately only a derived hint. Raw delivery-screen text is the durable
 * source of truth. These rules therefore prefer suppressing a dubious hint over treating an
 * apartment number or another visible value as a building code.
 */
internal object AccessCodeHintPolicy {
    private val accessCueRegex = Regex(
        "(?iu)(door\\s*code|entry\\s*code|gate\\s*code|intercom|domofon|" +
            "dur[ųu]\\s*kod|laiptin[eė]s?\\s*kod|vart[ųu]\\s*kod|" +
            "kod\\w*\\s*(?:dur|laipt|vart)|" +
            "код\\s*(?:домоф|двер|подъезд|під.?їзд|воріт)|домофон)"
    )
    private val apartmentSuffixRegex = Regex("\\b\\d{1,4}[A-Za-z]?\\s*[-/]\\s*(\\d{1,4})\\b")
    private val apartmentLabelRegex = Regex(
        "(?iu)\\b(?:apartment|apt\\.?|flat|suite|butas|but\\.?|kv\\.?|кв\\.?|квартира)\\s*#?\\s*(\\d{1,4})\\b"
    )
    private val apartmentLabels = setOf(
        "apartment, flat or suite number",
        "apartment",
        "flat",
        "suite number",
        "butas",
        "but.",
        "kv.",
        "кв.",
        "квартира",
    )

    fun screenContainsAccessCodeInfo(text: String): Boolean = accessCueRegex.containsMatchIn(text)

    fun shouldLearnCandidate(text: String, code: String): Boolean {
        val numeric = numericToken(code) ?: return true
        return numeric !in apartmentNumbers(text)
    }

    fun isAlreadyVisible(text: String, code: String): Boolean {
        val candidate = code.trim()
        if (candidate.isEmpty()) return true
        if (text.contains(candidate, ignoreCase = true)) return true

        val compactCandidate = candidate
            .uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '#' || it == '*' }
        if (compactCandidate.isEmpty()) return true

        if (compactCandidate.all(Char::isDigit)) {
            return Regex("(?<!\\d)${Regex.escape(compactCandidate)}(?!\\d)").containsMatchIn(text)
        }

        val compactText = text
            .uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
        return compactText.contains(compactCandidate)
    }

    internal fun apartmentNumbers(text: String): Set<String> {
        val out = linkedSetOf<String>()
        apartmentSuffixRegex.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)?.let(out::add)
        }
        apartmentLabelRegex.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)?.let(out::add)
        }

        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .toList()
        lines.forEachIndexed { index, line ->
            if (line.lowercase(Locale.ROOT) !in apartmentLabels) return@forEachIndexed
            lines.getOrNull(index + 1)
                ?.trim()
                ?.takeIf { it.matches(Regex("\\d{1,4}")) }
                ?.let(out::add)
        }
        return out
    }

    private fun numericToken(code: String): String? {
        val trimmed = code.trim().trim('#', '*')
        return trimmed.takeIf { it.matches(Regex("\\d{1,4}")) }
    }
}

/**
 * Notification dedupe is independent from the ephemeral suggestion shown in the app. Reopening the
 * same courier screen may clear/rebuild AccessCodeSuggestions, but it must not re-notify the same
 * delivery. We keep a small local ring of hashed delivery keys so multi-stop screen changes cannot
 * re-arm an already-consumed hint.
 */
internal object AccessCodeNotificationGate {
    private const val PREFS = "courierpilot_access_code_notification_gate_v2"
    private const val KEY_RECORDS = "records"
    private const val RECORD_SEPARATOR = "\u001E"
    private const val FIELD_SEPARATOR = "\u001F"
    private const val TTL_MS = 3L * 60L * 60L * 1000L
    private const val MAX_RECORDS = 64

    fun deliveryKey(packageName: String, buildingKey: String, rawAddress: String): String {
        val unit = AccessCodeHintPolicy.apartmentNumbers(rawAddress).sorted().joinToString(",")
        val payload = buildString {
            append(packageName)
            append('|').append(buildingKey.lowercase(Locale.ROOT).trim())
            append("|unit=").append(unit)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun claim(context: Context, deliveryKey: String, now: Long = System.currentTimeMillis()): Boolean {
        val records = loadFresh(context, now)
        if (records.any { it.first == deliveryKey }) {
            persist(context, records)
            return false
        }
        records += deliveryKey to now
        persist(context, records)
        return true
    }

    @Synchronized
    fun consume(context: Context, deliveryKey: String, now: Long = System.currentTimeMillis()) {
        val records = loadFresh(context, now)
        records.removeAll { it.first == deliveryKey }
        records += deliveryKey to now
        persist(context, records)
    }

    @Synchronized
    internal fun clearForTests(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun loadFresh(context: Context, now: Long): MutableList<Pair<String, Long>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, "").orEmpty()
        return raw.split(RECORD_SEPARATOR)
            .mapNotNull { record ->
                val fields = record.split(FIELD_SEPARATOR, limit = 2)
                if (fields.size != 2) return@mapNotNull null
                val at = fields[1].toLongOrNull() ?: return@mapNotNull null
                if (now - at !in 0..TTL_MS) return@mapNotNull null
                fields[0] to at
            }
            .sortedBy { it.second }
            .takeLast(MAX_RECORDS)
            .toMutableList()
    }

    private fun persist(context: Context, records: List<Pair<String, Long>>) {
        val encoded = records
            .takeLast(MAX_RECORDS)
            .joinToString(RECORD_SEPARATOR) { (key, at) -> "$key$FIELD_SEPARATOR$at" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, encoded)
            .commit()
    }
}

internal object AccessCodeSuggestions {
    private const val PREFS = "courierpilot_access_code_suggestion"
    private const val MAX_AGE_MS = 4L * 60L * 60L * 1000L

    fun save(context: Context, suggestion: AccessCodeSuggestion) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("address", suggestion.displayAddress)
            .putString("codes", suggestion.codes.joinToString("\u001F"))
            .putString("platform", suggestion.platform)
            .putLong("updated_at", suggestion.updatedAt)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun latest(context: Context, now: Long = System.currentTimeMillis()): AccessCodeSuggestion? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updatedAt = prefs.getLong("updated_at", 0L)
        if (updatedAt <= 0L || now - updatedAt > MAX_AGE_MS) return null
        val address = prefs.getString("address", null)?.takeIf { it.isNotBlank() } ?: return null
        val codes = prefs.getString("codes", null)
            ?.split("\u001F")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
        if (codes.isEmpty()) return null
        return AccessCodeSuggestion(
            displayAddress = address,
            codes = codes,
            platform = prefs.getString("platform", "Courier") ?: "Courier",
            updatedAt = updatedAt,
        )
    }
}
