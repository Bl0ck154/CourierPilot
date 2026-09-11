package com.block154.courierpilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

internal data class PendingArrivalReminder(
    val deliveryKey: String,
    val buildingKey: String,
    val suggestion: AccessCodeSuggestion,
    val armedAt: Long,
    val destination: RoutePoint?,
)

/** Durable single-slot state so a process restart does not lose an armed reminder mid-delivery. */
internal object PendingArrivalReminderStore {
    private const val PREFS = "courierpilot_pending_arrival_hint_v1"
    private const val KEY_ACTIVE = "active"

    fun save(context: Context, reminder: PendingArrivalReminder) {
        val json = JSONObject()
            .put("deliveryKey", reminder.deliveryKey)
            .put("buildingKey", reminder.buildingKey)
            .put("displayAddress", reminder.suggestion.displayAddress)
            .put("platform", reminder.suggestion.platform)
            .put("updatedAt", reminder.suggestion.updatedAt)
            .put("armedAt", reminder.armedAt)
            .put("codes", JSONArray(reminder.suggestion.codes))
        reminder.destination?.let {
            json.put("latitude", it.latitude)
            json.put("longitude", it.longitude)
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE, json.toString())
            .apply()
    }

    fun load(context: Context, now: Long = System.currentTimeMillis()): PendingArrivalReminder? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACTIVE, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val armedAt = json.getLong("armedAt")
            if (now - armedAt !in 0..ArrivalAccessHintPolicy.REMINDER_TTL_MS) {
                clear(context)
                return null
            }
            val codesArray = json.getJSONArray("codes")
            val codes = buildList {
                for (i in 0 until codesArray.length()) {
                    codesArray.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }.distinct()
            if (codes.isEmpty()) {
                clear(context)
                return null
            }
            PendingArrivalReminder(
                deliveryKey = json.getString("deliveryKey"),
                buildingKey = json.getString("buildingKey"),
                suggestion = AccessCodeSuggestion(
                    displayAddress = json.getString("displayAddress"),
                    codes = codes,
                    platform = json.optString("platform"),
                    updatedAt = json.optLong("updatedAt", armedAt),
                ),
                armedAt = armedAt,
                destination = if (json.has("latitude") && json.has("longitude")) {
                    RoutePoint(json.getDouble("latitude"), json.getDouble("longitude"))
                } else null,
            )
        }.getOrElse {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE)
            .apply()
    }
}

internal data class AccessHintFeedback(
    val confirmations: Int,
    val rejections: Int,
    val lastConfirmedAt: Long,
    val lastRejectedAt: Long,
)

/** User feedback is derived state. Raw delivery observations remain the source of truth. */
internal object AccessHintFeedbackStore {
    private const val PREFS = "courierpilot_access_hint_feedback_v1"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun markConfirmed(context: Context, buildingKey: String, code: String, now: Long = System.currentTimeMillis()) {
        mutate(context, buildingKey, code) { current ->
            current.copy(
                confirmations = current.confirmations + 1,
                lastConfirmedAt = now,
            )
        }
    }

    fun markRejected(context: Context, buildingKey: String, code: String, now: Long = System.currentTimeMillis()) {
        mutate(context, buildingKey, code) { current ->
            current.copy(
                rejections = current.rejections + 1,
                lastRejectedAt = now,
            )
        }
    }

    /** Fresh live evidence revives a code that had previously been marked wrong/old. */
    fun markObserved(context: Context, buildingKey: String, code: String, now: Long = System.currentTimeMillis()) {
        val current = get(context, buildingKey, code)
        if (current.lastRejectedAt <= 0L || now <= current.lastRejectedAt) return
        write(
            context,
            buildingKey,
            code,
            current.copy(rejections = 0, lastRejectedAt = 0L),
        )
    }

    fun shouldSurface(
        context: Context,
        record: AccessCodeRecord,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val feedback = get(context, record.buildingKey, record.code)
        val newestPositiveEvidence = maxOf(record.lastSeenAt, feedback.lastConfirmedAt)
        if (feedback.lastRejectedAt > newestPositiveEvidence) return false

        val age = (now - record.lastSeenAt).coerceAtLeast(0L)
        if (feedback.confirmations > 0) return true
        if (age <= 180L * DAY_MS) return true
        if (record.seenCount >= 2 && age <= 365L * DAY_MS) return true
        return false
    }

