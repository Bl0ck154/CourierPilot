package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Process-local bridge between the Accessibility service and post-persistence helpers. The database
 * remains usable without an attached service; advisor work simply becomes a no-op in that case.
 */
internal object LiveAdvisorHub {
    private data class CurrentAdvisorOffer(
        val offerId: Long,
        val record: OfferRecord,
        val parsed: ParsedOffer,
        val supplementalBoltPickupAddresses: List<String> = emptyList(),
        val woltRouteScope: WoltRouteScope? = null,
    )

    private data class PendingAdvisorOffer(
        val key: String,
        val packageName: String,
        val notificationKey: String,
        val armedAt: Long,
        val parsed: ParsedOffer,
        val woltRouteScope: WoltRouteScope? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceRef = WeakReference<AccessibilityService>(null)
    private var advisor: StableLiveOfferAdvisor? = null
    private var currentOffer: CurrentAdvisorOffer? = null
    private var pendingPreview: PendingAdvisorOffer? = null
    private var captureOfferKey: String? = null
    private var currentOfferHasResolvedRoute = false
    private var currentWoltRouteRetryCount = 0
    private data class UserDismissedOffer(
        val identity: LiveOfferDismissalIdentity,
        val notificationKey: String,
        val dismissedAtElapsed: Long,
    )
    private var userDismissedOffer: UserDismissedOffer? = null
    private val WOLT_BASELINE_CANDIDATE_STATES = setOf(
        // Wolt can replace the offer with the task map before Accessibility catches an explicit
        // acceptance cue. An incremental `+... extra` card itself proves an active route exists, so
        // a fresh previous capture may still be used only after exact pickup + first-dropoff match.
        DeliveryEventType.OFFER_CAPTURED,
        DeliveryEventType.ACCEPTED,
        DeliveryEventType.ARRIVED_PICKUP,
        DeliveryEventType.PICKED_UP,
        DeliveryEventType.ARRIVED_DROPOFF,
    )

    fun attach(context: Context) {
        val service = context as? AccessibilityService ?: return
        if (serviceRef.get() === service && advisor != null) return
        advisor?.destroy()
        serviceRef = WeakReference(service)
        advisor = StableLiveOfferAdvisor(service)
        currentOffer = null
        pendingPreview = null
        captureOfferKey = null
        currentOfferHasResolvedRoute = false
        currentWoltRouteRetryCount = 0
    }

    /**
     * Hide the previous offer exactly once when a genuinely new capture transaction starts. Repeated
     * observations for the same pending offer must not kill the progressive preview we show later.
     */
    fun hideForCapture(context: Context, pending: PendingOffer) {
        attach(context)
        observeIncomingCapture(pending)
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (captureOfferKey == key) return
        captureOfferKey = key
        val keepCurrentSurfaceWarm = advisor?.isTrackingOffer(pending.packageName) == true
        pendingPreview = null
        if (keepCurrentSurfaceWarm) {
            // A fresh same-platform notification is not itself proof of a fresh visible offer.
            // Keep the old card on-screen until parsed screen identity confirms replacement. This
            // removes the hide/show flash when Wolt rotates notification keys while ringing.
            CaptureEventLog.append(
                context,
                stage = "capture_boundary_deferred",
                platform = OfferState.platformLabel(pending.packageName),
                message = "Kept current live card warm until same-platform replacement is verified on screen",
                dedupeWindowMs = 1_000L,
            )
            return
        }
        currentOffer = null
        currentOfferHasResolvedRoute = false
        currentWoltRouteRetryCount = 0
        advisor?.suppressCurrentOffer("new offer capture started", animate = false)
    }

    fun coalesceOfferNotificationRefresh(packageName: String, notificationKey: String): Boolean {
        val currentAdvisor = advisor ?: return false
        if (!currentAdvisor.isSameOfferVisiblyPresent(packageName)) return false
        currentAdvisor.retargetNotificationAnchor(packageName, notificationKey)
        return true
    }

    fun showPendingOffer(context: Context, pending: PendingOffer, parsed: ParsedOffer) {
        attach(context)
        val service = serviceRef.get() ?: return
        val livePending = OfferState.pending(service)
        val transactionStillCurrent = livePending != null &&
            livePending.packageName == pending.packageName &&
            livePending.armedAt == pending.armedAt &&
            (pending.notificationKey.isBlank() || livePending.notificationKey == pending.notificationKey)
        if (!transactionStillCurrent) return
        val platform = OfferState.platformLabel(pending.packageName)
        observeIncomingCapture(pending)
        if (isUserDismissedOffer(pending.packageName, parsed, pending.notificationKey)) {
            CaptureEventLog.append(
                service,
                stage = "overlay_user_dismiss_suppressed_reopen",
                platform = platform,
                message = "Ignored live-card update for the same offer the user already dismissed",
                dedupeWindowMs = 2_000L,
            )
            return
        }
        currentOffer?.takeIf { it.record.packageName == pending.packageName }?.let { current ->
            if (LiveOfferResumePolicy.definitelyDifferent(current.parsed, parsed)) {
                // hideForCapture deliberately keeps a same-platform card warm until screen identity
                // arrives. Once the new surface is genuinely different, invalidate old route callbacks.
                currentOffer = null
                currentOfferHasResolvedRoute = false
                currentWoltRouteRetryCount = 0
            }
        }
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        val woltRouteScope = if (pending.packageName == CourierSignals.WOLT_PACKAGE) {
            resolveWoltRouteScope(service, parsed)
        } else null
        pendingPreview = PendingAdvisorOffer(
            key,
            pending.packageName,
            pending.notificationKey,
            pending.armedAt,
            parsed,
            woltRouteScope,
        )
        advisor?.showPending(platform, parsed, pending.notificationKey)

        // Bolt address geocoding is independent from price persistence. Start it as soon as the
        // offer card exposes pickup addresses so the live route can usually hit the in-memory cache
        // once the priced offer is committed.
        if (pending.packageName == CourierSignals.BOLT_PACKAGE && parsed.pickupAddresses.isNotEmpty()) {
            RouteResearchGeocoder.prewarm(service, parsed.pickupAddresses)
        }

        if (pending.packageName == CourierSignals.WOLT_PACKAGE) {
            val started = AutomaticWoltRouteCoordinator.prepare(
                service,
                key,
                parsed,
                routeScope = woltRouteScope ?: WoltIncrementalRoutePolicy.select(parsed),
            ) { prepared ->
                val active = pendingPreview
                if (active?.key != key || active.packageName != pending.packageName) return@prepare
                val comparison = prepared.comparison
                if (comparison == null) {
                    val reason = prepared.failureReason ?: "unknown route preparation failure"
                    CaptureEventLog.append(
                        service,
                        stage = "route_prepare_failed",
                        platform = platform,
                        message = "$reason; preview kept loading for fresh persisted retry",
                        dedupeWindowMs = 500L,
                    )
                    // A pre-price route preparation failure is not final. start() deliberately runs
                    // a fresh route after persistence, so never poison the visible preview with a
                    // sticky `—/km` state while the same offer can still recover.
                    return@prepare
                }
                val walking = comparison.pedestrian.getOrNull()?.distanceMeters
                val cycling = comparison.cycleway.getOrNull()?.distanceMeters
                val average = OfferDecisionEngine.averageValhallaDistanceMeters(
                    comparison.pedestrian.getOrNull(), comparison.cycleway.getOrNull(),
                )
                CaptureEventLog.append(
                    service,
                    stage = "route_prepared",
                    platform = platform,
                    message = "points=${prepared.waypoints.size}; walk_m=${walking ?: -1}; cycle_m=${cycling ?: -1}; " +
                        "avg_m=${average ?: -1}; platform_m=${prepared.platformDistanceMeters ?: -1}; " +
                        "direct_chain_m=${prepared.directChainMeters ?: -1}; direct_legs_m=${RouteGeometryMetrics.directLegSummary(prepared.waypoints)}; " +
                        "gps_age_ms=${prepared.locationAgeMillis ?: -1}; gps_accuracy_m=${prepared.locationAccuracyMeters ?: -1f}",
                    dedupeWindowMs = 500L,
                )
                advisor?.updateWoltRoute(comparison, prepared.waypoints.size, prepared.scope)
            }
            if (started) {
                CaptureEventLog.append(
                    service,
                    stage = "route_prepare_start",
                    platform = platform,
                    message = "Wolt route preparation started before price; scope=${woltRouteScope?.kind ?: WoltRouteScopeKind.FULL_REMAINING}",
                    dedupeWindowMs = 10_000L,
                )
            }
        }
    }

    /**
     * Recover an offer that was already persisted but whose overlay was destroyed by transient
     * Accessibility state. This runs before ScreenOfferDeduper, so the 10-minute screen tombstone
     * cannot leave the user with no card when the *same* offer is still visibly open.
     */
    fun tryRestoreRecentOffer(context: Context, packageName: String, parsed: ParsedOffer): Boolean {
        attach(context)
        val service = serviceRef.get() ?: return false
        val currentAdvisor = advisor ?: return false
        val historical = OfferHistoryResumePolicy.findMatchingRecent(
            database = OfferDatabase.get(service),
            packageName = packageName,
            parsed = parsed,
        ) ?: return false
        restoreHistoricalOffer(service, currentAdvisor, historical, parsed, "screen history match")
        return true
    }

    fun restoreDuplicateOffer(context: Context, historical: OfferRecord, visible: ParsedOffer) {
        attach(context)
        val service = serviceRef.get() ?: return
        val currentAdvisor = advisor ?: return
        restoreHistoricalOffer(service, currentAdvisor, historical.withCurrentParsedStructure(), visible, "persistence duplicate")
    }

    private fun restoreHistoricalOffer(
        service: AccessibilityService,
        currentAdvisor: StableLiveOfferAdvisor,
        historical: OfferRecord,
        visible: ParsedOffer,
        reason: String,
    ) {
        val syntheticCaptureKey = "screen:history:${historical.id}"
        val parsedFromHistory = OfferParser.parse(historical.rawText)
        val merged = visible.copy(
            priceCents = visible.priceCents ?: historical.priceCents,
            money = visible.money ?: MoneyAmount(
                historical.priceCents.toLong(),
                historical.currencyCode,
                historical.currencyFractionDigits,
            ),
            distanceMeters = visible.distanceMeters ?: historical.distanceMeters,
            restaurant = visible.restaurant ?: historical.restaurant ?: parsedFromHistory.restaurant,
            merchantNames = visible.merchantNames.ifEmpty { historical.merchantNames.ifEmpty { parsedFromHistory.merchantNames } },
            pickupAddresses = visible.pickupAddresses.ifEmpty { historical.pickupAddresses.ifEmpty { parsedFromHistory.pickupAddresses } },
            customerNames = visible.customerNames.ifEmpty { historical.customerNames.ifEmpty { parsedFromHistory.customerNames } },
            dropoffAddresses = visible.dropoffAddresses.ifEmpty { historical.dropoffAddresses.ifEmpty { parsedFromHistory.dropoffAddresses } },
            deliveryCount = visible.deliveryCount ?: historical.deliveryCount ?: parsedFromHistory.deliveryCount,
            estimatedMinutesMin = visible.estimatedMinutesMin ?: historical.estimatedMinutesMin ?: parsedFromHistory.estimatedMinutesMin,
            estimatedMinutesMax = visible.estimatedMinutesMax ?: historical.estimatedMinutesMax ?: parsedFromHistory.estimatedMinutesMax,
            isIncrementalOffer = visible.isIncrementalOffer || parsedFromHistory.isIncrementalOffer,
            incrementalStopCount = visible.incrementalStopCount ?: parsedFromHistory.incrementalStopCount,
        )
        if (isUserDismissedOffer(historical.packageName, merged)) {
            CaptureEventLog.append(
                service,
                stage = "overlay_user_dismiss_history_restore_blocked",
                platform = historical.platform,
                message = "Blocked history/duplicate restore for the same offer the user dismissed",
                dedupeWindowMs = 2_000L,
            )
            return
        }
        val activeRecord = historical.copy(
            captureKey = syntheticCaptureKey,
            distanceMeters = merged.distanceMeters,
            restaurant = merged.restaurant,
            merchantNames = merged.merchantNames,
            pickupAddresses = merged.pickupAddresses,
            customerNames = merged.customerNames,
            dropoffAddresses = merged.dropoffAddresses,
            deliveryCount = merged.deliveryCount,
            estimatedMinutesMin = merged.estimatedMinutesMin,
            estimatedMinutesMax = merged.estimatedMinutesMax,
        )
        currentOffer = CurrentAdvisorOffer(historical.id, activeRecord, merged)
        pendingPreview = null
        captureOfferKey = null
        currentOfferHasResolvedRoute = false
        currentWoltRouteRetryCount = 0

        currentAdvisor.showBase(historical.platform, merged, syntheticCaptureKey)
        // Older route snapshots do not persist whether an incremental offer was routed as the
        // paid dropoff tail or as the full remaining chain. Reusing one as €/km would be unsafe.
        // Recompute incremental offers under the current scope policy instead.
        val route = if (merged.isIncrementalOffer) null else {
            runCatching { RouteResearchDatabase.get(service).latestSuccessfulAdvisorRoute(historical.id) }.getOrNull()
        }
        val historicalRouteMeters = historical.trustedMarketRouteDistanceMeters
            .takeUnless { merged.isIncrementalOffer }
        when {
            route != null -> {
                currentOfferHasResolvedRoute = true
                currentAdvisor.updateRoute(route.comparison, route.waypointCount)
            }
            historicalRouteMeters != null -> {
                currentOfferHasResolvedRoute = true
                currentAdvisor.updateHistoricalRouteDistance(historicalRouteMeters)
            }
            else -> startRouteForOffer(service, currentOffer ?: return, preparedKey = null)
        }
        CaptureEventLog.append(
            service,
            stage = "offer_history_restored",
            platform = historical.platform,
            message = "Restored record #${historical.id} after $reason; reused_route=${route != null}; " +
                "history_route_m=${historical.trustedMarketRouteDistanceMeters ?: -1}",
            dedupeWindowMs = 1_000L,
        )
    }

    fun onUserDismissedOffer(
        context: Context,
        packageName: String,
        notificationKey: String,
        parsed: ParsedOffer,
    ) {
        attach(context)
        val service = serviceRef.get() ?: return
        userDismissedOffer = UserDismissedOffer(
            identity = LiveOfferUserDismissalPolicy.identity(packageName, parsed),
            notificationKey = notificationKey,
            dismissedAtElapsed = android.os.SystemClock.elapsedRealtime(),
        )
        pendingPreview = null
        currentOffer = null
        captureOfferKey = null
        currentOfferHasResolvedRoute = false
        currentWoltRouteRetryCount = 0
        CaptureEventLog.append(
            service,
            stage = "overlay_user_dismissed",
            platform = OfferState.platformLabel(packageName),
            message = "Suppressed this live offer until a genuinely different offer or home screen appears",
            dedupeWindowMs = 500L,
        )
    }

    fun isUserDismissedOffer(
        packageName: String,
        parsed: ParsedOffer,
        notificationKey: String = "",
    ): Boolean {
        val dismissed = userDismissedOffer ?: return false
        if (android.os.SystemClock.elapsedRealtime() - dismissed.dismissedAtElapsed > USER_DISMISS_TTL_MS) {
            userDismissedOffer = null
            return false
        }
        if (packageName == dismissed.identity.packageName &&
            notificationKey.isNotBlank() &&
            dismissed.notificationKey.isNotBlank() &&
            notificationKey == dismissed.notificationKey
        ) {
            return true
        }
        return LiveOfferUserDismissalPolicy.isSameOffer(
            dismissed.identity,
            LiveOfferUserDismissalPolicy.identity(packageName, parsed),
        )
    }

    fun clearUserDismissal(context: Context, packageName: String, reason: String) {
        attach(context)
        val dismissed = userDismissedOffer ?: return
        if (dismissed.identity.packageName != packageName) return
        userDismissedOffer = null
        serviceRef.get()?.let { service ->
            CaptureEventLog.append(
                service,
                stage = "overlay_user_dismiss_cleared",
                platform = OfferState.platformLabel(packageName),
                message = reason,
                dedupeWindowMs = 1_000L,
            )
        }
    }

    private fun observeIncomingCapture(pending: PendingOffer) {
        val dismissed = userDismissedOffer ?: return
        val key = pending.notificationKey
        val isRealNotification = key.isNotBlank() && !key.startsWith("screen:")
        if (!isRealNotification) return
        // If the user dismissed a screen-discovered offer before its notification arrived, that late
        // notification can still belong to the same card. Only a different real notification may
        // clear a tombstone that was itself tied to a real notification instance.
        if (dismissed.notificationKey.isBlank() || dismissed.notificationKey.startsWith("screen:")) return
        val sameExactNotification = dismissed.identity.packageName == pending.packageName &&
            dismissed.notificationKey == key
        if (!sameExactNotification) userDismissedOffer = null
    }

    fun setCaptureSuppressed(context: Context, suppressed: Boolean) {
        attach(context)
        advisor?.setCaptureSuppressed(suppressed)
    }

    fun onActiveTaskSurface(context: Context, packageName: String) {
        attach(context)
        pendingPreview = null
        currentOffer = null
        captureOfferKey = null
        currentOfferHasResolvedRoute = false
        currentWoltRouteRetryCount = 0
        advisor?.suppressCurrentOffer("offer accepted; active delivery screen visible", animate = false)
        serviceRef.get()?.let { service ->
            CaptureEventLog.append(
                service,
                stage = "active_task_offer_terminated",
                platform = OfferState.platformLabel(packageName),
                message = "Accepted task surface terminated live offer UI",
                dedupeWindowMs = 2_000L,
            )
        }
    }

    /** AccessibilityService and the overlay share the main looper, so this is a cheap coordination read. */
    fun isOverlayGestureActive(): Boolean = advisor?.isGestureTouchActive() == true

    fun onOfferPersisted(offerId: Long, record: OfferRecord) {
        val service = serviceRef.get() ?: return
        val currentAdvisor = advisor ?: return
        // Reparse the same captured screen text here so the sequential Timeline stop order remains
        // available to the router without changing the stable offer-history DB schema.
        val parsedFromScreen = OfferParser.parse(record.rawText)
        val activePending = OfferState.pending(service)
        val previewIdentityMatches = pendingPreview
            ?.takeIf { it.packageName == record.packageName }
            ?.parsed
            ?.let { preview ->
                !LiveOfferResumePolicy.definitelyDifferent(preview, parsedFromScreen) &&
                    (LiveOfferResumePolicy.hasCompatibleCoreIdentity(preview, parsedFromScreen) ||
                        LiveOfferResumePolicy.hasMatchingIdentity(preview, parsedFromScreen))
            } == true
        val visibleIdentityMatches = currentAdvisor.isSameOfferVisiblyPresent(record.packageName)
        val compatibleOfferEvidence = previewIdentityMatches || visibleIdentityMatches
        if (LiveOfferTransactionPolicy.shouldIgnorePersistedOffer(
                activePackageName = activePending?.packageName,
                activeNotificationKey = activePending?.notificationKey,
                persistedPackageName = record.packageName,
                persistedCaptureKey = record.captureKey,
                compatibleOfferEvidence = compatibleOfferEvidence,
            )
        ) {
            // A genuinely different notification can arrive during the tiny DB-insert window after
            // persistOffer's stale-callback check. Keep the newer transaction only when the current
            // screen/preview no longer matches the just-persisted offer.
            CaptureEventLog.append(
                service,
                stage = "advisor_stale_persist_ignored",
                platform = record.platform,
                message = "Persisted offer belongs to a superseded notification and visible identity differs; live advisor left on the newer offer",
                dedupeWindowMs = 1_000L,
            )
            return
        }
        if (activePending != null &&
            activePending.packageName == record.packageName &&
            activePending.notificationKey.isNotBlank() &&
            record.captureKey.isNotBlank() &&
            activePending.notificationKey != record.captureKey &&
            compatibleOfferEvidence
        ) {
            CaptureEventLog.append(
                service,
                stage = "advisor_persist_key_churn_coalesced",
                platform = record.platform,
                message = "Notification key rotated during persistence but visible offer identity still matches; promoting persisted offer and starting route",
                dedupeWindowMs = 1_000L,
            )
        }
        val parsed = parsedFromScreen.copy(
            priceCents = record.priceCents,
            money = MoneyAmount(record.priceCents.toLong(), record.currencyCode, record.currencyFractionDigits),
            distanceMeters = record.distanceMeters,
            restaurant = record.restaurant ?: parsedFromScreen.restaurant,
            merchantNames = record.merchantNames.ifEmpty { parsedFromScreen.merchantNames },
            pickupAddresses = record.pickupAddresses.ifEmpty { parsedFromScreen.pickupAddresses },
            customerNames = record.customerNames.ifEmpty { parsedFromScreen.customerNames },
            dropoffAddresses = record.dropoffAddresses.ifEmpty { parsedFromScreen.dropoffAddresses },
            deliveryCount = record.deliveryCount ?: parsedFromScreen.deliveryCount,
            estimatedMinutesMin = record.estimatedMinutesMin ?: parsedFromScreen.estimatedMinutesMin,
            estimatedMinutesMax = record.estimatedMinutesMax ?: parsedFromScreen.estimatedMinutesMax,
        )

        if (isUserDismissedOffer(record.packageName, parsed, record.captureKey)) {
            pendingPreview = null
            currentOffer = null
            captureOfferKey = null
            currentOfferHasResolvedRoute = false
            currentWoltRouteRetryCount = 0
            DeliveryLifecycleTracking.onOfferCaptured(service, record.packageName, offerId, record.capturedAt)
            CaptureEventLog.append(
                service,
                stage = "overlay_user_dismiss_persisted_hidden",
                platform = record.platform,
                message = "Offer persisted after user dismissal; history kept but live card stayed hidden",
                dedupeWindowMs = 1_000L,
            )
            return
        }

        val supplementalBoltPickups = if (record.packageName == CourierSignals.BOLT_PACKAGE) {
            // Snapshot the previously accepted task before onOfferCaptured() advances the legacy
            // single-offer lifecycle pointer to this new (possibly add-on) offer.
            BoltActivePickupStore.rememberUncollectedTask(
                service,
                DeliveryLifecycleTracking.currentTask(service, record.packageName),
            )
            BoltActivePickupStore.supplementalForOffer(service, parsed.pickupAddresses)
        } else {
            emptyList()
        }

        val matchingPending = pendingPreview?.takeIf {
            it.packageName == record.packageName && previewIdentityMatches
        }
        val preparedKey = matchingPending?.key ?: captureOfferKey
        val woltRouteScope = if (record.packageName == CourierSignals.WOLT_PACKAGE) {
            matchingPending?.woltRouteScope ?: resolveWoltRouteScope(service, parsed)
        } else null
        val current = CurrentAdvisorOffer(
            offerId,
            record,
            parsed,
            supplementalBoltPickups,
            woltRouteScope,
        )
        currentOffer = current
        pendingPreview = null
        captureOfferKey = null
        currentOfferHasResolvedRoute = false
        currentWoltRouteRetryCount = 0

        DeliveryLifecycleTracking.onOfferCaptured(service, record.packageName, offerId, record.capturedAt)

        // The card shell is rendered synchronously before any route/geocoder work starts. Routing
        // only updates rows inside this already-visible card; it never controls whether the card exists.
        currentAdvisor.showBase(record.platform, parsed, record.captureKey)
        startRouteForOffer(service, current, preparedKey)
    }

    private fun startRouteForOffer(
        service: AccessibilityService,
        current: CurrentAdvisorOffer,
        preparedKey: String? = null,
    ) {
        val record = current.record
        val parsed = current.parsed
        if (!LiveAdvisorSettings.routeEnabled(service, record.platform)) return

        // With experimental Bolt routing enabled, preserve a clean research bundle automatically.
        // The bitmap comes from the already-persisted proof screenshot, so the advisor can never
        // contaminate the map image with its own overlay.
        if (record.platform.equals("Bolt", ignoreCase = true)) {
            val root = service.rootInActiveWindow
            if (root?.packageName?.toString() == CourierSignals.BOLT_PACKAGE) {
                runCatching {
                    BoltAccessibilityDiagnostics.savePersistedOfferSample(
                        context = service,
                        root = root,
                        screenshotUri = record.screenshotUri,
                        location = RouteResearchLocation.bestLastKnown(service),
                    )
                }
            }
            AutomaticBoltRouteCoordinator.start(
                service,
                current.offerId,
                record.platform,
                parsed,
                supplementalPickupAddresses = current.supplementalBoltPickupAddresses,
            ) { outcome ->
                val comparison = outcome.comparison
                // Score/render the candidate against the existing reference corpus before inserting
                // this offer into local/server market history.
                if (isCurrentOffer(current)) advisor?.updateBoltRoute(outcome)
                if (comparison != null && outcome.scope == BoltRouteScope.FULL) {
                    MarketIntelligence.onRouteResolved(
                        service,
                        current.offerId,
                        record,
                        comparison.pedestrian.getOrNull(),
                        comparison.cycleway.getOrNull(),
                    )
                }
            }
            return
        }

        if (record.platform.equals("Wolt", ignoreCase = true)) {
            AutomaticWoltRouteCoordinator.start(
                service,
                current.offerId,
                record.platform,
                parsed,
                routeScope = current.woltRouteScope ?: WoltIncrementalRoutePolicy.select(parsed),
                preparedKey = preparedKey,
            ) { outcome ->
                val comparison = outcome.comparison
                if (isCurrentOffer(current)) {
                    if (comparison != null) {
                        currentOfferHasResolvedRoute = true
                        currentWoltRouteRetryCount = 0
                        advisor?.updateWoltRoute(comparison, outcome.waypoints.size, outcome.scope)
                    } else {
                        val reason = outcome.failureReason ?: "unknown failure"
                        when (LiveAdvisorRouteFailurePolicy.decideWoltFinalFailure(
                            hasResolvedRoute = currentOfferHasResolvedRoute,
                            retryCount = currentWoltRouteRetryCount,
                            reason = reason,
                        )) {
                            WoltRouteFailureAction.PRESERVE_LAST_GOOD -> {
                                CaptureEventLog.append(
                                    service,
                                    stage = "route_failure_preserved",
                                    platform = record.platform,
                                    message = "$reason; kept last verified route presentation",
                                    dedupeWindowMs = 500L,
                                )
                            }
                            WoltRouteFailureAction.RETRY -> {
                                currentWoltRouteRetryCount += 1
                                CaptureEventLog.append(
                                    service,
                                    stage = "route_retry_scheduled",
                                    platform = record.platform,
                                    message = "$reason; retry=$currentWoltRouteRetryCount/${LiveAdvisorRouteFailurePolicy.MAX_WOLT_RETRIES}",
                                    dedupeWindowMs = 500L,
                                )
                                mainHandler.postDelayed({
                                    if (isCurrentOffer(current) && !currentOfferHasResolvedRoute) {
                                        startRouteForOffer(service, current, preparedKey = null)
                                    }
                                }, LiveAdvisorRouteFailurePolicy.WOLT_RETRY_DELAY_MS)
                            }
                            WoltRouteFailureAction.RETAIN_UNAVAILABLE -> {
                                CaptureEventLog.append(
                                    service,
                                    stage = "route_failure_retained",
                                    platform = record.platform,
                                    message = "$reason; kept one stable advisor owner to prevent same-screen re-arm",
                                    dedupeWindowMs = 500L,
                                )
                                advisor?.updateRouteUnavailable(reason)
                            }
                            WoltRouteFailureAction.DISMISS -> {
                                CaptureEventLog.append(
                                    service,
                                    stage = "route_failure_dismissed",
                                    platform = record.platform,
                                    message = "$reason; no verified score available",
                                    dedupeWindowMs = 500L,
                                )
                                // Do not pin a useless `⚠️ 5.70 km | —/km` shell over the courier
                                // app. If no trustworthy rate survived and recovery is exhausted,
                                // removing the card is safer than presenting a dead result.
                                advisor?.suppressCurrentOffer("route unavailable without usable score: $reason", animate = false)
                            }
                        }
                    }
                }
                // Render first so the candidate cannot train the thresholds used to judge itself.
                if (comparison != null && MarketRoutePersistencePolicy.shouldPersistFullRoute(record.platform, parsed, comparison)) {
                    MarketIntelligence.onRouteResolved(
                        service,
                        current.offerId,
                        record,
                        comparison.pedestrian.getOrNull(),
                        comparison.cycleway.getOrNull(),
                    )
                } else if (comparison != null && parsed.isIncrementalOffer) {
                    CaptureEventLog.append(
                        service,
                        stage = "market_route_skipped_incremental",
                        platform = record.platform,
                        message = "Skipped incremental add-on route from canonical full-offer market samples",
                        dedupeWindowMs = 1_000L,
                    )
                } else if (comparison != null && record.platform.equals("Wolt", ignoreCase = true) &&
                    (comparison.pedestrian.isFailure || comparison.cycleway.isFailure)
                ) {
                    CaptureEventLog.append(
                        service,
                        stage = "market_route_skipped_partial",
                        platform = record.platform,
                        message = "Skipped partial Wolt route because walking+cycling pair was incomplete",
                        dedupeWindowMs = 1_000L,
                    )
                }
            }
        }
    }

    private fun resolveWoltRouteScope(context: Context, parsed: ParsedOffer): WoltRouteScope {
        val conservative = WoltIncrementalRoutePolicy.select(parsed)
        if (!parsed.isIncrementalOffer || parsed.incrementalStopCount != 2) return conservative

        val task = DeliveryLifecycleTracking.currentTask(context, CourierSignals.WOLT_PACKAGE) ?: return conservative
        if (task.state !in WOLT_BASELINE_CANDIDATE_STATES) return conservative
        val record = OfferDatabase.get(context).findById(task.offerId) ?: return conservative
        val baselineAgeMs = (System.currentTimeMillis() - record.capturedAt).coerceAtLeast(0L)
        if (baselineAgeMs > WOLT_BASELINE_MAX_AGE_MS) return conservative
        val raw = OfferParser.parse(record.rawText)
        val baseline = raw.copy(
            restaurant = record.restaurant ?: raw.restaurant,
            merchantNames = record.merchantNames.ifEmpty { raw.merchantNames },
            pickupAddresses = record.pickupAddresses.ifEmpty { raw.pickupAddresses },
            customerNames = record.customerNames.ifEmpty { raw.customerNames },
            dropoffAddresses = record.dropoffAddresses.ifEmpty { raw.dropoffAddresses },
            deliveryCount = record.deliveryCount ?: raw.deliveryCount,
        )
        val resolved = WoltIncrementalRoutePolicy.select(parsed, acceptedBaseline = baseline)
        if (resolved.kind == WoltRouteScopeKind.INCREMENTAL_DROPOFF_TAIL) {
            CaptureEventLog.append(
                context,
                stage = "route_incremental_baseline_match",
                platform = "Wolt",
                message = "Fresh baseline confirms same pickup + existing first drop-off; routing appended drop-off tail only",
                dedupeWindowMs = 2_000L,
            )
        }
        return resolved
    }

    private fun isCurrentOffer(expected: CurrentAdvisorOffer): Boolean =
        currentOffer?.offerId == expected.offerId

    /**
     * Screen discovery is a fallback, not a reason to recapture the offer already owned by the
     * live advisor. Treat sparse/recomposed views as the current offer unless they contain a clear
     * conflicting identity. Real new notifications still bypass this gate and arm normally.
     */
    fun isCurrentTrackedOfferScreen(packageName: String, parsed: ParsedOffer): Boolean {
        val currentAdvisor = advisor ?: return false
        if (!currentAdvisor.isTrackingOffer(packageName)) return false
        currentOffer?.takeIf { it.record.packageName == packageName }?.let {
            if (LiveOfferResumePolicy.hasCompatibleCoreIdentity(it.parsed, parsed)) return true
            return !currentAdvisor.isConfirmedDifferentOffer(packageName, parsed)
        }
        pendingPreview?.takeIf { it.packageName == packageName }?.let {
            if (LiveOfferResumePolicy.hasCompatibleCoreIdentity(it.parsed, parsed)) return true
            return !currentAdvisor.isConfirmedDifferentOffer(packageName, parsed)
        }
        return false
    }

    fun onForegroundWindowChanged(context: Context, packageName: String) {
        attach(context)
        advisor?.onForegroundWindowChanged(packageName)
    }

    fun onCourierWindowEvent(context: Context, packageName: String) {
        attach(context)
        advisor?.onCourierWindowEvent(packageName)
    }

    fun onOfferNotificationRemoved(packageName: String, notificationKey: String) {
        val pendingMatches = pendingPreview?.let {
            it.packageName == packageName && it.notificationKey == notificationKey
        } == true
        val currentMatches = currentOffer?.record?.let {
            it.packageName == packageName && it.captureKey == notificationKey
        } == true
        val trackedPackage = advisor?.isTrackingOffer(packageName) == true
        if (pendingMatches || currentMatches || trackedPackage) advisor?.onOfferNotificationRemoved(notificationKey)
    }

    fun observeScreen(context: Context, packageName: String, text: String) {
        attach(context)
        advisor?.onObservedCourierScreen(packageName, text)
        DeliveryLifecycleTracking.observeScreen(context, packageName, text)
    }
    private const val USER_DISMISS_TTL_MS = 3L * 60L * 1000L
    private const val WOLT_BASELINE_MAX_AGE_MS = 3L * 60L * 60L * 1000L
}
