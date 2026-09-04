package com.block154.courierpilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CaptureEvent(
    val timestamp: Long,
    val stage: String,
    val platform: String,
    val message: String,
)

internal object CaptureEventLog {
    private const val PREFS = "courierpilot_capture_events"
    private const val KEY_EVENTS = "events_json"
    private const val MAX_EVENTS = 160
    private const val MAX_DEDUPE_KEYS = 256
    private val lastDedupeAt = LinkedHashMap<String, Long>(MAX_DEDUPE_KEYS, 0.75f, true)

    @Synchronized
    fun append(
        context: Context,
        stage: String,
        message: String,
        platform: String = "",
        dedupeWindowMs: Long = 0L,
    ) {
        val now = System.currentTimeMillis()
        val normalizedStage = stage.take(48)
        val normalizedPlatform = platform.take(24)
        val normalizedMessage = message.take(320)
        if (dedupeWindowMs > 0L) {
            val dedupeKey = "$normalizedStage\u0000$normalizedPlatform\u0000$normalizedMessage"
            val previousAt = lastDedupeAt[dedupeKey]
            if (previousAt != null && now - previousAt < dedupeWindowMs) return
            lastDedupeAt[dedupeKey] = now
            while (lastDedupeAt.size > MAX_DEDUPE_KEYS) {
                val oldest = lastDedupeAt.entries.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = readArray(prefs.getString(KEY_EVENTS, null))
        val event = CaptureEvent(
            timestamp = now,
            stage = normalizedStage,
            platform = normalizedPlatform,
            message = normalizedMessage,
        )
        events.put(
            JSONObject()
                .put("timestamp", event.timestamp)
                .put("stage", event.stage)
                .put("platform", event.platform)
                .put("message", event.message)
        )

        val trimmed = JSONArray()
        val start = (events.length() - MAX_EVENTS).coerceAtLeast(0)
        for (i in start until events.length()) trimmed.put(events.optJSONObject(i))
        prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply()

        // This method only hands the already-sanitized event to a dedicated executor. No HTTP or
        // remote queue serialization runs on the notification/accessibility caller thread.
        RemoteDiagnostics.enqueue(context, event)
    }

    fun recent(context: Context, limit: Int = 80): List<CaptureEvent> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = readArray(prefs.getString(KEY_EVENTS, null))
        val out = mutableListOf<CaptureEvent>()
        val start = (events.length() - limit.coerceIn(1, MAX_EVENTS)).coerceAtLeast(0)
        for (i in events.length() - 1 downTo start) {
            val item = events.optJSONObject(i) ?: continue
            out += CaptureEvent(
                timestamp = item.optLong("timestamp"),
                stage = item.optString("stage"),
                platform = item.optString("platform"),
                message = item.optString("message"),
            )
        }
        return out
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EVENTS).apply()
    }

    fun asText(context: Context, limit: Int = 120): String = buildString {
        recent(context, limit).asReversed().forEach { event ->
            append(event.timestamp)
            append('\t')
            append(event.stage)
            if (event.platform.isNotBlank()) {
                append('\t')
                append(event.platform)
            }
            append('\t')
            append(event.message.replace('\n', ' '))
            append('\n')
        }
    }.trimEnd()

    private fun readArray(raw: String?): JSONArray = try {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (_: Throwable) {
        JSONArray()
    }
}
