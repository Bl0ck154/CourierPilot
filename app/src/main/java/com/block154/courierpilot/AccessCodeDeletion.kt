package com.block154.courierpilot

import java.util.Locale

/** Removes a derived access hint without touching the raw address-screen history. */
internal object AccessCodeDeletion {
    fun delete(
        database: CourierMetaDatabase,
        buildingKey: String,
        code: String,
    ): Int {
        val canonical = canonicalCode(code)
        if (buildingKey.isBlank() || canonical.isBlank()) return 0

        val db = database.writableDatabase
        val ids = mutableListOf<Long>()
        db.query(
            "access_codes",
            arrayOf("id", "code"),
            "building_key = ?",
            arrayOf(buildingKey),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (canonicalCode(cursor.getString(1)) == canonical) {
                    ids += cursor.getLong(0)
                }
            }
        }
        if (ids.isEmpty()) return 0

        val placeholders = ids.joinToString(",") { "?" }
        return db.delete(
            "access_codes",
            "id IN ($placeholders)",
            ids.map(Long::toString).toTypedArray(),
        )
    }

    fun equivalent(first: String, second: String): Boolean {
        val a = canonicalCode(first)
        val b = canonicalCode(second)
        return a.isNotBlank() && a == b
    }

    private fun canonicalCode(value: String): String = value
        .trim()
        .uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
}
