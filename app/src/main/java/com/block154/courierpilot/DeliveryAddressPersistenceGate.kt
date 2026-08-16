package com.block154.courierpilot

import android.content.Context
import java.util.Locale

internal enum class AddressPersistenceReason {
    CUSTOMER_DETAILS,
    POST_PICKUP,
    PICKUP_SCREEN,
    NO_CUSTOMER_CONTEXT,
}

internal data class AddressPersistenceDecision(
    val allowed: Boolean,
    val reason: AddressPersistenceReason,
)

/** Hard boundary between transient courier UI and durable customer-address memory. */
internal object DeliveryAddressPersistenceGate {

    fun evaluate(
        context: Context,
        packageName: String,
        text: String,
        details: DeliveryScreenDetails? = DeliveryScreenDetailsExtractor.extractForPlatform(packageName, text),
    ): AddressPersistenceDecision = evaluatePlatform(
        packageName = packageName,
        text = text,
        details = details,
        lifecycleState = DeliveryLifecycleTracking.currentState(context, packageName),
    )

    /** Compatibility entry point used by existing generic tests. */
    internal fun evaluate(
        text: String,
        details: DeliveryScreenDetails?,
        lifecycleState: DeliveryEventType?,
    ): AddressPersistenceDecision = evaluatePlatform("", text, details, lifecycleState)

    internal fun evaluatePlatform(
        packageName: String,
        text: String,
        details: DeliveryScreenDetails?,
        lifecycleState: DeliveryEventType?,
    ): AddressPersistenceDecision {
        if (text.isBlank()) return denied(AddressPersistenceReason.NO_CUSTOMER_CONTEXT)
        val lower = text.lowercase(Locale.ROOT).replace('’', '\'')

        if (packageName == CourierSignals.WOLT_PACKAGE) {
            // Real Wolt merchant sheet: `Pickup from ...` + restaurant address + `I've got the items`.
            if (lower.contains("pickup from") || WOLT_PICKUP_CUES.any(lower::contains)) {
                return denied(AddressPersistenceReason.PICKUP_SCREEN)
            }
            // Real Wolt customer sheet: `Dropoff to` followed by recipient and street address.
            if (lower.contains("dropoff to") && details?.address != null) {
                return allowed(AddressPersistenceReason.CUSTOMER_DETAILS)
            }
            // Do not fall back to broad words+number guessing for Wolt. The supplied real UI has a
            // stable customer marker, and failing closed is safer than learning a restaurant/item.
            return denied(AddressPersistenceReason.NO_CUSTOMER_CONTEXT)
        }

        if (packageName == CourierSignals.BOLT_PACKAGE) {
            // Real Bolt merchant sheet exposes `Order is ready for pickup`, arrival time and items.
            if (BOLT_PICKUP_CUES.any(lower::contains)) {
                return denied(AddressPersistenceReason.PICKUP_SCREEN)
            }
            // Real Bolt customer sheet exposes an explicit Address plus customer-only detail fields.
            if (details?.address != null && hasCustomerDetailEvidence(lower, details)) {
                return allowed(AddressPersistenceReason.CUSTOMER_DETAILS)
            }
            return denied(AddressPersistenceReason.NO_CUSTOMER_CONTEXT)
        }

        // Conservative generic fallback for unknown/changed courier layouts.
        val explicitAddress = details?.address ?: DeliveryScreenDetailsExtractor.addressValue(text)
        if (explicitAddress != null && hasCustomerDetailEvidence(lower, details)) {
            return allowed(AddressPersistenceReason.CUSTOMER_DETAILS)
        }
        if (GENERIC_PICKUP_CUES.any(lower::contains)) return denied(AddressPersistenceReason.PICKUP_SCREEN)
        if (lifecycleState == DeliveryEventType.PICKED_UP || lifecycleState == DeliveryEventType.ARRIVED_DROPOFF) {
            return allowed(AddressPersistenceReason.POST_PICKUP)
        }
        return denied(AddressPersistenceReason.NO_CUSTOMER_CONTEXT)
    }

    private fun allowed(reason: AddressPersistenceReason) = AddressPersistenceDecision(true, reason)
    private fun denied(reason: AddressPersistenceReason) = AddressPersistenceDecision(false, reason)

    private fun hasCustomerDetailEvidence(lower: String, details: DeliveryScreenDetails?): Boolean {
        if (details?.additionalNote?.isNotBlank() == true) return true
        if (details?.apartment?.isNotBlank() == true) return true
        if (details?.entryCode?.isNotBlank() == true) return true
        if (details?.floor?.isNotBlank() == true) return true
        if (details?.buildingName?.isNotBlank() == true) return true
        if (details?.deliverTo?.isNotBlank() == true) return true

        val instructions = details?.instructions.orEmpty().lowercase(Locale.ROOT)
        if (CUSTOMER_INSTRUCTION_CUES.any(instructions::contains)) return true
        if (CUSTOMER_DETAIL_CUES.any(lower::contains)) return true
        if (DELIVERY_ACTION_CUES.any(lower::contains)) return true
        return false
    }

    private val WOLT_PICKUP_CUES = listOf(
        "i've got the items",
        "ive got the items",
        "order ready",
        "delayed? mark the order as late",
        "cancel this delivery",
    )

    private val BOLT_PICKUP_CUES = listOf(
        "order is ready for pickup",
        "arrive in ",
        "report an issue",
        "order details",
    )

    private val GENERIC_PICKUP_CUES = listOf(
        "confirm pickup", "confirm pick up", "slide to pick up", "swipe to pick up",
        "arrived at pickup", "at pickup location", "pickup instructions", "ready for pickup",
        "order ready", "collect order", "pick up order", "restaurant is preparing",
        "waiting at restaurant", "patvirtinti paėmimą", "patvirtinti paemima",
        "atsiimti užsakymą", "atsiimti uzsakyma", "забрать заказ", "забрати замовлення",
    )

    private val CUSTOMER_DETAIL_CUES = listOf(
        "additional note", "apartment, flat or suite number", "apartment number", "door code",
        "entry code", "entrance code", "intercom", "staircase", "delivery issues?",
        "pristatymo instrukcijos", "papildoma pastaba", "durų kodas", "duru kodas",
        "domofon", "код домофона", "код дверей",
    )

    private val CUSTOMER_INSTRUCTION_CUES = listOf(
        "leave at", "meet at", "meet outside", "door", "entrance", "gate", "intercom",
        "ring", "bell", "call me", "hand it", "palik", "susitik", "dur", "vart", "skamb",
        "залиш", "двер", "подъезд", "під'їзд",
    )

    private val DELIVERY_ACTION_CUES = listOf(
        "slide to confirm", "swipe to confirm", "mark delivered", "complete delivery",
        "finish delivery", "delivery issues?", "arrived at customer", "at customer location",
        "age verified", "pristatymo problemos", "patvirtinti pristatymą", "patvirtinti pristatyma",
        "доставлено", "підтвердити доставку",
    )
}
