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

/**
 * Hard boundary between transient courier UI text and durable address memory.
 *
 * Accessibility exposes text from every stage of an order: the offer, restaurant, item list,
 * pickup controls and finally the customer. Address memory is useful only for the customer side.
 * A broad "words + number" detector therefore must never be enough on its own to persist data.
 */
internal object DeliveryAddressPersistenceGate {

    fun evaluate(
        context: Context,
        packageName: String,
        text: String,
        details: DeliveryScreenDetails? = DeliveryScreenDetailsExtractor.extract(text),
    ): AddressPersistenceDecision = evaluate(
        text = text,
        details = details,
        lifecycleState = DeliveryLifecycleTracking.currentState(context, packageName),
    )

    internal fun evaluate(
        text: String,
        details: DeliveryScreenDetails?,
        lifecycleState: DeliveryEventType?,
    ): AddressPersistenceDecision {
        if (text.isBlank()) {
            return AddressPersistenceDecision(false, AddressPersistenceReason.NO_CUSTOMER_CONTEXT)
        }

        val lower = text.lowercase(Locale.ROOT).replace('’', '\'')
        val explicitAddress = details?.address ?: DeliveryScreenDetailsExtractor.addressValue(text)

        // A real delivery-detail sheet can be trusted even if lifecycle tracking missed the exact
        // pickup transition. Require an Address field plus customer-only metadata/actions; an
        // Address label by itself is not enough because restaurant sheets can expose one too.
        if (explicitAddress != null && hasCustomerDetailEvidence(lower, details)) {
            return AddressPersistenceDecision(true, AddressPersistenceReason.CUSTOMER_DETAILS)
        }

        // Explicit pickup actions override lifecycle fallback. This prevents a stale PICKED_UP state
        // (or manual navigation back to merchant details) from teaching restaurant/menu text.
        if (PICKUP_SCREEN_CUES.any(lower::contains)) {
            return AddressPersistenceDecision(false, AddressPersistenceReason.PICKUP_SCREEN)
        }

        // Once pickup is explicitly confirmed, the courier app normally transitions to the customer
        // route. This fallback supports compact customer screens that do not expose an Address label.
        if (lifecycleState == DeliveryEventType.PICKED_UP || lifecycleState == DeliveryEventType.ARRIVED_DROPOFF) {
            return AddressPersistenceDecision(true, AddressPersistenceReason.POST_PICKUP)
        }

        return AddressPersistenceDecision(false, AddressPersistenceReason.NO_CUSTOMER_CONTEXT)
    }

    private fun hasCustomerDetailEvidence(lower: String, details: DeliveryScreenDetails?): Boolean {
        if (details?.additionalNote?.isNotBlank() == true) return true
        if (details?.apartment?.isNotBlank() == true) return true
        if (details?.floor?.isNotBlank() == true) return true

        val instructions = details?.instructions.orEmpty().lowercase(Locale.ROOT)
        if (CUSTOMER_INSTRUCTION_CUES.any(instructions::contains)) return true
        if (CUSTOMER_DETAIL_CUES.any(lower::contains)) return true
        if (DELIVERY_ACTION_CUES.any(lower::contains)) return true
        return false
    }

    private val PICKUP_SCREEN_CUES = listOf(
        "confirm pickup",
        "confirm pick up",
        "slide to pick up",
        "swipe to pick up",
        "arrived at pickup",
        "at pickup location",
        "pickup instructions",
        "ready for pickup",
        "order ready",
        "collect order",
        "pick up order",
        "restaurant is preparing",
        "waiting at restaurant",
        "patvirtinti paėmimą",
        "patvirtinti paemima",
        "atsiimti užsakymą",
        "atsiimti uzsakyma",
        "забрать заказ",
        "забрати замовлення",
    )

    private val CUSTOMER_DETAIL_CUES = listOf(
        "additional note",
        "apartment, flat or suite number",
        "apartment number",
        "door code",
        "entrance code",
        "intercom",
        "staircase",
        "delivery issues?",
        "pristatymo instrukcijos",
        "papildoma pastaba",
        "durų kodas",
        "duru kodas",
        "domofon",
        "код домофона",
        "код дверей",
        "код дверей",
    )

    private val CUSTOMER_INSTRUCTION_CUES = listOf(
        "leave at",
        "meet at",
        "meet outside",
        "door",
        "entrance",
        "gate",
        "intercom",
        "ring",
        "bell",
        "call me",
        "hand it",
        "palik",
        "susitik",
        "dur",
        "vart",
        "skamb",
        "залиш",
        "двер",
        "подъезд",
        "під'їзд",
    )

    private val DELIVERY_ACTION_CUES = listOf(
        "slide to confirm",
        "swipe to confirm",
        "mark delivered",
        "complete delivery",
        "finish delivery",
        "delivery issues?",
        "arrived at customer",
        "at customer location",
        "pristatymo problemos",
        "patvirtinti pristatymą",
        "patvirtinti pristatyma",
        "доставлено",
        "підтвердити доставку",
    )
}
