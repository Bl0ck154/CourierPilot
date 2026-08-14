package com.block154.courierpilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class GpsTracePoint(
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
)

internal data class GpsTraceStatus(
    val sessionId: Long?,
    val startedAt: Long?,
    val lastSampleAt: Long?,
    val sampleCount: Int,
    val distanceMeters: Double,
    val recording: Boolean,
    val stale: Boolean,
)

internal object GpsTraceState {
    private const val PREFS = "courierpilot_gps_trace"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_LAST_SAMPLE_AT = "last_sample_at"
    private const val KEY_HEARTBEAT_AT = "heartbeat_at"
    private const val KEY_SAMPLE_COUNT = "sample_count"
    private const val KEY_DISTANCE_M = "distance_m"
    private const val KEY_RECORDING = "recording"
    private const val STALE_AFTER_MS = 35_000L

    fun status(context: Context, now: Long = System.currentTimeMillis()): GpsTraceStatus {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = p.getLong(KEY_SESSION_ID, -1L).takeIf { it > 0 }
        val startedAt = p.getLong(KEY_STARTED_AT, 0L).takeIf { it > 0 }
        val lastSampleAt = p.getLong(KEY_LAST_SAMPLE_AT, 0L).takeIf { it > 0 }
        val heartbeatAt = p.getLong(KEY_HEARTBEAT_AT, 0L).takeIf { it > 0 }
        val markedRecording = p.getBoolean(KEY_RECORDING, false)
        val stale = markedRecording && (heartbeatAt == null || now - heartbeatAt > STALE_AFTER_MS)
        return GpsTraceStatus(
            sessionId = sessionId,
            startedAt = startedAt,
            lastSampleAt = lastSampleAt,
            sampleCount = p.getInt(KEY_SAMPLE_COUNT, 0),
            distanceMeters = java.lang.Double.longBitsToDouble(p.getLong(KEY_DISTANCE_M, 0L)),
            recording = markedRecording && !stale,
            stale = stale,
        )
    }

    fun begin(context: Context, sessionId: Long, startedAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SESSION_ID, sessionId)
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_LAST_SAMPLE_AT, 0L)
            .putLong(KEY_HEARTBEAT_AT, startedAt)
            .putInt(KEY_SAMPLE_COUNT, 0)
            .putLong(KEY_DISTANCE_M, java.lang.Double.doubleToRawLongBits(0.0))
            .putBoolean(KEY_RECORDING, true)
            .apply()
    }

    fun heartbeat(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_HEARTBEAT_AT, at)
            .apply()
    }

    fun sample(context: Context, at: Long, count: Int, distanceMeters: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SAMPLE_AT, at)
            .putInt(KEY_SAMPLE_COUNT, count)
            .putLong(KEY_DISTANCE_M, java.lang.Double.doubleToRawLongBits(distanceMeters))
            .apply()
    }

    fun end(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECORDING, false)
            .putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis())
            .apply()
    }
}

internal object GpsTracePolicy {
    const val REQUEST_INTERVAL_MS = 2_000L
    const val REQUEST_MIN_DISTANCE_METERS = 2f
    const val MAX_ACCEPTED_ACCURACY_METERS = 80f
    private const val MAX_IMPLAUSIBLE_SPEED_MPS = 35.0

    fun accept(previous: GpsTracePoint?, candidate: GpsTracePoint): Boolean {
        if (candidate.latitude !in -90.0..90.0 || candidate.longitude !in -180.0..180.0) return false
        val accuracy = candidate.accuracyMeters
        if (accuracy != null && accuracy > MAX_ACCEPTED_ACCURACY_METERS) return false
        if (previous == null) return true
        val dtSeconds = (candidate.recordedAt - previous.recordedAt) / 1000.0
        if (dtSeconds <= 0.0) return false
        val meters = distanceMeters(previous.latitude, previous.longitude, candidate.latitude, candidate.longitude)
        return meters / dtSeconds <= MAX_IMPLAUSIBLE_SPEED_MPS
    }

