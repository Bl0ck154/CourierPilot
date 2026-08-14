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
    private var advisorRef = WeakReference<LiveOfferAdvisor>(null)

    fun attach(context: Context) {
        val service = context as? AccessibilityService ?: return
        if (serviceRef.get() === service && advisorRef.get() != null) return
        advisorRef.get()?.destroy()
        val advisor = LiveOfferAdvisor(service)
        serviceRef = WeakReference(service)
        advisorRef = WeakReference(advisor)
    }

    /** Hide an older card while CourierPilot is collecting the clean screenshot for a new offer. */
    fun hideForCapture(context: Context) {
        attach(context)
        advisorRef.get()?.hide()
    }

    fun onOfferPersisted(offerId: Long, record: OfferRecord) {
        val service = serviceRef.get() ?: return
        val advisor = advisorRef.get() ?: return
        val parsed = ParsedOffer(
            priceCents = record.priceCents,
            distanceMeters = record.distanceMeters,
            restaurant = record.restaurant,
            merchantNames = record.merchantNames,
            pickupAddresses = record.pickupAddresses,
            customerNames = record.customerNames,
            dropoffAddresses = record.dropoffAddresses,
            deliveryCount = record.deliveryCount,
            estimatedMinutesMin = record.estimatedMinutesMin,
            estimatedMinutesMax = record.estimatedMinutesMax,
        )

        DeliveryLifecycleTracking.onOfferCaptured(service, record.packageName, offerId, record.capturedAt)
        advisor.showBase(record.platform, parsed)
        AutomaticWoltRouteCoordinator.start(service, offerId, record.platform, parsed) { outcome ->
            val comparison = outcome.comparison
            if (comparison != null) advisorRef.get()?.updateRoute(comparison, outcome.waypoints.size)
            else advisorRef.get()?.updateRouteUnavailable(outcome.failureReason ?: "unknown failure")
        }
    }

    fun observeScreen(context: Context, packageName: String, text: String) {
        attach(context)
        DeliveryLifecycleTracking.observeScreen(context, packageName, text)
    }
}
