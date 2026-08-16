package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import java.lang.ref.WeakReference

/**
 * Process-local bridge between the Accessibility service and post-persistence helpers. The database
 * remains usable without an attached service; advisor work simply becomes a no-op in that case.
 */
internal object LiveAdvisorHub {
    private var serviceRef = WeakReference<AccessibilityService>(null)
    private var advisor: LiveOfferAdvisor? = null

    fun attach(context: Context) {
        val service = context as? AccessibilityService ?: return
        if (serviceRef.get() === service && advisor != null) return
        advisor?.destroy()
        serviceRef = WeakReference(service)
        advisor = LiveOfferAdvisor(service)
    }

    /** Hide an older card while CourierPilot is collecting the clean screenshot for a new offer. */
    fun hideForCapture(context: Context) {
        attach(context)
        advisor?.hide()
    }

    fun onOfferPersisted(offerId: Long, record: OfferRecord) {
        val service = serviceRef.get() ?: return
        val currentAdvisor = advisor ?: return
        // Reparse the same captured screen text here so the sequential Timeline stop order remains
        // available to the router without changing the stable offer-history DB schema.
        val parsedFromScreen = OfferParser.parse(record.rawText)
        val parsed = parsedFromScreen.copy(
            priceCents = record.priceCents,
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

        DeliveryLifecycleTracking.onOfferCaptured(service, record.packageName, offerId, record.capturedAt)

        // With experimental Bolt routing enabled, preserve a clean research bundle automatically.
        // The bitmap comes from the already-persisted proof screenshot, so the advisor can never
        // contaminate the map image with its own overlay.
        if (record.platform.equals("Bolt", ignoreCase = true) && LiveAdvisorSettings.automaticBoltRouting(service)) {
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
        }

        currentAdvisor.showBase(record.platform, parsed)

        AutomaticWoltRouteCoordinator.start(service, offerId, record.platform, parsed) { outcome ->
            val comparison = outcome.comparison
            if (comparison != null) advisor?.updateRoute(comparison, outcome.waypoints.size)
            else advisor?.updateRouteUnavailable(outcome.failureReason ?: "unknown failure")
        }

        AutomaticBoltRouteCoordinator.start(service, offerId, record.platform, parsed) { outcome ->
            advisor?.updateBoltRoute(outcome)
        }
    }

    fun observeScreen(context: Context, packageName: String, text: String) {
        attach(context)
        DeliveryLifecycleTracking.observeScreen(context, packageName, text)
    }
}
