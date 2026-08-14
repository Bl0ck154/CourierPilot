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
        createV1Tables(db)
        createV2Tables(db)
    }

    private fun createV1Tables(db: SQLiteDatabase) {
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

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE live_advisor_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                offer_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                platform TEXT NOT NULL,
                price_cents INTEGER NOT NULL,
                platform_distance_m INTEGER,
                platform_eta_min INTEGER,
                platform_eta_max INTEGER,
                status TEXT NOT NULL,
                failure_reason TEXT,
                location_accuracy_m REAL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_live_advisor_offer ON live_advisor_runs(offer_id, created_at)")
        db.execSQL("""
            CREATE TABLE live_advisor_waypoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                sequence_index INTEGER NOT NULL,
                kind TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                provenance TEXT NOT NULL,
                confidence REAL NOT NULL,
                label TEXT,
                FOREIGN KEY(run_id) REFERENCES live_advisor_runs(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_live_advisor_waypoints_run ON live_advisor_waypoints(run_id, sequence_index)")
        db.execSQL("""
            CREATE TABLE live_advisor_candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                profile TEXT NOT NULL,
                distance_m INTEGER,
                duration_s INTEGER,
                http_status INTEGER,
                warnings TEXT,
                FOREIGN KEY(run_id) REFERENCES live_advisor_runs(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_live_advisor_candidates_run ON live_advisor_candidates(run_id)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
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

    fun recordLiveAdvisorRun(
        offerId: Long,
        platform: String,
        parsed: ParsedOffer,
        waypoints: List<ResolvedWaypoint>,
        locationAccuracyMeters: Float?,
        comparison: RouteComparison?,
        failureReason: String? = null,
    ): Long {
        val price = requireNotNull(parsed.priceCents) { "Advisor run requires priced offer" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val runId = db.insertOrThrow("live_advisor_runs", null, ContentValues().apply {
                put("offer_id", offerId)
                put("created_at", System.currentTimeMillis())
                put("platform", platform)
                put("price_cents", price)
                parsed.distanceMeters?.let { put("platform_distance_m", it) }
                parsed.estimatedMinutesMin?.let { put("platform_eta_min", it) }
                parsed.estimatedMinutesMax?.let { put("platform_eta_max", it) }
                put("status", if (comparison == null) "FAILED" else "COMPARED")
                put("failure_reason", failureReason?.trim()?.take(240))
                locationAccuracyMeters?.let { put("location_accuracy_m", it) }
            })
            waypoints.forEachIndexed { index, waypoint ->
                db.insertOrThrow("live_advisor_waypoints", null, ContentValues().apply {
                    put("run_id", runId)
                    put("sequence_index", index)
                    put("kind", waypoint.kind.name)
                    put("latitude", waypoint.point.latitude)
                    put("longitude", waypoint.point.longitude)
                    put("provenance", waypoint.provenance.name)
                    put("confidence", waypoint.confidence)
                    put("label", waypoint.label?.take(160))
                })
            }
            comparison?.let {
                insertLiveCandidate(db, runId, RouteProfile.PEDESTRIAN_SHORTCUT, it.pedestrian)
                insertLiveCandidate(db, runId, RouteProfile.CYCLEWAY_BIASED, it.cycleway)
            }
            db.setTransactionSuccessful()
            runId
        } finally {
            db.endTransaction()
        }
    }

    fun recordDeliveryEvent(offerId: Long?, event: DeliveryTimelineEvent): Long = writableDatabase.insertOrThrow(
        "delivery_timeline_events",
        null,
        ContentValues().apply {
            offerId?.let { put("offer_id", it) }
            put("event_type", event.type.name)
            put("occurred_at", event.timestampMillis)
            put("stop_key", event.stopKey)
            put("source", event.source)
            put("confidence", event.confidence)
        },
    )

    fun deliveryEvents(offerId: Long): List<DeliveryTimelineEvent> = readableDatabase.rawQuery(
        "SELECT event_type, occurred_at, stop_key, source, confidence FROM delivery_timeline_events WHERE offer_id=? ORDER BY occurred_at ASC",
        arrayOf(offerId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val type = runCatching { DeliveryEventType.valueOf(cursor.getString(0)) }.getOrNull() ?: continue
                add(
                    DeliveryTimelineEvent(
                        type = type,
                        timestampMillis = cursor.getLong(1),
                        stopKey = cursor.getString(2),
                        source = cursor.getString(3),
                        confidence = cursor.getDouble(4),
                    )
                )
            }
        }
    }

    fun comparisonCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM route_comparisons", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun liveAdvisorRunCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM live_advisor_runs", null).use {
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

    private fun insertLiveCandidate(db: SQLiteDatabase, runId: Long, profile: RouteProfile, result: Result<RouteResult>) {
        val route = result.getOrNull()
        db.insertOrThrow("live_advisor_candidates", null, ContentValues().apply {
            put("run_id", runId)
            put("profile", profile.name)
            if (route != null) {
                put("distance_m", route.distanceMeters)
                put("duration_s", route.durationSeconds)
                route.httpStatus?.let { put("http_status", it) }
                put("warnings", route.warnings.joinToString(" | "))
            }
        })
    }

    companion object {
        private const val DB_NAME = "route_research.db"
        private const val DB_VERSION = 2
        @Volatile private var instance: RouteResearchDatabase? = null
        fun get(context: Context): RouteResearchDatabase = instance ?: synchronized(this) {
            instance ?: RouteResearchDatabase(context).also { instance = it }
        }
    }
}
