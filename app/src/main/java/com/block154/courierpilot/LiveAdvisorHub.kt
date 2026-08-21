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

    private var serviceRef = WeakReference<AccessibilityService>(null)
    private var advisor: LiveOfferAdvisor? = null
    private var currentOffer: CurrentAdvisorOffer? = null

    fun attach(context: Context) {
        val service = context as? AccessibilityService ?: return
        if (serviceRef.get() === service && advisor != null) return
        advisor?.destroy()
        serviceRef = WeakReference(service)
        advisor = LiveOfferAdvisor(service) { platform, enabled ->
            if (!enabled) return@LiveOfferAdvisor
            val current = currentOffer ?: return@LiveOfferAdvisor
            if (!current.record.platform.equals(platform, ignoreCase = true)) return@LiveOfferAdvisor
            startRouteForOffer(service, current)
        }
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

        val current = CurrentAdvisorOffer(offerId, record, parsed, supplementalBoltPickups)
        currentOffer = current

        DeliveryLifecycleTracking.onOfferCaptured(service, record.packageName, offerId, record.capturedAt)
        currentAdvisor.showBase(record.platform, parsed)
        startRouteForOffer(service, current)
    }

    private fun startRouteForOffer(service: AccessibilityService, current: CurrentAdvisorOffer) {
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
                advisor?.updateBoltRoute(outcome)
            }
            return
        }

        if (record.platform.equals("Wolt", ignoreCase = true)) {
            AutomaticWoltRouteCoordinator.start(service, current.offerId, record.platform, parsed) { outcome ->
                val comparison = outcome.comparison
                if (comparison != null) advisor?.updateRoute(comparison, outcome.waypoints.size)
                else advisor?.updateRouteUnavailable(outcome.failureReason ?: "unknown failure")
            }
        }
    }

    fun observeScreen(context: Context, packageName: String, text: String) {
        attach(context)
        DeliveryLifecycleTracking.observeScreen(context, packageName, text)
    }
}
