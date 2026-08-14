package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
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

    fun recent(limit: Int = 30): List<OfferRecord> {
        val out = mutableListOf<OfferRecord>()
        readableDatabase.query(
            "offers",
            null,
            null,
            null,
            null,
            null,
            "captured_at DESC",
            limit.coerceIn(1, 1000).toString(),
        ).use { c ->
            while (c.moveToNext()) {
                fun nullableInt(name: String): Int? = c.getColumnIndexOrThrow(name).let { i -> if (c.isNull(i)) null else c.getInt(i) }
                fun nullableString(name: String): String? = c.getColumnIndexOrThrow(name).let { i -> if (c.isNull(i)) null else c.getString(i) }
                out += OfferRecord(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    capturedAt = c.getLong(c.getColumnIndexOrThrow("captured_at")),
                    platform = c.getString(c.getColumnIndexOrThrow("platform")),
                    packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
                    priceCents = c.getInt(c.getColumnIndexOrThrow("price_cents")),
                    distanceMeters = nullableInt("distance_meters"),
                    restaurant = nullableString("restaurant"),
                    screenshotUri = c.getString(c.getColumnIndexOrThrow("screenshot_uri")),
                    screenshotFilename = c.getString(c.getColumnIndexOrThrow("screenshot_filename")),
                    rawText = c.getString(c.getColumnIndexOrThrow("raw_text")),
                    merchantNames = decodeList(nullableString("merchant_names")),
                    pickupAddresses = decodeList(nullableString("pickup_addresses")),
                    customerNames = decodeList(nullableString("customer_names")),
                    dropoffAddresses = decodeList(nullableString("dropoff_addresses")),
                    deliveryCount = nullableInt("delivery_count"),
                    estimatedMinutesMin = nullableInt("estimated_min"),
                    estimatedMinutesMax = nullableInt("estimated_max"),
                )
            }
        }
        return out
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

    companion object {
        private const val DB_NAME = "courier_offers.db"
        private const val DB_VERSION = 2
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
