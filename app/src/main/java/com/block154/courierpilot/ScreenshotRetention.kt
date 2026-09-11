package com.block154.courierpilot

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

internal data class ScreenshotStorageStats(
    val count: Int,
    val bytes: Long,
    val oldestAt: Long,
)

internal data class ScreenshotCleanupResult(
    val deletedCount: Int,
    val deletedBytes: Long,
)

/** Keeps Pictures/CourierOffers bounded without touching OCR or offer metadata. */
internal object ScreenshotRetentionManager {
    private const val PREFS = "courierpilot_screenshot_maintenance_v1"
    private const val KEY_LAST_CLEANUP = "last_cleanup_at"
    private const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun runIfDue(context: Context, force: Boolean = false): ScreenshotCleanupResult {
        val app = context.applicationContext
        val retentionDays = CaptureStorageSettings.retentionDays(app)
        if (retentionDays == CaptureStorageSettings.RETENTION_FOREVER) {
            return ScreenshotCleanupResult(0, 0L)
        }
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CLEANUP, 0L)
            if (now - last in 0 until CLEANUP_INTERVAL_MS) return ScreenshotCleanupResult(0, 0L)
        }

        val cutoff = now - retentionDays * DAY_MS
        var deletedCount = 0
        var deletedBytes = 0L
        val resolver = app.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val candidates = mutableListOf<Triple<Long, Long, Long>>()
        runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%CourierOffers%"),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    val taken = cursor.getLong(takenIndex)
                    val addedSeconds = cursor.getLong(addedIndex)
                    val createdAt = when {
                        taken > 0L -> taken
                        addedSeconds > 0L -> addedSeconds * 1000L
                        else -> now
                    }
                    if (createdAt < cutoff) candidates += Triple(id, size, createdAt)
                }
            }
        }.onFailure {
            CaptureEventLog.append(
                app,
                stage = "screenshot_retention_scan_failed",
                message = it.javaClass.simpleName,
                dedupeWindowMs = 60_000L,
            )
        }

        candidates.forEach { (id, size, _) ->
            val uri = ContentUris.withAppendedId(collection, id)
            val deleted = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
            if (deleted > 0) {
                deletedCount++
                deletedBytes += size
            }
        }
        prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
        if (deletedCount > 0) {
            CaptureEventLog.append(
                app,
                stage = "screenshot_retention_cleanup",
                message = "deleted=$deletedCount; bytes=$deletedBytes; retentionDays=$retentionDays",
                dedupeWindowMs = 60_000L,
            )
        }
        return ScreenshotCleanupResult(deletedCount, deletedBytes)
    }

    fun stats(context: Context): ScreenshotStorageStats {
        val app = context.applicationContext
        var count = 0
        var bytes = 0L
        var oldestAt = 0L
        val projection = arrayOf(
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        runCatching {
            app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%CourierOffers%"),
                null,
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    count++
                    bytes += cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    val taken = cursor.getLong(takenIndex)
                    val added = cursor.getLong(addedIndex) * 1000L
                    val at = taken.takeIf { it > 0L } ?: added
                    if (at > 0L && (oldestAt == 0L || at < oldestAt)) oldestAt = at
                }
            }
        }
        return ScreenshotStorageStats(count, bytes, oldestAt)
    }
}
