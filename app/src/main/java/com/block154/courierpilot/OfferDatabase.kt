package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class OfferRecord(
    val id: Long = 0,
    val capturedAt: Long,
    val platform: String,
    val packageName: String,
    val priceCents: Int,
    val distanceMeters: Int?,
    val restaurant: String?,
    val screenshotUri: String,
    val screenshotFilename: String,
    val rawText: String,
    val merchantNames: List<String> = emptyList(),
    val pickupAddresses: List<String> = emptyList(),
    val customerNames: List<String> = emptyList(),
    val dropoffAddresses: List<String> = emptyList(),
    val deliveryCount: Int? = null,
    val estimatedMinutesMin: Int? = null,
    val estimatedMinutesMax: Int? = null,
)

data class OfferSummary(
    val count: Int,
    val averagePriceCents: Double?,
    val averageDistanceMeters: Double?,
    val averageEurPerKm: Double?,
)

data class DaySummary(
    val day: String,
    val count: Int,
    val woltCount: Int,
    val boltCount: Int,
    val averagePriceCents: Double?,
    val averageEurPerKm: Double?,
)

data class ShiftRecord(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
)

data class ShiftSummary(
    val totalMillis: Long,
    val shiftCount: Int,
    val active: Boolean,
)

class OfferDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE offers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                platform TEXT NOT NULL,
                package_name TEXT NOT NULL,
                price_cents INTEGER NOT NULL,
                distance_meters INTEGER,
                restaurant TEXT,
                screenshot_uri TEXT NOT NULL,
                screenshot_filename TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                merchant_names TEXT,
                pickup_addresses TEXT,
                customer_names TEXT,
                dropoff_addresses TEXT,
                delivery_count INTEGER,
                estimated_min INTEGER,
                estimated_max INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_offers_captured_at ON offers(captured_at)")
        db.execSQL("CREATE INDEX idx_offers_platform ON offers(platform)")
        createShiftsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE offers ADD COLUMN merchant_names TEXT")
            db.execSQL("ALTER TABLE offers ADD COLUMN pickup_addresses TEXT")
            db.execSQL("ALTER TABLE offers ADD COLUMN customer_names TEXT")
            db.execSQL("ALTER TABLE offers ADD COLUMN dropoff_addresses TEXT")
            db.execSQL("ALTER TABLE offers ADD COLUMN delivery_count INTEGER")
            db.execSQL("ALTER TABLE offers ADD COLUMN estimated_min INTEGER")
            db.execSQL("ALTER TABLE offers ADD COLUMN estimated_max INTEGER")
        }
        if (oldVersion < 3) {
            createShiftsTable(db)
        }
    }

    private fun createShiftsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shifts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shifts_started_at ON shifts(started_at)")
    }

    fun insert(record: OfferRecord): Long {
        val values = ContentValues().apply {
            put("captured_at", record.capturedAt)
            put("platform", record.platform)
            put("package_name", record.packageName)
            put("price_cents", record.priceCents)
            record.distanceMeters?.let { put("distance_meters", it) }
            put("restaurant", record.restaurant)
            put("screenshot_uri", record.screenshotUri)
            put("screenshot_filename", record.screenshotFilename)
            put("raw_text", record.rawText.take(12000))
            put("merchant_names", encodeList(record.merchantNames))
            put("pickup_addresses", encodeList(record.pickupAddresses))
            put("customer_names", encodeList(record.customerNames))
            put("dropoff_addresses", encodeList(record.dropoffAddresses))
            record.deliveryCount?.let { put("delivery_count", it) }
            record.estimatedMinutesMin?.let { put("estimated_min", it) }
            record.estimatedMinutesMax?.let { put("estimated_max", it) }
        }
        return writableDatabase.insertOrThrow("offers", null, values)
    }

    fun findById(id: Long): OfferRecord? {
        readableDatabase.query(
            "offers",
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toOfferRecord() else null
        }
    }

    fun recent(limit: Int = 30): List<OfferRecord> = queryOffers(
        selection = null,
        args = null,
        limit = limit,
        offset = 0,
    )

    fun recordsSince(since: Long, limit: Int = 5000): List<OfferRecord> = queryOffers(
        selection = "captured_at >= ?",
        args = arrayOf(since.toString()),
        limit = limit,
        offset = 0,
    )

    fun searchPage(query: String, limit: Int = 50, offset: Int = 0): List<OfferRecord> {
        val spec = searchSpec(query)
        return queryOffers(spec.first, spec.second, limit.coerceIn(1, 200), offset)
    }

    fun offerCount(query: String = ""): Int {
        val spec = searchSpec(query)
        val where = spec.first?.let { " WHERE $it" }.orEmpty()
        readableDatabase.rawQuery("SELECT COUNT(*) FROM offers$where", spec.second).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun searchSpec(query: String): Pair<String?, Array<String>?> {
        val terms = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .take(8)
        if (terms.isEmpty()) return null to null
        val searchable = """
            LOWER(
                COALESCE(platform, '') || ' ' ||
                COALESCE(package_name, '') || ' ' ||
                COALESCE(restaurant, '') || ' ' ||
                COALESCE(merchant_names, '') || ' ' ||
                COALESCE(pickup_addresses, '') || ' ' ||
                COALESCE(customer_names, '') || ' ' ||
                COALESCE(dropoff_addresses, '') || ' ' ||
                COALESCE(raw_text, '') || ' ' ||
                COALESCE(screenshot_filename, '')
            )
        """.trimIndent()
        val selection = terms.joinToString(" AND ") { "$searchable LIKE ?" }
        return selection to terms.map { "%$it%" }.toTypedArray()
    }

    private fun queryOffers(selection: String?, args: Array<String>?, limit: Int, offset: Int): List<OfferRecord> {
        val out = mutableListOf<OfferRecord>()
        readableDatabase.query(
            "offers",
            null,
            selection,
            args,
            null,
            null,
            "captured_at DESC",
            "${limit.coerceIn(1, 5000)} OFFSET ${offset.coerceAtLeast(0)}",
        ).use { c ->
            while (c.moveToNext()) out += c.toOfferRecord()
        }
        return out
    }

    private fun Cursor.toOfferRecord(): OfferRecord {
        fun nullableInt(name: String): Int? = getColumnIndexOrThrow(name).let { i -> if (isNull(i)) null else getInt(i) }
        fun nullableString(name: String): String? = getColumnIndexOrThrow(name).let { i -> if (isNull(i)) null else getString(i) }
        return OfferRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            capturedAt = getLong(getColumnIndexOrThrow("captured_at")),
            platform = getString(getColumnIndexOrThrow("platform")),
            packageName = getString(getColumnIndexOrThrow("package_name")),
            priceCents = getInt(getColumnIndexOrThrow("price_cents")),
            distanceMeters = nullableInt("distance_meters"),
            restaurant = nullableString("restaurant"),
            screenshotUri = getString(getColumnIndexOrThrow("screenshot_uri")),
            screenshotFilename = getString(getColumnIndexOrThrow("screenshot_filename")),
            rawText = getString(getColumnIndexOrThrow("raw_text")),
            merchantNames = decodeList(nullableString("merchant_names")),
            pickupAddresses = decodeList(nullableString("pickup_addresses")),
            customerNames = decodeList(nullableString("customer_names")),
            dropoffAddresses = decodeList(nullableString("dropoff_addresses")),
            deliveryCount = nullableInt("delivery_count"),
            estimatedMinutesMin = nullableInt("estimated_min"),
            estimatedMinutesMax = nullableInt("estimated_max"),
        )
    }

    fun summarySince(since: Long, platform: String? = null): OfferSummary {
        val where = if (platform == null) "captured_at >= ?" else "captured_at >= ? AND platform = ?"
        val args = if (platform == null) arrayOf(since.toString()) else arrayOf(since.toString(), platform)
        val sql = """
            SELECT COUNT(*) AS count,
                   AVG(price_cents) AS avg_price,
                   AVG(distance_meters) AS avg_distance,
                   AVG(CASE WHEN distance_meters > 0 THEN price_cents * 10.0 / distance_meters END) AS avg_per_km
            FROM offers
            WHERE $where
        """.trimIndent()
        readableDatabase.rawQuery(sql, args).use { c ->
            c.moveToFirst()
            return OfferSummary(
                count = c.getInt(0),
                averagePriceCents = if (c.isNull(1)) null else c.getDouble(1),
                averageDistanceMeters = if (c.isNull(2)) null else c.getDouble(2),
                averageEurPerKm = if (c.isNull(3)) null else c.getDouble(3),
            )
        }
    }

    fun dailyStats(limit: Int = 30): List<DaySummary> {
        val out = mutableListOf<DaySummary>()
        val sql = """
            SELECT strftime('%Y-%m-%d', captured_at / 1000, 'unixepoch', 'localtime') AS day,
                   COUNT(*) AS count,
                   SUM(CASE WHEN platform = 'Wolt' THEN 1 ELSE 0 END) AS wolt_count,
                   SUM(CASE WHEN platform = 'Bolt' THEN 1 ELSE 0 END) AS bolt_count,
                   AVG(price_cents) AS avg_price,
                   AVG(CASE WHEN distance_meters > 0 THEN price_cents * 10.0 / distance_meters END) AS avg_per_km
            FROM offers
            GROUP BY day
            ORDER BY day DESC
            LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(limit.coerceIn(1, 365).toString())).use { c ->
            while (c.moveToNext()) {
                out += DaySummary(
                    day = c.getString(0),
                    count = c.getInt(1),
                    woltCount = c.getInt(2),
                    boltCount = c.getInt(3),
                    averagePriceCents = if (c.isNull(4)) null else c.getDouble(4),
                    averageEurPerKm = if (c.isNull(5)) null else c.getDouble(5),
                )
            }
        }
        return out
    }

    fun activeShift(): ShiftRecord? {
        readableDatabase.query(
            "shifts",
            arrayOf("id", "started_at", "ended_at"),
            "ended_at IS NULL",
            null,
            null,
            null,
            "started_at DESC",
            "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ShiftRecord(c.getLong(0), c.getLong(1), null)
        }
    }

    fun startShift(now: Long = System.currentTimeMillis()): Long {
        activeShift()?.let { return it.id }
        return writableDatabase.insertOrThrow(
            "shifts",
            null,
            ContentValues().apply { put("started_at", now) },
        )
    }

    fun endActiveShift(now: Long = System.currentTimeMillis()): Boolean {
        val active = activeShift() ?: return false
        val safeEnd = now.coerceAtLeast(active.startedAt)
        return writableDatabase.update(
            "shifts",
            ContentValues().apply { put("ended_at", safeEnd) },
            "id = ? AND ended_at IS NULL",
            arrayOf(active.id.toString()),
        ) > 0
    }

    fun shiftsSince(since: Long, limit: Int = 400): List<ShiftRecord> {
        val out = mutableListOf<ShiftRecord>()
        readableDatabase.query(
            "shifts",
            arrayOf("id", "started_at", "ended_at"),
            "started_at >= ? OR ended_at >= ? OR ended_at IS NULL",
            arrayOf(since.toString(), since.toString()),
            null,
            null,
            "started_at DESC",
            limit.coerceIn(1, 1000).toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out += ShiftRecord(
                    id = c.getLong(0),
                    startedAt = c.getLong(1),
                    endedAt = if (c.isNull(2)) null else c.getLong(2),
                )
            }
        }
        return out
    }

    fun shiftSummarySince(since: Long, now: Long = System.currentTimeMillis()): ShiftSummary {
        val shifts = shiftsSince(since)
        var total = 0L
        var active = false
        shifts.forEach { shift ->
            val start = shift.startedAt.coerceAtLeast(since)
            val end = (shift.endedAt ?: now).coerceAtLeast(start)
            total += end - start
            if (shift.endedAt == null) active = true
        }
        return ShiftSummary(totalMillis = total, shiftCount = shifts.size, active = active)
    }

    companion object {
        private const val DB_NAME = "courier_offers.db"
        private const val DB_VERSION = 3
        private const val LIST_SEPARATOR = "\u001F"

        @Volatile private var instance: OfferDatabase? = null

        fun get(context: Context): OfferDatabase = instance ?: synchronized(this) {
            instance ?: OfferDatabase(context).also { instance = it }
        }

        private fun encodeList(values: List<String>): String? =
            values.map(String::trim).filter(String::isNotEmpty).distinct().takeIf { it.isNotEmpty() }?.joinToString(LIST_SEPARATOR)

        private fun decodeList(value: String?): List<String> =
            value?.split(LIST_SEPARATOR)?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    }
}
