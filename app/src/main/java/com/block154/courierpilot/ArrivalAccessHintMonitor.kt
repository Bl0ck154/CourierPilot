package com.block154.courierpilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure arrival policy kept separate so timing and distance rules can be regression-tested. */
internal object ArrivalAccessHintPolicy {
    // Geocoders commonly return one representative point for an entire parcel/building complex.
    // 300 m leaves enough headroom for large properties while still keeping the hint arrival-scoped.
    const val ARRIVAL_RADIUS_METERS = 300.0
    const val MAX_LOCATION_ACCURACY_METERS = 80f
    const val MAX_LOCATION_AGE_MS = 45_000L
    const val REMINDER_TTL_MS = 3L * 60L * 60L * 1000L

    fun shouldNotify(distanceMeters: Double, accuracyMeters: Float?, ageMillis: Long): Boolean =
        distanceMeters <= ARRIVAL_RADIUS_METERS &&
            ageMillis in 0..MAX_LOCATION_AGE_MS &&
            accuracyMeters != null &&
            accuracyMeters <= MAX_LOCATION_ACCURACY_METERS

    fun nextCheckDelayMs(distanceMeters: Double?): Long = when {
        distanceMeters == null -> 30_000L
        distanceMeters > 1_500.0 -> 60_000L
        distanceMeters > 500.0 -> 30_000L
        distanceMeters > ARRIVAL_RADIUS_METERS -> 15_000L
        else -> 8_000L
    }