    fun confidenceLabel(context: Context, record: AccessCodeRecord, now: Long = System.currentTimeMillis()): String {
        val feedback = get(context, record.buildingKey, record.code)
        val age = (now - record.lastSeenAt).coerceAtLeast(0L)
        return when {
            feedback.lastRejectedAt > maxOf(record.lastSeenAt, feedback.lastConfirmedAt) -> "rejected"
            feedback.confirmations >= 2 || (record.seenCount >= 4 && age <= 90L * DAY_MS) -> "high"
            feedback.confirmations >= 1 || record.seenCount >= 2 || age <= 30L * DAY_MS -> "medium"
            else -> "low"
        }
    }

    private fun get(context: Context, buildingKey: String, code: String): AccessHintFeedback {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(buildingKey, code), null)
            ?: return AccessHintFeedback(0, 0, 0L, 0L)
        return runCatching {
            val json = JSONObject(raw)
            AccessHintFeedback(
                confirmations = json.optInt("confirmations", 0),
                rejections = json.optInt("rejections", 0),
                lastConfirmedAt = json.optLong("lastConfirmedAt", 0L),
                lastRejectedAt = json.optLong("lastRejectedAt", 0L),
            )
        }.getOrDefault(AccessHintFeedback(0, 0, 0L, 0L))
    }

    private fun mutate(
        context: Context,
        buildingKey: String,
        code: String,
        change: (AccessHintFeedback) -> AccessHintFeedback,
    ) = write(context, buildingKey, code, change(get(context, buildingKey, code)))

    private fun write(context: Context, buildingKey: String, code: String, feedback: AccessHintFeedback) {
        val json = JSONObject()
            .put("confirmations", feedback.confirmations)
            .put("rejections", feedback.rejections)
            .put("lastConfirmedAt", feedback.lastConfirmedAt)
            .put("lastRejectedAt", feedback.lastRejectedAt)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(buildingKey, code), json.toString())
            .apply()
    }

    private fun key(buildingKey: String, code: String): String = digest(
        buildingKey.trim().lowercase(Locale.ROOT) + "|" + canonicalCode(code)
    )
}

/** Learns an actual entrance area only from explicit positive user feedback. */
internal object LearnedEntranceStore {
    private const val PREFS = "courierpilot_learned_entrances_v1"
    private const val MAX_SAMPLES = 8
    private const val MIN_SAMPLES = 2
    private const val MAX_SAMPLE_AGE_MS = 180L * 24L * 60L * 60L * 1000L
    private const val MAX_OUTLIER_DISTANCE_METERS = 220.0

    private data class Sample(val point: RoutePoint, val at: Long)

    fun record(context: Context, buildingKey: String, fix: CurrentLocationFix, now: Long = System.currentTimeMillis()) {
        if (buildingKey.isBlank()) return
        if (fix.ageMillis > 60_000L) return
        if (fix.accuracyMeters == null || fix.accuracyMeters > 80f) return
        val samples = read(context, buildingKey, now).toMutableList()
        samples += Sample(fix.point, now)
        val trimmed = samples.takeLast(MAX_SAMPLES)
        val json = JSONArray()
        trimmed.forEach { sample ->
            json.put(
                JSONObject()
                    .put("lat", sample.point.latitude)
                    .put("lon", sample.point.longitude)
                    .put("at", sample.at)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(buildingKey), json.toString())
            .apply()
    }

    fun preferred(context: Context, buildingKey: String, now: Long = System.currentTimeMillis()): RoutePoint? {
        val samples = read(context, buildingKey, now)
        if (samples.size < MIN_SAMPLES) return null
        val firstMedian = medianPoint(samples.map { it.point })
        val inliers = samples.map { it.point }.filter {
            ArrivalAccessHintPolicy.distanceMeters(it, firstMedian) <= MAX_OUTLIER_DISTANCE_METERS
        }
        if (inliers.size < MIN_SAMPLES) return null
        return medianPoint(inliers)
    }

    fun sampleCount(context: Context, buildingKey: String, now: Long = System.currentTimeMillis()): Int =
        read(context, buildingKey, now).size

    private fun read(context: Context, buildingKey: String, now: Long): List<Sample> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(buildingKey), null)
            ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val at = item.optLong("at", 0L)
                    if (now - at !in 0..MAX_SAMPLE_AGE_MS) continue
                    add(Sample(RoutePoint(item.getDouble("lat"), item.getDouble("lon")), at))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun medianPoint(points: List<RoutePoint>): RoutePoint {
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
        }
        return RoutePoint(
            latitude = median(points.map { it.latitude }),
            longitude = median(points.map { it.longitude }),
        )
    }

    private fun key(buildingKey: String): String = digest(buildingKey.trim().lowercase(Locale.ROOT))
}

private fun canonicalCode(value: String): String = value
    .trim()
    .uppercase(Locale.ROOT)
    .replace(Regex("\\s+"), "")

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(12)
    .joinToString("") { "%02x".format(it) }
