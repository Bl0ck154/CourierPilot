package com.block154.courierpilot

import android.content.Context

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
        "seen_count DESC, last_seen_at DESC",
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