    fun distanceMeters(a: GpsTracePoint, b: GpsTracePoint): Double =
        distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

internal object GpsTraceExport {
    fun geoJson(sessionId: Long, points: List<GpsTracePoint>): String {
        val coordinates = points.joinToString(",") { point ->
            "[${point.longitude},${point.latitude}]"
        }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"session_id":$sessionId,"sample_count":${points.size}},"geometry":{"type":"LineString","coordinates":[$coordinates]}}]}"""
    }
}

class GpsTraceService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: Long? = null
    private var lastAccepted: GpsTracePoint? = null
    private var sampleCount = 0
    private var distanceMeters = 0.0

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (sessionId == null) return
            val now = System.currentTimeMillis()
            GpsTraceState.heartbeat(this@GpsTraceService, now)
            updateNotification()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording("user_stop")
            ACTION_START, null -> startRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val id = sessionId ?: return
        val point = GpsTracePoint(
            recordedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
        )
        if (!GpsTracePolicy.accept(lastAccepted, point)) return

        lastAccepted?.let { distanceMeters += GpsTracePolicy.distanceMeters(it, point) }
        lastAccepted = point
        runCatching { RouteResearchDatabase.get(this).insertGpsSample(id, point) }
            .onFailure {
                CaptureEventLog.append(this, "gps_trace_store_failed", message = it.javaClass.simpleName, dedupeWindowMs = 30_000L)
                return
            }
        sampleCount++
        GpsTraceState.sample(this, point.recordedAt, sampleCount, distanceMeters)
    }

    override fun onProviderDisabled(provider: String) {
        updateNotification("Location provider disabled")
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        runCatching { locationManager.removeUpdates(this) }
        finishOpenSession("service_destroyed")
        super.onDestroy()
    }

    private fun startRecording() {
        if (sessionId != null) return
        if (!hasLocationPermission()) {
            GpsTraceState.end(this)
            stopSelf()
            return
        }
        createChannel()
        notifyForeground(buildNotification("Starting GPS…"))

        val db = RouteResearchDatabase.get(this)
        val now = System.currentTimeMillis()
        db.closeOpenGpsSessions(now)
        val id = db.startGpsSession(now, purpose = "manual_route_learning")
        sessionId = id
        sampleCount = 0
        distanceMeters = 0.0
        lastAccepted = null
        GpsTraceState.begin(this, id, now)
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)

        val provider = when {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
            runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            stopRecording("no_location_provider")
            return
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                GpsTracePolicy.REQUEST_INTERVAL_MS,
                GpsTracePolicy.REQUEST_MIN_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
            CaptureEventLog.append(this, "gps_trace_started", message = "Manual route trace started")
            updateNotification("Recording · waiting for first fix")
        } catch (failure: SecurityException) {
            stopRecording("permission_lost")
        }
    }

    private fun stopRecording(reason: String) {
        handler.removeCallbacks(heartbeatRunnable)
        runCatching { locationManager.removeUpdates(this) }
        if (sessionId == null) {
            runCatching { RouteResearchDatabase.get(this).closeOpenGpsSessions(System.currentTimeMillis()) }
            GpsTraceState.end(this)
        } else {
            finishOpenSession(reason)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishOpenSession(reason: String) {
        val id = sessionId ?: return
        sessionId = null
        runCatching { RouteResearchDatabase.get(this).endGpsSession(id, System.currentTimeMillis()) }
        GpsTraceState.end(this)
        CaptureEventLog.append(
            this,
            "gps_trace_stopped",
            message = "Manual route trace stopped · $reason · $sampleCount samples · ${"%.2f".format(Locale.US, distanceMeters / 1000.0)} km",
        )
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun notifyForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun updateNotification(overrideText: String? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(overrideText))
    }

    private fun buildNotification(overrideText: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1711,
            Intent(this, RouteTraceActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1712,
            Intent(this, GpsTraceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = overrideText ?: "Recording · $sampleCount points · ${"%.2f".format(Locale.US, distanceMeters / 1000.0)} km"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_courierpilot)
            .setContentTitle("CourierPilot route trace")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_courierpilot, "Stop trace", stop).build())
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Route trace", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible only while an explicitly started CourierPilot GPS route trace is recording."
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val ACTION_START = "com.block154.courierpilot.action.START_GPS_TRACE"
        const val ACTION_STOP = "com.block154.courierpilot.action.STOP_GPS_TRACE"
        private const val CHANNEL_ID = "courierpilot_route_trace"
        private const val NOTIFICATION_ID = 1710
        private const val HEARTBEAT_MS = 10_000L
    }
}
