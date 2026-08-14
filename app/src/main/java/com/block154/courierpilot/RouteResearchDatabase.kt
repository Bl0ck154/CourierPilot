package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal enum class RouteComparisonVerdict {
    PEDESTRIAN_BETTER,
    CYCLEWAY_BETTER,
    BOTH_OK,
    BOTH_BAD,
}

/** Isolated research storage. Production offer capture never depends on this database. */
internal class RouteResearchDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE route_comparisons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                start_lat REAL NOT NULL,
                start_lon REAL NOT NULL,
                end_lat REAL NOT NULL,
                end_lon REAL NOT NULL,
                verdict TEXT NOT NULL,
                notes TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE route_candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                comparison_id INTEGER NOT NULL,
                profile TEXT NOT NULL,
                distance_m INTEGER,
                duration_s INTEGER,
                encoded_shape TEXT,
                http_status INTEGER,
                warnings TEXT,
                FOREIGN KEY(comparison_id) REFERENCES route_comparisons(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_route_candidates_comparison ON route_candidates(comparison_id)")

        db.execSQL("""
            CREATE TABLE gps_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                source_platform TEXT,
                purpose TEXT NOT NULL DEFAULT 'route_learning'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE gps_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_m REAL,
                speed_mps REAL,
                FOREIGN KEY(session_id) REFERENCES gps_sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_gps_samples_session_time ON gps_samples(session_id, recorded_at)")
        db.execSQL("""
            CREATE TABLE matched_edge_traversals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                edge_id TEXT NOT NULL,
                entered_at INTEGER NOT NULL,
                exited_at INTEGER NOT NULL,
                distance_m INTEGER NOT NULL,
                matcher_provider TEXT NOT NULL,
                matcher_confidence REAL,
                FOREIGN KEY(session_id) REFERENCES gps_sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_edge_traversals_edge ON matched_edge_traversals(edge_id, exited_at)")
        db.execSQL("""
            CREATE TABLE delivery_timeline_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                offer_id INTEGER,
                event_type TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                stop_key TEXT,
                source TEXT NOT NULL,
                confidence REAL NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_delivery_events_offer_time ON delivery_timeline_events(offer_id, occurred_at)")
        db.execSQL("""
            CREATE TABLE venue_wait_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                venue_key TEXT NOT NULL,
                arrived_at INTEGER NOT NULL,
                picked_up_at INTEGER NOT NULL,
                platform TEXT,
                offer_id INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_venue_wait_key ON venue_wait_observations(venue_key, picked_up_at)")
        db.execSQL("""
            CREATE TABLE bolt_map_ground_truth (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                current_lat REAL,
                current_lon REAL,
                current_accuracy_m REAL,
                marker_kind TEXT NOT NULL,
                marker_screen_x REAL NOT NULL,
                marker_screen_y REAL NOT NULL,
                recovered_lat REAL,
                recovered_lon REAL,
                ground_truth_lat REAL,
                ground_truth_lon REAL,
                method TEXT,
                confidence REAL,
                notes TEXT
            )
        """.trimIndent())
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Route research DB migration $oldVersion->$newVersion is not implemented")
    }

    fun recordComparison(
        start: RoutePoint,
        end: RoutePoint,
        comparison: RouteComparison,
        verdict: RouteComparisonVerdict,
        notes: String?,
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val comparisonId = db.insertOrThrow("route_comparisons", null, ContentValues().apply {
                put("created_at", System.currentTimeMillis())
                put("start_lat", start.latitude); put("start_lon", start.longitude)
                put("end_lat", end.latitude); put("end_lon", end.longitude)
                put("verdict", verdict.name)
                put("notes", notes?.trim()?.takeIf { it.isNotEmpty() })
            })
            insertCandidate(db, comparisonId, RouteProfile.PEDESTRIAN_SHORTCUT, comparison.pedestrian)
            insertCandidate(db, comparisonId, RouteProfile.CYCLEWAY_BIASED, comparison.cycleway)
            db.setTransactionSuccessful()
            comparisonId
        } finally {
            db.endTransaction()
        }
    }

    fun comparisonCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM route_comparisons", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun insertCandidate(db: SQLiteDatabase, comparisonId: Long, profile: RouteProfile, result: Result<RouteResult>) {
        val route = result.getOrNull()
        db.insertOrThrow("route_candidates", null, ContentValues().apply {
            put("comparison_id", comparisonId)
            put("profile", profile.name)
            if (route != null) {
                put("distance_m", route.distanceMeters)
                put("duration_s", route.durationSeconds)
                put("encoded_shape", route.legShapes.joinToString("\n"))
                route.httpStatus?.let { put("http_status", it) }
                put("warnings", route.warnings.joinToString(" | "))
            }
        })
    }

    companion object {
        private const val DB_NAME = "route_research.db"
        private const val DB_VERSION = 1
        @Volatile private var instance: RouteResearchDatabase? = null
        fun get(context: Context): RouteResearchDatabase = instance ?: synchronized(this) {
            instance ?: RouteResearchDatabase(context).also { instance = it }
        }
    }
}
