package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class AutomaticWorkSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val startReason: String,
    val endReason: String?,
)

data class AutomaticWorkSummary(
    val totalMillis: Long,
    val sessionCount: Int,
    val active: Boolean,
)

data class AccessCodeRecord(
    val id: Long,
    val buildingKey: String,
    val displayAddress: String,
    val code: String,
    val platform: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int,
)

/**
 * Separate local database for metadata learned after an offer is shown. Keeping this out of the
 * offer-history schema makes the feature easy to remove/migrate and avoids rewriting proven capture
 * tables merely to add automatic presence tracking and building access codes.
 */
class CourierMetaDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE work_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                start_reason TEXT NOT NULL,
                end_reason TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_work_sessions_started_at ON work_sessions(started_at)")
        db.execSQL(
            """
            CREATE TABLE access_codes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                building_key TEXT NOT NULL,
                display_address TEXT NOT NULL,
                code TEXT NOT NULL,
                platform TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                UNIQUE(building_key, code)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_access_codes_last_seen ON access_codes(last_seen_at)")
        db.execSQL("CREATE INDEX idx_access_codes_building ON access_codes(building_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun activeWorkSession(): AutomaticWorkSession? {
        readableDatabase.query(
            "work_sessions",
            null,
            "ended_at IS NULL",
            null,
            null,
            null,
            "started_at DESC",
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toWorkSession() else null
        }
    }

    fun startAutomaticSession(reason: String, now: Long = System.currentTimeMillis()): Long {
        activeWorkSession()?.let { return it.id }
        return writableDatabase.insertOrThrow(
            "work_sessions",
            null,
            ContentValues().apply {
                put("started_at", now)
                put("start_reason", reason.take(160))
            },
        )
    }

    fun endAutomaticSession(reason: String, now: Long = System.currentTimeMillis()): Boolean {
        val active = activeWorkSession() ?: return false
        val safeEnd = now.coerceAtLeast(active.startedAt)
        return writableDatabase.update(
            "work_sessions",
            ContentValues().apply {
                put("ended_at", safeEnd)
                put("end_reason", reason.take(160))
            },
            "id = ? AND ended_at IS NULL",
            arrayOf(active.id.toString()),
        ) > 0
    }

    fun workSummarySince(since: Long, now: Long = System.currentTimeMillis()): AutomaticWorkSummary {
        var total = 0L
        var count = 0
        var active = false
        readableDatabase.query(
            "work_sessions",
            arrayOf("started_at", "ended_at"),
            "started_at >= ? OR ended_at >= ? OR ended_at IS NULL",
            arrayOf(since.toString(), since.toString()),
            null,
            null,
            "started_at DESC",
            "1000",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val start = cursor.getLong(0).coerceAtLeast(since)
                val end = if (cursor.isNull(1)) {
                    active = true
                    now
                } else {
                    cursor.getLong(1)
                }.coerceAtLeast(start)
                total += end - start
                count++
            }
        }
        return AutomaticWorkSummary(total, count, active)
    }

    fun saveAccessCode(
        observation: AccessCodeObservation,
        platform: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val db = writableDatabase
        db.query(
            "access_codes",
            arrayOf("id", "last_seen_at", "seen_count"),
            "building_key = ? AND code = ?",
            arrayOf(observation.buildingKey, observation.code),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val previousSeenAt = cursor.getLong(1)
                val previousCount = cursor.getInt(2)
                val values = ContentValues().apply {
                    put("display_address", observation.displayAddress)
                    put("platform", platform)
                    put("last_seen_at", now)
                    // Accessibility can emit the same screen many times. Count another observation
                    // only after five minutes so the confidence counter means something.
                    put("seen_count", if (now - previousSeenAt >= OBSERVATION_DEDUPE_MS) previousCount + 1 else previousCount)
                }
                db.update("access_codes", values, "id = ?", arrayOf(id.toString()))
                return id
            }
        }

        return db.insertOrThrow(
            "access_codes",
            null,
            ContentValues().apply {
                put("building_key", observation.buildingKey)
                put("display_address", observation.displayAddress)
                put("code", observation.code)
                put("platform", platform)
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
            },
        )
    }

    fun recentAccessCodes(limit: Int = 100): List<AccessCodeRecord> = queryAccessCodes(null, null, limit)

    fun searchAccessCodes(query: String, limit: Int = 100): List<AccessCodeRecord> {
        val clean = query.trim()
        if (clean.isEmpty()) return recentAccessCodes(limit)
        val key = clean.lowercase().replace(Regex("[^a-z0-9ąčęėįšųūž]+"), "%")
        return queryAccessCodes(
            "LOWER(display_address) LIKE ? OR LOWER(building_key) LIKE ? OR code LIKE ?",
            arrayOf("%${clean.lowercase()}%", "%$key%", "%$clean%"),
            limit,
        )
    }

    fun accessCodeCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM access_codes", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun queryAccessCodes(selection: String?, args: Array<String>?, limit: Int): List<AccessCodeRecord> {
        val out = mutableListOf<AccessCodeRecord>()
        readableDatabase.query(
            "access_codes",
            null,
            selection,
            args,
            null,
            null,
            "last_seen_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toAccessCode()
        }
        return out
    }

    private fun Cursor.toWorkSession(): AutomaticWorkSession = AutomaticWorkSession(
        id = getLong(getColumnIndexOrThrow("id")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        endedAt = getColumnIndexOrThrow("ended_at").let { if (isNull(it)) null else getLong(it) },
        startReason = getString(getColumnIndexOrThrow("start_reason")),
        endReason = getColumnIndexOrThrow("end_reason").let { if (isNull(it)) null else getString(it) },
    )

    private fun Cursor.toAccessCode(): AccessCodeRecord = AccessCodeRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        buildingKey = getString(getColumnIndexOrThrow("building_key")),
        displayAddress = getString(getColumnIndexOrThrow("display_address")),
        code = getString(getColumnIndexOrThrow("code")),
        platform = getString(getColumnIndexOrThrow("platform")),
        firstSeenAt = getLong(getColumnIndexOrThrow("first_seen_at")),
        lastSeenAt = getLong(getColumnIndexOrThrow("last_seen_at")),
        seenCount = getInt(getColumnIndexOrThrow("seen_count")),
    )

    companion object {
        private const val DB_NAME = "courier_meta.db"
        private const val DB_VERSION = 1
        private const val OBSERVATION_DEDUPE_MS = 5L * 60L * 1000L

        @Volatile private var instance: CourierMetaDatabase? = null

        fun get(context: Context): CourierMetaDatabase = instance ?: synchronized(this) {
            instance ?: CourierMetaDatabase(context).also { instance = it }
        }
    }
}
