package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Stable live card: the shell appears first with profitability data, then routing updates the same
 * card in place. Route work never owns card lifetime and a route callback cannot create a new card.
 */
internal class StableLiveOfferAdvisor(
    private val service: AccessibilityService,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val surfaceInspector = LiveAdvisorSurfaceInspector(service)
    private val speech = LiveAdvisorSpeech(service.applicationContext)
    private val decisionThresholds = LiveAdvisorDecisionThresholds.production(service.applicationContext, handler)
    private val overlayView by lazy {
        LiveAdvisorOverlayView(
            service = service,
            onDismiss = ::dismissCurrentOfferByUser,
            onGestureFinished = ::flushDeferredCourierWindowCheck,
            platformProvider = { currentPlatform },
        )
    }

    private var currentPlatform = ""
    private var currentParsed: ParsedOffer? = null
    private var expectedPackageName = ""
    private var currentNotificationKey = ""
    private var currentNotificationRemoved = false
    private var dismissed = false
    private var temporarilyHidden = false
    private var temporaryRestoreDeadlineElapsed = Long.MAX_VALUE
    private var generation = 0L
    private var missingSince = 0L
    private var missingChecks = 0
    private var boltBaselineSurface: LiveOfferSurfaceSnapshot? = null
    private var woltBaselineSurface: LiveOfferSurfaceSnapshot? = null
    private var previewMode = false
    private var offerVisualStartedAtElapsed = 0L

    private var cachedDecisionLine = ""
    private var cachedDecisionBand = OfferDecisionBand.UNKNOWN
    private var cachedDecisionLoading = true
    private var finalPresentationLocked = false
    private var cachedRouteLine = ""
    private var cachedRouteVisible = true
    private var cachedPedestrianRoute: RouteResult? = null
    private var cachedCyclewayRoute: RouteResult? = null
    private val differentOfferConfirmation = OfferDifferenceConfirmation()
    private val woltHomeEndConfirmation = WoltHomeEndConfirmation()

    private var courierEventCheckScheduled = false
    private var courierEventCheckDeferred = false
    private var latestObservedPackage = ""
    private var latestObservedText = ""
    private var latestObservedAtElapsed = 0L

    private val courierWindowCheck = Runnable {
        courierEventCheckScheduled = false
        if (overlayView.isGestureTouchActive) {
            courierEventCheckDeferred = true
            return@Runnable
        }
        if (!dismissed && currentParsed != null) checkOfferStillVisible()
    }

    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (dismissed || currentParsed == null) return
            // Accessibility tree inspection is relatively expensive on Compose-heavy Wolt screens.
            // Never compete with touch delivery while the courier is physically dragging the card.
            if (!overlayView.isGestureTouchActive) checkOfferStillVisible()
            if (!dismissed && currentParsed != null) {
                handler.postDelayed(this, if (temporarilyHidden) HIDDEN_VISIBILITY_CHECK_MS else VISIBILITY_CHECK_MS)
            }
        }
    }

    /**
     * Show the advisor as soon as CourierPilot has a verified offer surface, even while the courier
     * app is still loading the price. Later price/persistence/route updates mutate this same view.
     */
    fun showPending(platform: String, parsed: ParsedOffer, notificationKey: String = "") {
        if (!LiveAdvisorSettings.enabled(service)) return
        val packageName = packageForPlatform(platform)
        val compatibleOfferEvidence = currentParsed?.let { expected ->
            !LiveOfferResumePolicy.definitelyDifferent(expected, parsed) &&
                (LiveOfferResumePolicy.hasCompatibleCoreIdentity(expected, parsed) ||
                    LiveOfferResumePolicy.hasMatchingIdentity(expected, parsed))
        } == true
        val sameSurface = LiveOfferTransactionPolicy.isSameSurface(
            dismissed = dismissed,
            hasCurrentOffer = currentParsed != null,
            expectedPackageName = expectedPackageName,
            currentNotificationKey = currentNotificationKey,
            incomingPackageName = packageName,
            incomingNotificationKey = notificationKey,
            compatibleOfferEvidence = compatibleOfferEvidence,
        )
        val createdSurface = !sameSurface
        if (!sameSurface) {
            generation += 1
            offerVisualStartedAtElapsed = SystemClock.elapsedRealtime()
            currentPlatform = platform
            expectedPackageName = packageName
            currentNotificationKey = notificationKey
            currentNotificationRemoved = notificationIsAlreadyRemoved(packageName, notificationKey)
            dismissed = false
            temporarilyHidden = false
            temporaryRestoreDeadlineElapsed = Long.MAX_VALUE
            cachedDecisionLine = ""
            cachedDecisionBand = OfferDecisionBand.UNKNOWN
            cachedDecisionLoading = true
            finalPresentationLocked = false
            decisionThresholds.beginOffer(platform, generation)
            cachedRouteLine = ""
            cachedRouteVisible = true
            cachedPedestrianRoute = null
            cachedCyclewayRoute = null
            resetMissingEvidence()
            woltHomeEndConfirmation.reset()
            val initialSurface = findVisiblePackageRoot(packageName)?.let { inspectVisibleSurface(it).snapshot }
            boltBaselineSurface = if (platform.equals("Bolt", ignoreCase = true)) initialSurface else null
            woltBaselineSurface = if (platform.equals("Wolt", ignoreCase = true)) initialSurface else null
        }
        previewMode = true
        if (notificationKey.isNotBlank()) {
            currentNotificationKey = notificationKey
            currentNotificationRemoved = notificationIsAlreadyRemoved(packageName, notificationKey)
        }
        currentParsed = parsed
        decisionThresholds.prewarm()
        differentOfferConfirmation.reset()
        if (!finalPresentationLocked) renderProgressiveDecision(parsed)
        if (cachedRouteLine.isBlank()) renderRouteLoadingState()
        if (!temporarilyHidden) {
            ensureView()
            applyCachedPresentation()
            if (createdSurface && overlayView.isAttached) {
                CaptureEventLog.append(
                    service,
                    stage = "overlay_preview",
                    platform = platform,
                    message = "Progressive advisor shown before final price persistence",
                )
            }
        }
        startVisibilityWatchdog()
    }

    fun showBase(platform: String, parsed: ParsedOffer, notificationKey: String = "") {
        if (!LiveAdvisorSettings.enabled(service)) {
            suppressCurrentOffer("advisor disabled")
            return
        }

        val packageName = packageForPlatform(platform)
        val compatibleOfferEvidence = currentParsed?.let { expected ->
            !LiveOfferResumePolicy.definitelyDifferent(expected, parsed) &&
                (LiveOfferResumePolicy.hasCompatibleCoreIdentity(expected, parsed) ||
                    LiveOfferResumePolicy.hasMatchingIdentity(expected, parsed))
        } == true
        val samePreviewSurface = previewMode && LiveOfferTransactionPolicy.isSameSurface(
            dismissed = dismissed,
            hasCurrentOffer = currentParsed != null,
            expectedPackageName = expectedPackageName,
            currentNotificationKey = currentNotificationKey,
            incomingPackageName = packageName,
            incomingNotificationKey = notificationKey,
            compatibleOfferEvidence = compatibleOfferEvidence,
        )
        if (samePreviewSurface) {
            val previousPrice = currentParsed?.priceCents
            currentPlatform = platform
            currentParsed = parsed
            if (notificationKey.isNotBlank()) {
                currentNotificationKey = notificationKey
                currentNotificationRemoved = notificationIsAlreadyRemoved(packageName, notificationKey)
            }
            previewMode = false
            decisionThresholds.prewarm()
            differentOfferConfirmation.reset()
            if (!finalPresentationLocked && (cachedDecisionLoading || previousPrice != parsed.priceCents)) {
                renderProgressiveDecision(parsed)
            }
            if (cachedRouteLine.isBlank()) renderRouteLoadingState()
            if (!temporarilyHidden) {
                ensureView()
                applyCachedPresentation()
            }
            CaptureEventLog.append(
                service,
                stage = "overlay_promote",
                platform = platform,
                message = "Pending advisor promoted in place after price capture",
                dedupeWindowMs = 1_000L,
            )
            if (LiveAdvisorSettings.voiceEnabled(service)) speech.announceOffer(platform, parsed)
            startVisibilityWatchdog()
            return
        }

        generation += 1
        offerVisualStartedAtElapsed = SystemClock.elapsedRealtime()
        val expectedGeneration = generation
        currentPlatform = platform
        currentParsed = parsed
        expectedPackageName = packageForPlatform(platform)
        currentNotificationKey = notificationKey
        currentNotificationRemoved = notificationIsAlreadyRemoved(expectedPackageName, notificationKey)
        dismissed = false
        temporarilyHidden = false
        temporaryRestoreDeadlineElapsed = Long.MAX_VALUE
        previewMode = false
        cachedDecisionLine = ""
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = true
        finalPresentationLocked = false
        decisionThresholds.beginOffer(platform, generation)
        cachedRouteLine = ""
        cachedRouteVisible = true
        cachedPedestrianRoute = null
        cachedCyclewayRoute = null
        resetMissingEvidence()
        woltHomeEndConfirmation.reset()
        val initialSurface = findVisiblePackageRoot(expectedPackageName)?.let { inspectVisibleSurface(it).snapshot }
        boltBaselineSurface = if (platform.equals("Bolt", ignoreCase = true)) initialSurface else null
        woltBaselineSurface = if (platform.equals("Wolt", ignoreCase = true)) initialSurface else null
        decisionThresholds.prewarm()

        handler.post {
            if (dismissed || expectedGeneration != generation) return@post
            // Cache presentation first. If the courier app was already backgrounded, keep the
            // offer warm without recreating an overlay on top of another app.
            if (!finalPresentationLocked) renderProgressiveDecision(parsed)
            if (cachedRouteLine.isBlank()) renderRouteLoadingState()
            if (!temporarilyHidden) {
                ensureView()
                if (overlayView.isAttached) {
                    applyCachedPresentation()
                    CaptureEventLog.append(
                        service,
                        stage = "overlay_show",
                        platform = platform,
                        message = "Stable advisor shell shown before route result",
                    )
                    if (LiveAdvisorSettings.voiceEnabled(service)) speech.announceOffer(platform, parsed)
                }
            }
            startVisibilityWatchdog()
        }
    }

    fun isTrackingOffer(packageName: String): Boolean =
        !dismissed && currentParsed != null && expectedPackageName == packageName

    /**
     * Coalesce notification churn only when the courier screen itself still shows this advisor's
     * offer. Wolt can repost one ringing offer under new notification keys; those keys are lifetime
     * anchors, not reliable transaction identities.
     */
    fun isSameOfferVisiblyPresent(packageName: String): Boolean {
        if (!isTrackingOffer(packageName)) return false
        val expected = currentParsed ?: return false
        val rootNode = findVisiblePackageRoot(packageName) ?: return false
        val visibleText = inspectVisibleSurface(rootNode).text
        if (visibleText.isBlank()) return false
        val visible = OfferParser.parse(visibleText)
        val hasOfferUi = CourierSignals.looksLikeOfferScreen(visibleText, visible) || hasDecisionPair(visibleText)
        if (!hasOfferUi || LiveOfferResumePolicy.definitelyDifferent(expected, visible)) return false
        return LiveOfferResumePolicy.hasCompatibleCoreIdentity(expected, visible) ||
            LiveOfferResumePolicy.hasMatchingIdentity(expected, visible)
    }

    /** Move only the notification lifetime anchor; never recreate or repaint the live card. */
    fun retargetNotificationAnchor(packageName: String, notificationKey: String) {
        if (!isTrackingOffer(packageName) || notificationKey.isBlank()) return
        currentNotificationKey = notificationKey
        currentNotificationRemoved = notificationIsAlreadyRemoved(packageName, notificationKey)
        CaptureEventLog.append(
            service,
            stage = "overlay_notification_anchor_retargeted",
            platform = currentPlatform,
            message = "Same visible offer adopted refreshed notification lifetime anchor",
            dedupeWindowMs = 1_000L,
        )
    }

    /**
     * Courier UIs can briefly expose contradictory merchant/address snapshots while the same offer
     * recomposes. A single such frame must never destroy/re-arm the live card. Notifications still
     * arm truly new offers immediately; screen-only replacement needs stable conflict evidence.
     */
    fun isConfirmedDifferentOffer(packageName: String, parsed: ParsedOffer): Boolean {
        if (!isTrackingOffer(packageName)) return true
        val expected = currentParsed ?: return true
        return differentOfferConfirmation.observe(
            different = LiveOfferResumePolicy.definitelyDifferent(expected, parsed),
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    fun updateRoute(comparison: RouteComparison, waypointCount: Int) {
        if (dismissed || finalPresentationLocked || !LiveAdvisorSettings.enabled(service)) return
        val expectedGeneration = generation
        handler.post {
            if (dismissed || finalPresentationLocked || generation != expectedGeneration) return@post
            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            val samePreparedRoute = sameRoute(cachedPedestrianRoute, walking) &&
                sameRoute(cachedCyclewayRoute, cycling) &&
                cachedRouteLine.isNotBlank()
            cachedPedestrianRoute = walking
            cachedCyclewayRoute = cycling
            if (samePreparedRoute) {
                // Persistence reattaches the exact route that was already prepared before price.
                // Do not re-score/repaint the same live offer a second time.
                if (cachedDecisionLoading) currentParsed?.let(::renderProgressiveDecision)
                return@post
            }
            currentParsed?.let(::renderProgressiveDecision)
            setRouteContent(LiveAdvisorPresentation.routeLine(walking, cycling))
            CaptureEventLog.append(
                service,
                stage = "route_ready",
                platform = currentPlatform,
                message = "Route updated cached card; points=$waypointCount; visible=${overlayView.isAttached}; card_age_ms=${(SystemClock.elapsedRealtime() - offerVisualStartedAtElapsed).coerceAtLeast(0L)}",
            )
        }
    }

    fun updateWoltRoute(
        comparison: RouteComparison,
        waypointCount: Int,
        scope: WoltRouteScopeKind,
    ) {
        val parsed = currentParsed
        if (parsed == null || WoltIncrementalRoutePolicy.canScoreResolvedRoute(parsed, scope)) {
            updateRoute(comparison, waypointCount)
            return
        }
        if (dismissed || finalPresentationLocked || !LiveAdvisorSettings.enabled(service)) return
        val expectedGeneration = generation
        handler.post {
            if (dismissed || finalPresentationLocked || generation != expectedGeneration) return@post
            val current = currentParsed ?: return@post
            if (WoltIncrementalRoutePolicy.canScoreResolvedRoute(current, scope)) {
                updateRoute(comparison, waypointCount)
                return@post
            }
            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            // The full remaining chain is useful route context for an ambiguous add-on, but its
            // distance is not the denominator for incremental money. Keep the left route row while
            // the primary €/km stays on Wolt's explicit +distance fallback.
            cachedPedestrianRoute = null
            cachedCyclewayRoute = null
            renderProgressiveDecision(current)
            setRouteContent(LiveAdvisorPresentation.routeLine(walking, cycling))
            CaptureEventLog.append(
                service,
                stage = "route_ready_context_only",
                platform = currentPlatform,
                message = "Incremental offer kept full remaining route as context only; " +
                    "scope=$scope; points=$waypointCount",
                dedupeWindowMs = 500L,
            )
        }
    }

    fun updateBoltRoute(outcome: AutomaticBoltRouteOutcome) {
        if (dismissed || finalPresentationLocked || !LiveAdvisorSettings.enabled(service)) return
        val expectedGeneration = generation
        handler.post {
            if (dismissed || finalPresentationLocked || generation != expectedGeneration) return@post
            val comparison = outcome.comparison
            if (comparison == null) {
                setDecisionUnavailable()
                setRouteContent("⚠️ Route unavailable")
                CaptureEventLog.append(
                    service,
                    stage = "bolt_route_failed",
                    platform = "Bolt",
                    message = outcome.failureReason ?: "unknown route failure",
                )
                return@post
            }

            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            if (outcome.scope == BoltRouteScope.FULL) {
                cachedPedestrianRoute = walking
                cachedCyclewayRoute = cycling
                currentParsed?.let { parsed -> renderProfitability(parsed, walking, cycling) }
            } else {
                // A pickup-only route is useful context, but it is not the full paid delivery.
                // Never turn that partial distance into a misleading €/km verdict.
                cachedPedestrianRoute = null
                cachedCyclewayRoute = null
                currentParsed?.let(::renderProgressiveDecision)
            }
            setRouteContent(LiveAdvisorPresentation.routeLine(walking, cycling))
            CaptureEventLog.append(
                service,
                stage = "bolt_route_ready",
                platform = "Bolt",
                message = "Updated existing card; scope=${outcome.scope}; waypoints=${outcome.waypoints.size}",
            )
        }
    }

    /**
     * Last-resort history recovery when the detailed research-route snapshot is unavailable but the
     * offer row already contains a trusted full-route distance. Profitability is restored instantly
     * without pretending that we know separate walking/cycling legs.
     */
    fun updateHistoricalRouteDistance(routeMeters: Int) {
        if (dismissed || finalPresentationLocked || routeMeters <= 0 || !LiveAdvisorSettings.enabled(service)) return
        val expectedGeneration = generation
        handler.post {
            if (dismissed || finalPresentationLocked || generation != expectedGeneration) return@post
            val route = RouteResult(
                provider = "history-cache",
                profile = RouteProfile.CYCLEWAY_BIASED,
                distanceMeters = routeMeters,
                durationSeconds = 0,
                legShapes = emptyList(),
            )
            cachedPedestrianRoute = null
            cachedCyclewayRoute = route
            currentParsed?.let { parsed -> renderProfitability(parsed, null, route) }
            setRouteContent(LiveAdvisorPresentation.platformDistanceLine(routeMeters))
            CaptureEventLog.append(
                service,
                stage = "route_history_distance",
                platform = currentPlatform,
                message = "Restored trusted full-route distance from persisted offer history; route_m=$routeMeters",
                dedupeWindowMs = 1_000L,
            )
        }
    }

    fun updateRouteUnavailable(reason: String) {
        if (dismissed || finalPresentationLocked || !LiveAdvisorSettings.enabled(service)) return
        val expectedGeneration = generation
        handler.post {
            if (dismissed || finalPresentationLocked || generation != expectedGeneration) return@post
            // Ordinary offers never fall back to platform-distance profitability after a real route
            // failure. Wolt add-ons keep their explicit +distance as a truthful fallback when the
            // incremental Valhalla geometry is unavailable or the add-on layout is not yet safe to
            // isolate. Exact one-stop tail add-ons use their verified dropoff-to-dropoff route above.
            val keptIncrementalRate = currentParsed?.takeIf { it.isIncrementalOffer }
                ?.let { renderProvisionalProfitability(it) } == true
            if (!keptIncrementalRate) setDecisionUnavailable()
            val platformMeters = currentParsed?.distanceMeters?.takeIf { it > 0 }
            if (platformMeters != null) {
                setRouteContent("⚠️ ${LiveAdvisorPresentation.platformDistanceLine(platformMeters)}")
            } else {
                setRouteContent("⚠️ Route unavailable")
            }
            CaptureEventLog.append(service, "route_failed", reason, currentPlatform)
        }
    }

    /** Accessibility events make resume nearly immediate; the watchdog remains the fallback. */
    fun onObservedCourierScreen(packageName: String, text: String) {
        if (packageName == expectedPackageName && text.isNotBlank()) {
            latestObservedPackage = packageName
            latestObservedText = text
            latestObservedAtElapsed = SystemClock.elapsedRealtime()
        }
        onCourierWindowEvent(packageName)
    }

    fun onCourierWindowEvent(packageName: String) {
        if (dismissed || currentParsed == null || packageName != expectedPackageName) return
        if (overlayView.isGestureTouchActive) {
            courierEventCheckDeferred = true
            return
        }
        scheduleCourierWindowCheck()
    }

    private fun scheduleCourierWindowCheck(delayMs: Long = COURIER_EVENT_CHECK_DELAY_MS) {
        if (courierEventCheckScheduled) return
        courierEventCheckScheduled = true
        handler.postDelayed(courierWindowCheck, delayMs)
    }

    /** A real foreign window-state event is stronger than rootInActiveWindow on overlay-heavy OEMs. */
    fun onForegroundWindowChanged(packageName: String) {
        if (dismissed || currentParsed == null || packageName.isBlank()) return
        when {
            packageName == expectedPackageName -> onCourierWindowEvent(packageName)
            packageName == service.packageName || isTransientSystemOverlayPackage(packageName) -> Unit
            else -> handler.post {
                if (!dismissed && currentParsed != null) {
                    temporarilyHide("foreground window changed to $packageName")
                }
            }
        }
    }

    private fun dismissCurrentOfferByUser(reason: String) {
        currentParsed?.let { parsed ->
            LiveAdvisorHub.onUserDismissedOffer(
                service,
                expectedPackageName,
                currentNotificationKey,
                parsed,
            )
        }
        suppressCurrentOffer(reason)
    }

    fun suppressCurrentOffer(reason: String = "superseded", animate: Boolean = true) {
        if (!dismissed || overlayView.isAttached) {
            CaptureEventLog.append(
                service,
                stage = "overlay_hide",
                platform = currentPlatform,
                message = reason,
                dedupeWindowMs = 500L,
            )
        }
        dismissed = true
        temporarilyHidden = false
        generation += 1
        clearOfferViewState(animate = animate)
    }

    fun destroy() {
        suppressCurrentOffer("advisor destroyed", animate = false)
        speech.destroy()
    }

    /**
     * The live card has one primary number: native money per real route kilometre. Until both
     * money and a full route exist, the right side stays as a spinner instead of exposing internal
     * capture states such as "price ready" or "calculating".
     */
    private fun renderProgressiveDecision(parsed: ParsedOffer) {
        val hasPrice = parsed.priceCents != null && parsed.money != null
        val hasRoute = cachedPedestrianRoute != null || cachedCyclewayRoute != null
        when {
            !hasPrice -> setDecisionLoading()
            // Exact one-stop Wolt add-ons can now resolve the actual incremental tail
            // (existing drop-off -> appended drop-off). Prefer that verified route once available.
            parsed.isIncrementalOffer && hasRoute ->
                renderProfitability(parsed, cachedPedestrianRoute, cachedCyclewayRoute)
            // Until the incremental tail is ready, or for add-on layouts whose insertion point is
            // ambiguous, Wolt's own +distance is still the truthful zero-latency fallback.
            parsed.isIncrementalOffer && renderProvisionalProfitability(parsed) -> Unit
            // A single successful Valhalla profile is still verified route evidence. The scoring
            // engine averages walking + cycling when both exist, but falls back to the surviving
            // profile when one fails. Never turn that valid partial comparison into `—/km`.
            hasRoute -> renderProfitability(parsed, cachedPedestrianRoute, cachedCyclewayRoute)
            // When real routing is enabled, do not flash a platform-distance €/km that will be
            // replaced moments later by a materially different real-route value. Live 0.15.46
            // showed €0.92/km from Wolt's 3.9 km while the resolved route was €2.73/km.
            LiveAdvisorSettings.routeEnabled(service, currentPlatform) -> setDecisionLoading()
            // A provisional platform-distance rate is only useful when the user disabled routing.
            renderProvisionalProfitability(parsed) -> Unit
            else -> setDecisionUnavailable()
        }
    }

    /**
     * Show money/km as soon as the offer exposes price + its own displayed distance. This path is
     * zero-latency and deliberately never rewrites that distance from historical route samples. No
     * rating emoji is shown until the full Valhalla route is verified.
     */
    private fun renderProvisionalProfitability(parsed: ParsedOffer): Boolean {
        val money = parsed.money ?: return false
        val estimate = LiveRouteDistanceEstimator.estimate(
            currentPlatform,
            parsed.distanceMeters,
        ) ?: return false
        val line = LiveAdvisorPresentation.provisionalRateLine(money, estimate.distanceMeters) ?: return false
        cachedDecisionLine = line
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = false
        applyDecisionPresentation()
        val major = money.major().toDouble()
        val rate = major * 1000.0 / estimate.distanceMeters
        CaptureEventLog.append(
            service,
            stage = "score_estimate",
            platform = currentPlatform,
            message = "source=${estimate.source}; samples=${estimate.sampleCount}; platform_m=${parsed.distanceMeters ?: -1}; " +
                "estimated_route_m=${estimate.distanceMeters}; rate=${"%.2f".format(Locale.US, rate)}",
            dedupeWindowMs = 5_000L,
        )
        return true
    }

    private fun setDecisionLoading() {
        cachedDecisionLine = ""
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = true
        applyDecisionPresentation()
    }

    private fun setDecisionUnavailable() {
        cachedDecisionLine = "—/km"
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = false
        applyDecisionPresentation()
    }

    private fun renderProfitability(
        parsed: ParsedOffer,
        pedestrianRoute: RouteResult?,
        cyclewayRoute: RouteResult?,
    ) {
        val currencyCode = parsed.money?.currencyCode
        val thresholdSnapshot = currencyCode?.let(decisionThresholds::snapshotFor)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute,
            cyclewayRoute,
            thresholds = thresholdSnapshot?.thresholds,
        )
        CaptureEventLog.append(
            service,
            stage = "score_model",
            platform = currentPlatform,
            message = "source=${thresholdSnapshot?.source ?: "none"}; frozen=true; currency=${currencyCode ?: "none"}; " +
                "band=${decision.band.name}; rate=${decision.moneyPerKilometer?.let { "%.2f".format(Locale.US, it) } ?: "none"}",
            dedupeWindowMs = 5_000L,
        )
        if (decision.moneyPerKilometer == null) {
            setDecisionLoading()
            return
        }
        cachedDecisionLine = LiveAdvisorPresentation.rateLine(decision)
        cachedDecisionBand = decision.band
        cachedDecisionLoading = false
        // First trustworthy route verdict wins for this offer. Late GPS/geocoder/retry callbacks may
        // still finish in the background, but they must never repaint the number or emoji mid-decision.
        finalPresentationLocked = true
        applyDecisionPresentation()
    }

    private fun renderRouteLoadingState() {
        // Keep one truthful instant datum visible while the real route is resolving. The platform
        // distance is labelled only as distance; the primary €/km remains a spinner until Valhalla
        // returns, so users never see a fake provisional profitability verdict.
        val platformMeters = currentParsed?.distanceMeters?.takeIf { it > 0 }
        if (platformMeters != null) {
            setRouteContent(LiveAdvisorPresentation.platformDistanceLine(platformMeters))
        } else {
            setRouteContent("⏳ Route…")
        }
    }

    private fun setRouteContent(text: String, visible: Boolean = true) {
        cachedRouteLine = text
        cachedRouteVisible = visible
        overlayView.applyRoute(text, visible)
    }

    private fun applyCachedPresentation() {
        applyDecisionPresentation()
        overlayView.applyRoute(cachedRouteLine, cachedRouteVisible)
    }

    private fun applyDecisionPresentation() {
        overlayView.applyDecision(cachedDecisionLine, cachedDecisionBand, cachedDecisionLoading)
    }

    private fun ensureView() = overlayView.ensure()

    private fun detachView(animate: Boolean = true) = overlayView.detach(animate)

    /** Lightweight main-thread state used by capture polling to avoid competing with a finger drag. */
    fun isGestureTouchActive(): Boolean = overlayView.isGestureTouchActive

    /**
     * Keep Wolt visible during display fallback captures. Before price the card contains no money
     * token, and after price the final proof capture is not reparsed; hiding it was pure user-visible
     * flicker on Realme/ColorOS. Bolt keeps the conservative suppression path because its OCR is more
     * spatially fragile on Android versions that cannot capture a single app window.
     */
    fun setCaptureSuppressed(suppressed: Boolean) = overlayView.setCaptureSuppressed(suppressed)

    private fun temporarilyHide(reason: String) {
        if (dismissed || currentParsed == null) return
        val firstHide = !temporarilyHidden
        if (firstHide || overlayView.isAttached) {
            CaptureEventLog.append(
                service,
                stage = "overlay_suspend",
                platform = currentPlatform,
                message = reason,
                dedupeWindowMs = 750L,
            )
        }
        if (firstHide) {
            val now = SystemClock.elapsedRealtime()
            temporaryRestoreDeadlineElapsed = LiveAdvisorRestorePolicy.recoveryWindowMs(currentPlatform, reason)
                ?.let { now + it }
                ?: Long.MAX_VALUE
        } else if (reason.startsWith("foreground")) {
            // Deliberately leaving the courier app is not evidence that the offer expired.
            temporaryRestoreDeadlineElapsed = Long.MAX_VALUE
        }
        temporarilyHidden = true
        detachView()
        resetMissingEvidence()
    }

    private fun restoreFromCache(reason: String) {
        if (dismissed || currentParsed == null || !temporarilyHidden) return
        if (SystemClock.elapsedRealtime() > temporaryRestoreDeadlineElapsed) {
            // A short Compose gap may recover, but a Wolt card that resurfaces tens of seconds after
            // becoming unconfirmed is stale Accessibility state, not a live offer.
            suppressCurrentOffer("stale Wolt offer resurfaced after suspension", animate = false)
            return
        }
        val pending = OfferState.pending(service)
        if (pending != null && pending.packageName == expectedPackageName && !previewMode) return
        ensureView()
        if (!overlayView.isAttached) return
        temporarilyHidden = false
        temporaryRestoreDeadlineElapsed = Long.MAX_VALUE
        applyCachedPresentation()
        resetMissingEvidence()
        CaptureEventLog.append(
            service,
            stage = "overlay_restore",
            platform = currentPlatform,
            message = reason,
            dedupeWindowMs = 500L,
        )
    }

    private fun clearOfferViewState(animate: Boolean = true) {
        handler.removeCallbacks(visibilityWatchdog)
        handler.removeCallbacks(courierWindowCheck)
        courierEventCheckScheduled = false
        courierEventCheckDeferred = false
        overlayView.resetInteraction()
        detachView(animate = animate)
        resetMissingEvidence()
        boltBaselineSurface = null
        woltBaselineSurface = null
        previewMode = false
        temporaryRestoreDeadlineElapsed = Long.MAX_VALUE
        offerVisualStartedAtElapsed = 0L
        currentParsed = null
        expectedPackageName = ""
        currentNotificationKey = ""
        currentNotificationRemoved = false
        cachedDecisionLine = ""
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = true
        finalPresentationLocked = false
        decisionThresholds.clearOffer()
        cachedRouteLine = ""
        cachedRouteVisible = true
        cachedPedestrianRoute = null
        cachedCyclewayRoute = null
        latestObservedPackage = ""
        latestObservedText = ""
        latestObservedAtElapsed = 0L
        differentOfferConfirmation.reset()
    }

    private fun sameRoute(previous: RouteResult?, current: RouteResult?): Boolean = when {
        previous == null && current == null -> true
        previous == null || current == null -> false
        else -> previous.distanceMeters == current.distanceMeters && previous.durationSeconds == current.durationSeconds
    }

    private fun hasActiveNotificationAnchor(): Boolean =
        currentPlatform.equals("Wolt", ignoreCase = true) &&
            currentNotificationKey.isNotBlank() &&
            !currentNotificationKey.startsWith("screen:") &&
            LiveOfferNotificationLifetime.isActive(expectedPackageName, currentNotificationKey)

    private fun replacementIdentitySummary(
        expected: ParsedOffer,
        visible: ParsedOffer,
        activeNotificationAnchor: Boolean,
    ): String =
        "anchor=$activeNotificationAnchor; " +
            "price=${expected.priceCents ?: -1}->${visible.priceCents ?: -1}; " +
            "distance_m=${expected.distanceMeters ?: -1}->${visible.distanceMeters ?: -1}; " +
            "deliveries=${expected.deliveryCount ?: -1}->${visible.deliveryCount ?: -1}; " +
            "pickups=${expected.pickupAddresses.size}->${visible.pickupAddresses.size}; " +
            "dropoffs=${expected.dropoffAddresses.size}->${visible.dropoffAddresses.size}; " +
            "merchants=${expected.merchantNames.size}->${visible.merchantNames.size}"

    private fun notificationIsAlreadyRemoved(packageName: String, notificationKey: String): Boolean =
        notificationKey.isNotBlank() &&
            !notificationKey.startsWith("screen:") &&
            !LiveOfferNotificationLifetime.isActive(packageName, notificationKey)

    fun onOfferNotificationRemoved(notificationKey: String) {
        if (notificationKey.isBlank() || notificationKey != currentNotificationKey) return
        currentNotificationRemoved = true
        handler.postDelayed({
            if (!dismissed && currentParsed != null && currentNotificationKey == notificationKey) {
                checkOfferStillVisible()
            }
        }, NOTIFICATION_REMOVAL_RECHECK_MS)
    }

    private fun startVisibilityWatchdog() {
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, VISIBILITY_CHECK_MS)
    }

    private fun checkOfferStillVisible() {
        val expected = expectedPackageName
        val expectedOffer = currentParsed ?: return
        if (expected.isBlank()) return
        if (acceptRecentSameOfferObservation(expected, expectedOffer)) return

        val activePackage = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val courierRoot = findVisiblePackageRoot(expected)
        val definitelyAway = activePackage.isNotBlank() &&
            activePackage != expected &&
            activePackage != service.packageName &&
            !isTransientSystemOverlayPackage(activePackage)

        if (definitelyAway) {
            if (currentNotificationRemoved) {
                // The exact incoming-task notification is already gone and the user left the courier
                // app: this transaction cannot legitimately come back. Remove the card immediately.
                suppressCurrentOffer("offer notification ended and courier app left foreground", animate = false)
            } else {
                temporarilyHide("foreground changed to $activePackage")
            }
            return
        }

        if (courierRoot == null) {
            if (isTransientSystemOverlayPackage(activePackage) || hasActiveNotificationAnchor()) {
                resetMissingEvidence()
                return
            }
            val gone = if (currentNotificationRemoved) {
                registerMissingEvidence(
                    graceMs = REMOVED_NOTIFICATION_GONE_GRACE_MS,
                    minChecks = REMOVED_NOTIFICATION_MIN_MISSING_CHECKS,
                )
            } else {
                registerMissingEvidence()
            }
            if (gone) {
                if (currentNotificationRemoved) {
                    suppressCurrentOffer("offer notification ended and courier window disappeared", animate = false)
                } else {
                    temporarilyHide("courier window temporarily unavailable; active=$activePackage")
                }
            }
            return
        }

        val inspection = inspectVisibleSurface(courierRoot)
        val visibleText = inspection.text
        val parsed = OfferParser.parse(visibleText)
        val hasOfferUi = CourierSignals.looksLikeOfferScreen(visibleText, parsed) || hasDecisionPair(visibleText)

        // Wolt's decline confirmation is a modal over the same live offer. Check it before every
        // generic offer/home/identity rule because underlying Compose nodes may still contain both
        // old offer controls and the city home map while the modal is on top.
        if (CourierSignals.looksLikeWoltDeclineConfirmation(expected, visibleText)) {
            differentOfferConfirmation.reset()
            woltHomeEndConfirmation.reset()
            resetMissingEvidence()
            if (temporarilyHidden) restoreFromCache("Wolt decline confirmation still belongs to current offer")
            return
        }

        // CourierPilot deliberately opens Wolt's Multiple dropoffs sheet to recover hidden customer
        // destinations. The sheet replaces the offer-card Compose tree, but it is still the same
        // transaction. Never let the generic structural-change fallback tear down/re-arm the card.
        if (CourierSignals.looksLikeWoltMultipleDropoffsSheet(expected, visibleText)) {
            differentOfferConfirmation.reset()
            woltHomeEndConfirmation.reset()
            resetMissingEvidence()
            if (temporarilyHidden) restoreFromCache("Wolt multiple-dropoff sheet still belongs to current offer")
            return
        }

        // Known Wolt navigation pages are decisive end-of-offer evidence. Real 0.15.47 telemetry
        // showed the advisor staying over the Stats page because an incoming-task notification was
        // still considered a lifetime anchor even though the visible Wolt surface was unrelated.
        if (CourierSignals.looksLikeWoltNonOfferNavigationScreen(expected, visibleText)) {
            suppressCurrentOffer("offer replaced by Wolt navigation screen", animate = false)
            return
        }

        // Strong accepted/in-progress task surfaces always beat stale offer identity. Bolt can keep
        // merchant/address nodes around after Accept, so waiting for generic surface change was able
        // to pin the card over Dropoff/Address details screens indefinitely.
        if (DeliveryLifecycleTracking.hasActiveTaskSurface(visibleText)) {
            suppressCurrentOffer("offer accepted; active delivery screen visible")
            return
        }

        // A live offer is stronger evidence than generic background/presence strings rendered on
        // the same Wolt screen. In particular, Wolt can expose "Go offline" while an incoming
        // offer is still fully visible. Never end the card before checking the offer UI itself.
        if (hasOfferUi) {
            val differentNow = LiveOfferResumePolicy.definitelyDifferent(expectedOffer, parsed)
            if (differentNow) {
                val activeNotificationAnchor = hasActiveNotificationAnchor()
                if (LiveOfferReplacementPolicy.shouldDeferScreenReplacement(
                        platform = currentPlatform,
                        hasActiveNotificationAnchor = activeNotificationAnchor,
                        expected = expectedOffer,
                        visible = parsed,
                    )
                ) {
                    // Today's 0.15.79 traces showed the same Wolt card being suppressed after its
                    // route was already computed because Compose briefly reconstructed the route as
                    // a different merchant/stop set. Keep the notification-owned transaction alive
                    // unless the visible card proves a different price or a new notification arms
                    // the replacement through the normal capture path.
                    differentOfferConfirmation.reset()
                    woltHomeEndConfirmation.reset()
                    resetMissingEvidence()
                    CaptureEventLog.append(
                        service,
                        stage = "overlay_difference_deferred",
                        platform = currentPlatform,
                        message = replacementIdentitySummary(expectedOffer, parsed, activeNotificationAnchor),
                        dedupeWindowMs = 2_000L,
                    )
                    if (temporarilyHidden) {
                        restoreFromCache("active Wolt notification still owns the current offer")
                    }
                    return
                }
                if (isConfirmedDifferentOffer(expected, parsed)) {
                    CaptureEventLog.append(
                        service,
                        stage = "overlay_replacement_confirmed",
                        platform = currentPlatform,
                        message = replacementIdentitySummary(expectedOffer, parsed, activeNotificationAnchor),
                        dedupeWindowMs = 1_000L,
                    )
                    suppressCurrentOffer("different offer is now stably visible")
                } else if (activeNotificationAnchor) {
                    // An explicit conflicting price still needs stability confirmation before replacement.
                    resetMissingEvidence()
                } else {
                    // Do not blink the current card merely because a screen-only parse looks
                    // different. Keep the known offer visible while the conflict confirmation
                    // accumulates; hide only after replacement is actually confirmed.
                    resetMissingEvidence()
                    CaptureEventLog.append(
                        service,
                        stage = "overlay_difference_observed",
                        platform = currentPlatform,
                        message = replacementIdentitySummary(expectedOffer, parsed, activeNotificationAnchor),
                        dedupeWindowMs = 1_000L,
                    )
                }
                return
            }
            differentOfferConfirmation.reset()
            woltHomeEndConfirmation.reset()
            resetMissingEvidence()
            if (currentPlatform.equals("Bolt", ignoreCase = true)) {
                // Adopt the latest confirmed same-offer surface after map zoom/recomposition instead
                // of treating the old geometry snapshot as immutable proof that the offer vanished.
                boltBaselineSurface = inspection.snapshot
            } else if (currentPlatform.equals("Wolt", ignoreCase = true)) {
                woltBaselineSurface = inspection.snapshot
            }
            if (temporarilyHidden) restoreFromCache("same offer returned to foreground")
            return
        }

        // Wolt Compose can expose the underlying city-home `Delivery demand` node for a single
        // visible frame while the offer sheet is still on screen (observed on 0.15.51 immediately
        // after a transient Android window). Never let that one frame destroy a freshly calculated
        // route. Require the home surface to remain stable across two watchdog observations.
        val idleHome = CourierSignals.looksLikeIdleHomeScreen(expected, visibleText)
        if (idleHome) {
            if (woltHomeEndConfirmation.observe(true, SystemClock.elapsedRealtime())) {
                suppressCurrentOffer("offer replaced by stable Wolt home screen", animate = false)
            }
            return
        } else {
            woltHomeEndConfirmation.reset()
        }

        DeliveryLifecycleTracking.detect(visibleText)?.let {
            suppressCurrentOffer("offer ended: ${it.type}")
            return
        }
        val presence = CourierSignals.detectPresence(visibleText)
        if (presence != PresenceSignal.UNKNOWN) {
            suppressCurrentOffer("offer replaced by presence=$presence")
            return
        }

        // Compose/map recompositions can temporarily remove Accept/Decline while retaining the same
        // price, merchant or address. This identity is stronger than window geometry on both apps.
        if (LiveOfferResumePolicy.hasMatchingIdentity(expectedOffer, parsed)) {
            resetMissingEvidence()
            if (currentPlatform.equals("Bolt", ignoreCase = true)) {
                boltBaselineSurface = inspection.snapshot
            } else if (currentPlatform.equals("Wolt", ignoreCase = true)) {
                woltBaselineSurface = inspection.snapshot
            }
            if (temporarilyHidden) restoreFromCache("same offer identity returned without controls")
            return
        }

        if (currentPlatform.equals("Bolt", ignoreCase = true)) {
            val baseline = boltBaselineSurface
            if (baseline == null) {
                // Without a known live Bolt surface, do not resurrect a hidden card from guesswork.
                if (!temporarilyHidden) {
                    boltBaselineSurface = inspection.snapshot
                    resetMissingEvidence()
                }
                return
            }
            if (!LiveOfferSurfaceEvidence.materiallyChanged(baseline, inspection.snapshot)) {
                resetMissingEvidence()
                if (temporarilyHidden) restoreFromCache("same sparse Bolt offer surface returned")
                return
            }
            // Zooming/panning the live map changes a large part of Bolt's Accessibility geometry even
            // though the bottom offer card is exactly the same. Geometry alone therefore gets a long
            // grace period; explicit task/presence/different-offer evidence above still hides at once.
            if (registerMissingEvidence(graceMs = BOLT_GONE_GRACE_MS, minChecks = BOLT_MIN_MISSING_CHECKS)) {
                temporarilyHide("Bolt offer surface remained unconfirmed")
            }
            return
        }

        if (currentPlatform.equals("Wolt", ignoreCase = true)) {
            val baseline = woltBaselineSurface
            if (baseline != null && LiveOfferSurfaceEvidence.materiallyChanged(baseline, inspection.snapshot)) {
                // A populated, structurally different Wolt page cannot be kept alive forever by a
                // stale incoming-task notification. Require a short stable transition so one Compose
                // frame does not kill the card, then end it even for navigation pages we do not know by name.
                if (registerMissingEvidence(
                        graceMs = WOLT_NAVIGATION_GONE_GRACE_MS,
                        minChecks = WOLT_NAVIGATION_MIN_MISSING_CHECKS,
                    )
                ) {
                    suppressCurrentOffer("offer replaced by materially different Wolt screen", animate = false)
                }
                return
            }
        }

        // The exact active incoming-task notification is a strong positive lifetime anchor only for
        // transient Compose gaps, not for a stable different Wolt page.
        if (hasActiveNotificationAnchor()) {
            resetMissingEvidence()
            return
        }
        if (registerMissingEvidence(graceMs = WOLT_UNCERTAIN_GRACE_MS, minChecks = WOLT_UNCERTAIN_MIN_MISSING_CHECKS)) {
            temporarilyHide("Wolt offer surface remained unconfirmed")
        }
    }

    private fun isTransientSystemOverlayPackage(packageName: String): Boolean =
        packageName == SYSTEM_UI_PACKAGE ||
            packageName == "com.oplus.screenshot" ||
            packageName == "com.coloros.screenshot"

    private fun findVisiblePackageRoot(packageName: String): AccessibilityNodeInfo? =
        surfaceInspector.findVisiblePackageRoot(packageName)

    private fun registerMissingEvidence(
        now: Long = SystemClock.elapsedRealtime(),
        graceMs: Long = GONE_GRACE_MS,
        minChecks: Int = MIN_MISSING_CHECKS,
    ): Boolean {
        if (missingSince == 0L) missingSince = now
        missingChecks += 1
        return missingChecks >= minChecks && now - missingSince >= graceMs
    }

    private fun resetMissingEvidence() {
        missingSince = 0L
        missingChecks = 0
    }

    private fun acceptRecentSameOfferObservation(expectedPackage: String, expectedOffer: ParsedOffer): Boolean {
        if (latestObservedPackage != expectedPackage) return false
        val ageMs = SystemClock.elapsedRealtime() - latestObservedAtElapsed
        if (ageMs !in 0..RECENT_SCREEN_TEXT_TTL_MS) return false
        val text = latestObservedText
        if (text.isBlank()) return false

        if (CourierSignals.looksLikeWoltDeclineConfirmation(expectedPackage, text)) {
            differentOfferConfirmation.reset()
            woltHomeEndConfirmation.reset()
            resetMissingEvidence()
            if (temporarilyHidden) restoreFromCache("recent Accessibility text still shows the Wolt decline modal")
            return true
        }

        val parsed = OfferParser.parse(text)
        val hasOfferUi = CourierSignals.looksLikeOfferScreen(text, parsed) || hasDecisionPair(text)
        if (!hasOfferUi || LiveOfferResumePolicy.definitelyDifferent(expectedOffer, parsed)) return false

        differentOfferConfirmation.reset()
        woltHomeEndConfirmation.reset()
        resetMissingEvidence()
        if (temporarilyHidden) restoreFromCache("recent Accessibility text confirms the same live offer")
        return true
    }

    private fun inspectVisibleSurface(rootNode: AccessibilityNodeInfo): LiveAdvisorSurfaceInspection =
        surfaceInspector.inspectVisibleSurface(rootNode, currentPlatform)

    private fun hasDecisionPair(text: String): Boolean = surfaceInspector.hasDecisionPair(text)

    private fun flushDeferredCourierWindowCheck() {
        if (!courierEventCheckDeferred) return
        courierEventCheckDeferred = false
        scheduleCourierWindowCheck(0L)
    }

    private fun packageForPlatform(platform: String): String = when {
        platform.equals("Wolt", true) -> CourierSignals.WOLT_PACKAGE
        platform.equals("Bolt", true) -> CourierSignals.BOLT_PACKAGE
        else -> ""
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val VISIBILITY_CHECK_MS = 750L
        const val HIDDEN_VISIBILITY_CHECK_MS = 1_500L
        const val GONE_GRACE_MS = 1_500L
        const val MIN_MISSING_CHECKS = 3
        const val BOLT_GONE_GRACE_MS = 8_000L
        const val BOLT_MIN_MISSING_CHECKS = 5
        const val WOLT_UNCERTAIN_GRACE_MS = 2_000L
        const val WOLT_UNCERTAIN_MIN_MISSING_CHECKS = 3
        const val WOLT_NAVIGATION_GONE_GRACE_MS = 900L
        const val WOLT_NAVIGATION_MIN_MISSING_CHECKS = 2
        const val REMOVED_NOTIFICATION_GONE_GRACE_MS = 350L
        const val REMOVED_NOTIFICATION_MIN_MISSING_CHECKS = 2
        const val NOTIFICATION_REMOVAL_RECHECK_MS = 60L
        const val COURIER_EVENT_CHECK_DELAY_MS = 48L
        const val RECENT_SCREEN_TEXT_TTL_MS = 350L
    }
}
