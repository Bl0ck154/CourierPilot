package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
    )

    private data class PendingAdvisorOffer(
        val key: String,
        val packageName: String,
        val armedAt: Long,
        val parsed: ParsedOffer,
    )

    private var serviceRef = WeakReference<AccessibilityService>(null)
    private var advisor: StableLiveOfferAdvisor? = null
    private var currentOffer: CurrentAdvisorOffer? = null
    private var pendingPreview: PendingAdvisorOffer? = null
    private var captureOfferKey: String? = null

    fun attach(context: Context) {
        val service = context as? AccessibilityService ?: return
        if (serviceRef.get() === service && advisor != null) return
        advisor?.destroy()
        serviceRef = WeakReference(service)
        advisor = StableLiveOfferAdvisor(service, routeToggle@ { platform, enabled ->
            if (!enabled) return@routeToggle
            val current = currentOffer ?: return@routeToggle
            if (!current.record.platform.equals(platform, ignoreCase = true)) return@routeToggle
            startRouteForOffer(service, current, preparedKey = null)
        })
    }

    /**
     * Hide the previous offer exactly once when a genuinely new capture transaction starts. Repeated
     * observations for the same pending offer must not kill the progressive preview we show later.
     */
    fun hideForCapture(context: Context, pending: PendingOffer) {
        attach(context)
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (captureOfferKey == key) return
        captureOfferKey = key
        pendingPreview = null
        currentOffer = null
        advisor?.suppressCurrentOffer("new offer capture started", animate = false)
    }

    fun showPendingOffer(context: Context, pending: PendingOffer, parsed: ParsedOffer) {
        attach(context)
        val service = serviceRef.get() ?: return
        val platform = OfferState.platformLabel(pending.packageName)
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        pendingPreview = PendingAdvisorOffer(key, pending.packageName, pending.armedAt, parsed)
        advisor?.showPending(platform, parsed)

        // Bolt address geocoding is independent from price persistence. Start it as soon as the
        // offer card exposes pickup addresses so the live route can usually hit the in-memory cache
        // once the priced offer is committed.
        if (pending.packageName == CourierSignals.BOLT_PACKAGE && parsed.pickupAddresses.isNotEmpty()) {
            RouteResearchGeocoder.prewarm(service, parsed.pickupAddresses)
        }

        if (pending.packageName == CourierSignals.WOLT_PACKAGE) {
            val started = AutomaticWoltRouteCoordinator.prepare(service, key, parsed) { prepared ->
                val active = pendingPreview
                if (active?.key != key || active.packageName != pending.packageName) return@prepare
                val comparison = prepared.comparison ?: return@prepare
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
                        "avg_m=${average ?: -1}; direct_chain_m=${prepared.directChainMeters ?: -1}; " +
                        "gps_age_ms=${prepared.locationAgeMillis ?: -1}; gps_accuracy_m=${prepared.locationAccuracyMeters ?: -1f}",
                    dedupeWindowMs = 500L,
                )
                advisor?.updateRoute(comparison, prepared.waypoints.size)
            }
            if (started) {
                CaptureEventLog.append(
                    service,
                    stage = "route_prepare_start",
                    platform = platform,
                    message = "Full Wolt route preparation started before price",
                    dedupeWindowMs = 10_000L,
                )
            }
        }
    }

    fun setCaptureSuppressed(context: Context, suppressed: Boolean) {
        attach(context)
        advisor?.setCaptureSuppressed(suppressed)
    }

    fun onOfferPersisted(offerId: Long, record: OfferRecord) {
        val service = serviceRef.get() ?: return
        val currentAdvisor = advisor ?: return
        // Reparse the same captured screen text here so the sequential Timeline stop order remains
        // available to the router without changing the stable offer-history DB schema.
        val parsedFromScreen = OfferParser.parse(record.rawText)
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

        val preparedKey = pendingPreview?.key ?: captureOfferKey
        val current = CurrentAdvisorOffer(offerId, record, parsed, supplementalBoltPickups)
        currentOffer = current
        pendingPreview = null
        captureOfferKey = null

        DeliveryLifecycleTracking.onOfferCaptured(service, record.packageName, offerId, record.capturedAt)

        // The card shell is rendered synchronously before any route/geocoder work starts. Routing
        // only updates rows inside this already-visible card; it never controls whether the card exists.
        currentAdvisor.showBase(record.platform, parsed)
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
                preparedKey = preparedKey,
            ) { outcome ->
                val comparison = outcome.comparison
                // Render first so the candidate cannot train the thresholds used to judge itself.
                if (isCurrentOffer(current)) {
                    if (comparison != null) advisor?.updateRoute(comparison, outcome.waypoints.size)
                    else advisor?.updateRouteUnavailable(outcome.failureReason ?: "unknown failure")
                }
                if (comparison != null) {
                    MarketIntelligence.onRouteResolved(
                        service,
                        current.offerId,
                        record,
                        comparison.pedestrian.getOrNull(),
                        comparison.cycleway.getOrNull(),
                    )
                }
            }
        }
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
            return !currentAdvisor.isConfirmedDifferentOffer(packageName, parsed)
        }
        pendingPreview?.takeIf { it.packageName == packageName }?.let {
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

    fun observeScreen(context: Context, packageName: String, text: String) {
        attach(context)
        advisor?.onCourierWindowEvent(packageName)
        DeliveryLifecycleTracking.observeScreen(context, packageName, text)
    }
}