    fun distanceMeters(from: RoutePoint, to: RoutePoint): Double {
        val radius = 6_371_000.0
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Background location is required because Wolt/Maps, not CourierPilot, is foreground on the trip. */
internal object ArrivalLocationPermission {
    private const val CHANNEL_ID = "courierpilot_arrival_setup"
    private const val NOTIFICATION_ID = 0x4D71

    fun hasBackgroundAccess(context: Context): Boolean =
        RouteResearchLocation.hasPermission(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun showSetup(context: Context) {
        val app = context.applicationContext
        if (hasBackgroundAccess(app)) {
            clearSetup(app)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Arrival reminder setup",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Setup needed for door-code reminders at the delivery address"
                setShowBadge(false)
            }
        )

        val settingsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = "Open Permissions → Location and choose Allow all the time. This lets CourierPilot show saved door codes when you reach the delivery address."
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_courierpilot)
            .setContentTitle("Enable arrival code reminders")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun clearSetup(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID)
    }
}

/**
 * Arms a historical door-code hint when a delivery address is recognized, but does not notify yet.
 * Active state is mirrored to SharedPreferences, so process death no longer silently drops the
 * reminder. Once the courier has explicitly confirmed a code at the entrance at least twice, the
 * learned entrance point is preferred over the geocoder's representative parcel point.
 */
internal object ArrivalAccessHintMonitor {
    private data class ArmedReminder(
        val generation: Long,
        val deliveryKey: String,
        val buildingKey: String,
        val suggestion: AccessCodeSuggestion,
        val armedAt: Long,
        val destination: RoutePoint? = null,
        val lastCheckStartedAt: Long = 0L,
        val lastDistanceMeters: Double? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var appContext: Context? = null
    private var active: ArmedReminder? = null
    private var generationCounter = 0L
    private var geocodeGeneration: Long? = null
    private var locationGeneration: Long? = null

    private val checkRunnable = Runnable {
        val app = synchronized(lock) { appContext }
        if (app != null) check(app)
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        val stored = PendingArrivalReminderStore.load(app) ?: return
        val database = CourierMetaDatabase.get(app)
        val liveCodes = database.codesForBuilding(stored.buildingKey, limit = 50).map { it.code }
        val stillValid = stored.suggestion.codes.filter { candidate ->
            liveCodes.any { storedCode -> equivalentCode(candidate, storedCode) }
        }
        if (stillValid.isEmpty()) {
            PendingArrivalReminderStore.clear(app)
            return
        }

        synchronized(lock) {
            if (active != null) return
            generationCounter++
            appContext = app
            active = ArmedReminder(
                generation = generationCounter,
                deliveryKey = stored.deliveryKey,
                buildingKey = stored.buildingKey,
                suggestion = stored.suggestion.copy(codes = stillValid),
                armedAt = stored.armedAt,
                destination = stored.destination,
            )
            geocodeGeneration = null
            locationGeneration = null
        }
        if (stored.destination == null) resolveDestination(app) else scheduleCheck(0L)
        CaptureEventLog.append(
            app,
            stage = "access_code_arrival_restored",
            platform = stored.suggestion.platform,
            message = "Restored pending arrival reminder after process restart",
            dedupeWindowMs = 30_000L,
        )
    }

    fun arm(
        context: Context,
        deliveryKey: String,
        buildingKey: String,
        suggestion: AccessCodeSuggestion,
    ) {
        if (deliveryKey.isBlank() || buildingKey.isBlank() || suggestion.codes.isEmpty()) return
        val app = context.applicationContext
        if (ArrivalLocationPermission.hasBackgroundAccess(app)) {
            ArrivalLocationPermission.clearSetup(app)
        } else {
            ArrivalLocationPermission.showSetup(app)
        }

        val now = System.currentTimeMillis()
        var shouldResolve = false
        var shouldPoke = false
        var snapshot: ArmedReminder? = null

        synchronized(lock) {
            appContext = app
            val current = active
            if (current != null && current.deliveryKey == deliveryKey) {
                val mergedCodes = (current.suggestion.codes + suggestion.codes)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                active = current.copy(
                    buildingKey = buildingKey,
                    suggestion = suggestion.copy(codes = mergedCodes, updatedAt = now),
                )
                snapshot = active
                shouldResolve = current.destination == null && geocodeGeneration != current.generation
                shouldPoke = current.destination != null && now - current.lastCheckStartedAt >= SCREEN_POKE_MIN_MS
            } else {
                generationCounter++
                active = ArmedReminder(
                    generation = generationCounter,
                    deliveryKey = deliveryKey,
                    buildingKey = buildingKey,
                    suggestion = suggestion,
                    armedAt = now,
                )
                snapshot = active
                geocodeGeneration = null
                locationGeneration = null
                shouldResolve = true
                shouldPoke = false
            }
        }
        snapshot?.let { persist(app, it) }

        if (shouldResolve) resolveDestination(app)
        if (shouldPoke) scheduleCheck(0L)
    }

    /** Cancel a stale armed reminder when the visible delivery address has changed. */
    fun cancelUnless(context: Context, visibleDeliveryKeys: Collection<String>) {
        val keys = visibleDeliveryKeys.filter(String::isNotBlank).toSet()
        val shouldCancel = synchronized(lock) {
            appContext = context.applicationContext
            val current = active ?: return@synchronized false
            keys.isNotEmpty() && current.deliveryKey !in keys
        }
        if (shouldCancel) cancelAll(context)
    }

    fun cancelAll(context: Context? = null) {
        val app = synchronized(lock) {
            if (context != null) appContext = context.applicationContext
            val currentApp = appContext
            active = null
            geocodeGeneration = null
            locationGeneration = null
            currentApp
        }
        handler.removeCallbacks(checkRunnable)
        app?.let(PendingArrivalReminderStore::clear)
    }

    private fun resolveDestination(app: Context) {
        val reminder = synchronized(lock) {
            val current = active ?: return
            if (current.destination != null || geocodeGeneration == current.generation) return
            current
        }

        val learned = LearnedEntranceStore.preferred(app, reminder.buildingKey)
        if (learned != null) {
            synchronized(lock) {
                val current = active
                if (current != null && current.generation == reminder.generation) {
                    active = current.copy(destination = learned)
                    persist(app, active!!)
                }
            }
            CaptureEventLog.append(
                app,
                stage = "access_code_arrival_learned_entrance",
                platform = reminder.suggestion.platform,
                message = "Using learned entrance from ${LearnedEntranceStore.sampleCount(app, reminder.buildingKey)} confirmations",
                dedupeWindowMs = 30_000L,
            )
            scheduleCheck(0L)
            return
        }

        synchronized(lock) {
            val current = active ?: return
            if (current.generation != reminder.generation || geocodeGeneration == current.generation) return
            geocodeGeneration = current.generation
        }

        RouteResearchGeocoder.resolve(app, reminder.suggestion.displayAddress) { result ->
            val point = result.getOrNull()
            var persisted: ArmedReminder? = null
            val stillActive = synchronized(lock) {
                val current = active
                if (current == null || current.generation != reminder.generation) return@synchronized false
                if (geocodeGeneration == reminder.generation) geocodeGeneration = null
                if (point != null) {
                    active = current.copy(destination = point)
                    persisted = active
                }
                true
            }
            persisted?.let { persist(app, it) }
            if (!stillActive) return@resolve

            if (point == null) {
                CaptureEventLog.append(
                    app,
                    stage = "access_code_arrival_geocode_failed",
                    platform = reminder.suggestion.platform,
                    message = result.exceptionOrNull()?.javaClass?.simpleName ?: "Address lookup failed",
                    dedupeWindowMs = 60_000L,
                )
                scheduleCheck(GEOCODE_RETRY_MS)
            } else {
                CaptureEventLog.append(
                    app,
                    stage = "access_code_arrival_armed",
                    platform = reminder.suggestion.platform,
                    message = "Historical access hint armed until courier reaches the saved address",
                    dedupeWindowMs = 30_000L,
                )
                scheduleCheck(0L)
            }
        }
    }

    private fun check(app: Context) {
        val now = System.currentTimeMillis()
        val reminder = synchronized(lock) {
            val current = active ?: return
            if (now - current.armedAt > ArrivalAccessHintPolicy.REMINDER_TTL_MS) {
                active = null
                geocodeGeneration = null
                locationGeneration = null
                PendingArrivalReminderStore.clear(app)
                return
            }
            if (current.destination == null) {
                current
            } else {
                if (locationGeneration == current.generation) return
                locationGeneration = current.generation
                active = current.copy(lastCheckStartedAt = now)
                current
            }
        }

        if (reminder.destination == null) {
            resolveDestination(app)
            return
        }

        requestArrivalFix(app) { result ->
            val fix = result.getOrNull()
            val current = synchronized(lock) {
                val live = active
                if (locationGeneration == reminder.generation) locationGeneration = null
                if (live == null || live.generation != reminder.generation) return@requestArrivalFix
                live
            }

            if (fix == null) {
                CaptureEventLog.append(
                    app,
                    stage = "access_code_arrival_location_failed",
                    platform = current.suggestion.platform,
                    message = result.exceptionOrNull()?.javaClass?.simpleName ?: "Location unavailable",
                    dedupeWindowMs = 60_000L,
                )
                scheduleCheck(ArrivalAccessHintPolicy.nextCheckDelayMs(current.lastDistanceMeters))
                return@requestArrivalFix
            }

            val destination = current.destination ?: return@requestArrivalFix
            val distance = ArrivalAccessHintPolicy.distanceMeters(fix.point, destination)
            synchronized(lock) {
                val live = active
                if (live != null && live.generation == reminder.generation) {
                    active = live.copy(lastDistanceMeters = distance)
                }
            }

            if (ArrivalAccessHintPolicy.shouldNotify(distance, fix.accuracyMeters, fix.ageMillis)) {
                notifyAtArrival(app, current, distance)
            } else {
                scheduleCheck(ArrivalAccessHintPolicy.nextCheckDelayMs(distance))
            }
        }
    }

    private fun requestArrivalFix(context: Context, callback: (Result<CurrentLocationFix>) -> Unit) {
        if (!ArrivalLocationPermission.hasBackgroundAccess(context)) {
            ArrivalLocationPermission.showSetup(context)
            callback(Result.failure(SecurityException("Background location is required for arrival-timed access hints")))
            return
        }
        ArrivalLocationPermission.clearSetup(context)

        val client = runCatching { LocationServices.getFusedLocationProviderClient(context) }
            .getOrElse {
                RouteResearchLocation.requestForLiveOffer(context, callback)
                return
            }

        runCatching {
            client.lastLocation
                .addOnSuccessListener { location ->
                    val cached = location?.toCurrentFix("fused-cache")
                    if (cached != null &&
                        cached.ageMillis <= ArrivalAccessHintPolicy.MAX_LOCATION_AGE_MS &&
                        cached.accuracyMeters != null &&
                        cached.accuracyMeters <= ArrivalAccessHintPolicy.MAX_LOCATION_ACCURACY_METERS
                    ) {
                        callback(Result.success(cached))
                    } else {
                        RouteResearchLocation.requestForLiveOffer(context, callback)
                    }
                }
                .addOnFailureListener {
                    RouteResearchLocation.requestForLiveOffer(context, callback)
                }
        }.onFailure {
            RouteResearchLocation.requestForLiveOffer(context, callback)
        }
    }

    private fun notifyAtArrival(app: Context, reminder: ArmedReminder, distanceMeters: Double) {
        val database = CourierMetaDatabase.get(app)
        val liveCodes = database.codesForBuilding(reminder.buildingKey, limit = 50)
            .filter { AccessHintFeedbackStore.shouldSurface(app, it) }
            .map { it.code }
        val stillValidCodes = reminder.suggestion.codes.filter { candidate ->
            liveCodes.any { stored -> equivalentCode(candidate, stored) }
        }
        if (stillValidCodes.isEmpty()) {
            cancelAll(app)
            AccessCodeSuggestions.clear(app)
            return
        }

        val suggestion = reminder.suggestion.copy(
            codes = stillValidCodes,
            updatedAt = System.currentTimeMillis(),
        )
        val claimed = AccessCodeNotificationGate.claim(app, reminder.deliveryKey)
        cancelAll(app)
        if (!claimed) return

        AccessCodeSuggestions.save(app, suggestion)
        AccessCodeNotifier.show(app, suggestion, reminder.buildingKey)
        Toast.makeText(
            app,
            "Possible door code · ${suggestion.displayAddress}: ${suggestion.codes.joinToString(" / ")}",
            Toast.LENGTH_LONG,
        ).show()
        CaptureEventLog.append(
            app,
            stage = "access_code_arrival_match",
            platform = suggestion.platform,
            message = "Historical access hint shown at ${String.format(Locale.US, "%.0f", distanceMeters)} m from destination",
            dedupeWindowMs = 30_000L,
        )
    }

    private fun persist(app: Context, reminder: ArmedReminder) {
        PendingArrivalReminderStore.save(
            app,
            PendingArrivalReminder(
                deliveryKey = reminder.deliveryKey,
                buildingKey = reminder.buildingKey,
                suggestion = reminder.suggestion,
                armedAt = reminder.armedAt,
                destination = reminder.destination,
            )
        )
    }

    private fun scheduleCheck(delayMs: Long) {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun Location.toCurrentFix(providerOverride: String): CurrentLocationFix = CurrentLocationFix(
        point = RoutePoint(latitude, longitude),
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        ageMillis = (System.currentTimeMillis() - time).coerceAtLeast(0L),
        provider = providerOverride,
    )

    private fun equivalentCode(first: String, second: String): Boolean =
        canonicalCode(first).isNotBlank() && canonicalCode(first) == canonicalCode(second)

    private fun canonicalCode(value: String): String = value
        .trim()
        .uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")

    private const val SCREEN_POKE_MIN_MS = 15_000L
    private const val GEOCODE_RETRY_MS = 60_000L
}
