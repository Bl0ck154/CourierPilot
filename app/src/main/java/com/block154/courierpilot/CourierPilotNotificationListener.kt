package com.block154.courierpilot

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CourierPilotNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var unlockReceiverRegistered = false
    private var lastOfferContentIntent: PendingIntent? = null
    private var lastOfferPackage = ""
    private var lastOfferNotificationKey = ""

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) retryPendingOfferAfterUnlock()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerUnlockReceiver()
        CaptureEventLog.append(this, "listener", "Notification listener connected", dedupeWindowMs = 30_000L)
        reconcileAllCourierPresence()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!CourierSignals.isCourierPackage(sbn.packageName)) return
        val platform = OfferState.platformLabel(sbn.packageName)
        val notification = sbn.notification
        val decision = NotificationOfferClassifier.classify(this, sbn)

        // Keep likely/unknown transient courier pushes briefly. If Accessibility subsequently proves
        // that an offer screen is visible, this candidate becomes a learned structural profile.
        if (decision.isOffer || decision.score >= CANDIDATE_MEMORY_SCORE) {
            NotificationOfferProfileStore.rememberCandidate(this, sbn)
        }

        if (decision.isOffer) {
            CourierPresence.markOfferOnline(this, sbn.packageName, "offer notification")
            CaptureEventLog.append(
                this,
                "notification",
                "Offer notification matched structurally (score=${decision.score}; ${decision.reasons.joinToString(",")})",
                platform,
            )
            val sourceName = resolveAppName(sbn.packageName)
            lastOfferContentIntent = notification.contentIntent
            lastOfferPackage = sbn.packageName
            lastOfferNotificationKey = sbn.key
            val armResult = OfferState.arm(this, sbn.packageName, sourceName, sbn.key)
            CaptureEventLog.append(
                this,
                stage = "armed",
                platform = platform,
                message = when (armResult) {
                    ArmResult.ARMED -> "New capture armed"
                    ArmResult.DUPLICATE_UPDATE -> "Duplicate/already captured notification update ignored"
                    ArmResult.REPLACED_SAME_PLATFORM -> "New notification replaced pending offer from same platform"
                    ArmResult.QUEUED_OTHER_PLATFORM -> "Offer queued behind active capture from other platform"
                },
            )

            val shouldAct = armResult != ArmResult.DUPLICATE_UPDATE && armResult != ArmResult.QUEUED_OTHER_PLATFORM
            if (shouldAct && OfferState.wakeScreen(this)) wakeScreenBriefly(platform)
            if (shouldAct && OfferState.autoOpen(this)) {
                openOriginalNotification(
                    contentIntent = notification.contentIntent,
                    packageName = sbn.packageName,
                    notificationKey = sbn.key,
                    sourceName = sourceName,
                    platform = platform,
                    reason = "notification",
                )
            }
            return
        }

        val notificationText = CourierSignals.notificationText(notification)
        when (val signal = CourierSignals.detectPresence(notificationText)) {
            PresenceSignal.ONLINE, PresenceSignal.OFFLINE ->
                CourierPresence.markExplicitNotification(this, sbn.packageName, signal)
            PresenceSignal.UNKNOWN -> if (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) {
                CourierPresence.markNotificationUnknown(this, sbn.packageName)
            }
        }

        CaptureEventLog.append(
            this,
            stage = "notification_ignored",
            platform = platform,
            message = buildString {
                append("Courier notification not classified as offer; score=${decision.score}")
                if (decision.reasons.isNotEmpty()) append("; evidence=${decision.reasons.joinToString(",")}")
                val labels = CourierSignals.notificationActionLabels(notification)
                if (labels.isNotEmpty()) append("; actions=${labels.joinToString("/").take(120)}")
                val preview = notificationText.replace(Regex("\\s+"), " ").take(180)
                if (preview.isNotBlank()) append("; text=$preview")
            },
            dedupeWindowMs = 5_000L,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!CourierSignals.isCourierPackage(sbn.packageName)) return
        if (NotificationOfferClassifier.classify(this, sbn).isOffer) {
            OfferState.releaseCapturedNotification(this, sbn.packageName, sbn.key)
            return
        }
        if (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0) return

        val packageName = sbn.packageName
        handler.postDelayed({ reconcilePackagePresence(packageName) }, PRESENCE_REMOVAL_GRACE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(unlockReceiver, filter)
        }
        unlockReceiverRegistered = true
    }

    private fun retryPendingOfferAfterUnlock() {
        if (!OfferState.autoOpen(this)) return
        val pending = OfferState.pending(this) ?: return
        if (pending.notificationKey.isBlank() || pending.notificationKey.startsWith("screen:")) return

        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        val sbn = active.firstOrNull {
            it.packageName == pending.packageName && it.key == pending.notificationKey
        }
        val rememberedIntent = lastOfferContentIntent.takeIf {
            lastOfferPackage == pending.packageName && lastOfferNotificationKey == pending.notificationKey
        }
        val contentIntent = sbn?.notification?.contentIntent ?: rememberedIntent ?: return

        val platform = OfferState.platformLabel(pending.packageName)
        CaptureEventLog.append(
            this,
            "unlock_retry",
            if (sbn != null) "Device unlocked; retrying the exact active offer PendingIntent"
            else "Device unlocked; retrying the remembered exact offer PendingIntent",
            platform,
        )
        openOriginalNotification(
            contentIntent = contentIntent,
            packageName = pending.packageName,
            notificationKey = pending.notificationKey,
            sourceName = pending.sourceName,
            platform = platform,
            reason = "unlock",
        )
    }

    private fun reconcileAllCourierPresence() {
        CourierSignals.courierPackages.forEach(::reconcilePackagePresence)
    }

    private fun reconcilePackagePresence(packageName: String) {
        val active = runCatching { activeNotifications?.filter { it.packageName == packageName }.orEmpty() }
            .getOrDefault(emptyList())
        val nonOffer = active.filterNot { NotificationOfferClassifier.classify(this, it).isOffer }

        val explicit = nonOffer.asSequence()
            .map { CourierSignals.detectPresence(CourierSignals.notificationText(it.notification)) }
            .firstOrNull { it != PresenceSignal.UNKNOWN }
        if (explicit != null) {
            CourierPresence.markExplicitNotification(this, packageName, explicit)
            return
        }

        CourierPresence.markNotificationUnknown(this, packageName)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly(platform: String) {
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (power.isInteractive) return
            val lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:offerWake",
            )
            lock.acquire(3_000L)
            CaptureEventLog.append(this, "wake", "Requested a brief screen wake", platform)
        } catch (t: Throwable) {
            CaptureEventLog.append(this, "wake_failed", t.javaClass.simpleName, platform)
        }
    }

    private fun openOriginalNotification(
        contentIntent: PendingIntent?,
        packageName: String,
        notificationKey: String,
        sourceName: String,
        platform: String,
        reason: String,
    ) {
        val requestedAt = OfferOpenState.begin(this, packageName, notificationKey)
        if (contentIntent == null) {
            CaptureEventLog.append(this, "open_no_content_intent", "Offer notification has no content intent; using launcher fallback", platform)
            requestLauncherFallback(packageName, notificationKey, requestedAt, sourceName, platform)
            return
        }

        if (!sendPendingIntent(contentIntent, platform, if (reason == "unlock") "open_unlock_requested" else "open_requested")) {
            requestLauncherFallback(packageName, notificationKey, requestedAt, sourceName, platform)
            return
        }

        handler.postDelayed({
            verifyInitialOpen(
                contentIntent,
                packageName,
                notificationKey,
                requestedAt,
                sourceName,
                platform,
            )
        }, OPEN_VERIFY_DELAY_MS)
    }

    private fun verifyInitialOpen(
        contentIntent: PendingIntent,
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
        sourceName: String,
        platform: String,
    ) {
        if (!OfferOpenState.isCurrent(this, packageName, notificationKey, requestedAt)) return
        if (OfferOpenState.wasOfferVisible(this, packageName, notificationKey, requestedAt)) {
            CaptureEventLog.append(this, "open_verified_offer", "Offer screen verified after notification open", platform)
            return
        }

        if (OfferOpenState.wasWindowVisible(this, packageName, notificationKey, requestedAt)) {
            // The courier activity is alive, so give React Native/Compose + OCR a little more time to
            // render the actual offer before firing the PendingIntent a second time.
            CaptureEventLog.append(this, "open_window_only", "Courier window is visible but offer screen is not verified yet", platform)
            handler.postDelayed({
                if (!OfferOpenState.isCurrent(this, packageName, notificationKey, requestedAt)) return@postDelayed
                if (OfferOpenState.wasOfferVisible(this, packageName, notificationKey, requestedAt)) {
                    CaptureEventLog.append(this, "open_verified_offer", "Offer screen verified after render settle", platform)
                } else {
                    retryOriginalPendingIntent(contentIntent, packageName, notificationKey, requestedAt, sourceName, platform)
                }
            }, OPEN_WINDOW_SETTLE_MS)
            return
        }

        retryOriginalPendingIntent(contentIntent, packageName, notificationKey, requestedAt, sourceName, platform)
    }

    private fun retryOriginalPendingIntent(
        contentIntent: PendingIntent,
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
        sourceName: String,
        platform: String,
    ) {
        if (!OfferOpenState.isCurrent(this, packageName, notificationKey, requestedAt)) return
        CaptureEventLog.append(
            this,
            "open_unverified",
            "Offer screen not verified; retrying the exact notification PendingIntent once",
            platform,
        )
        if (!sendPendingIntent(contentIntent, platform, "open_retry_requested")) {
            requestLauncherFallback(packageName, notificationKey, requestedAt, sourceName, platform)
            return
        }

        handler.postDelayed({
            if (!OfferOpenState.isCurrent(this, packageName, notificationKey, requestedAt)) return@postDelayed
            when {
                OfferOpenState.wasOfferVisible(this, packageName, notificationKey, requestedAt) ->
                    CaptureEventLog.append(this, "open_retry_verified_offer", "Offer screen verified after PendingIntent retry", platform)

                OfferOpenState.wasWindowVisible(this, packageName, notificationKey, requestedAt) -> {
                    val message = "$sourceName opened, but the offer screen was not verified"
                    OfferState.markError(this, message)
                    CaptureEventLog.append(this, "open_wrong_screen", message, platform)
                    // Do not launch the generic home activity here: it would only make a wrong-screen
                    // result more certain. Accessibility/OCR keeps watching the pending offer.
                }

                else -> requestLauncherFallback(packageName, notificationKey, requestedAt, sourceName, platform)
            }
        }, OPEN_RETRY_VERIFY_DELAY_MS)
    }

    private fun sendPendingIntent(contentIntent: PendingIntent, platform: String, stage: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(backgroundActivityStartMode())
                }
                contentIntent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                contentIntent.send()
            }
            CaptureEventLog.append(this, stage, "Sent exact offer notification PendingIntent", platform)
            true
        } catch (_: PendingIntent.CanceledException) {
            CaptureEventLog.append(this, "open_failed", "Offer notification PendingIntent was cancelled", platform)
            false
        } catch (t: Throwable) {
            CaptureEventLog.append(this, "open_failed", t.javaClass.simpleName, platform)
            false
        }
    }

    private fun backgroundActivityStartMode(): Int {
        if (Build.VERSION.SDK_INT >= 36) {
            val allowAlways = runCatching {
                ActivityOptions::class.java
                    .getField("MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS")
                    .getInt(null)
            }.getOrNull()
            if (allowAlways != null) return allowAlways
        }
        return ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }

    private fun requestLauncherFallback(
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
        sourceName: String,
        platform: String,
    ) {
        if (!OfferOpenState.isCurrent(this, packageName, notificationKey, requestedAt)) return
        if (OfferOpenState.wasOfferVisible(this, packageName, notificationKey, requestedAt)) {
            CaptureEventLog.append(this, "open_verified_offer", "Offer screen became visible before launcher fallback", platform)
            return
        }

        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: error("No launcher activity")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }.onSuccess {
            CaptureEventLog.append(this, "open_launcher_fallback", "Exact PendingIntent never produced a courier window; requested launcher as last fallback", platform)
            handler.postDelayed({
                if (!OfferOpenState.isCurrent(this, packageName, notificationKey, requestedAt)) return@postDelayed
                when {
                    OfferOpenState.wasOfferVisible(this, packageName, notificationKey, requestedAt) ->
                        CaptureEventLog.append(this, "open_launcher_verified_offer", "Offer screen verified after launcher fallback", platform)
                    OfferOpenState.wasWindowVisible(this, packageName, notificationKey, requestedAt) -> {
                        val message = "$sourceName opened via launcher, but offer screen was not verified"
                        OfferState.markError(this, message)
                        CaptureEventLog.append(this, "open_launcher_window_only", message, platform)
                    }
                    else -> {
                        val message = "Could not verify that $sourceName opened from its offer notification"
                        OfferState.markError(this, message)
                        CaptureEventLog.append(this, "open_verify_failed", message, platform)
                    }
                }
            }, LAUNCHER_VERIFY_DELAY_MS)
        }.onFailure {
            val message = "Could not open $sourceName: ${it.javaClass.simpleName}"
            OfferState.markError(this, message)
            CaptureEventLog.append(this, "open_launcher_failed", it.javaClass.simpleName, platform)
        }
    }

    private fun resolveAppName(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Throwable) {
        pkg
    }

    companion object {
        private const val PRESENCE_REMOVAL_GRACE_MS = 20_000L
        private const val CANDIDATE_MEMORY_SCORE = 3
        private const val OPEN_VERIFY_DELAY_MS = 1_300L
        private const val OPEN_WINDOW_SETTLE_MS = 1_000L
        private const val OPEN_RETRY_VERIFY_DELAY_MS = 1_400L
        private const val LAUNCHER_VERIFY_DELAY_MS = 1_400L
    }
}
