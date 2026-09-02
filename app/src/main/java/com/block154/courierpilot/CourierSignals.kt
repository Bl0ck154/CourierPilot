package com.block154.courierpilot

import android.app.Notification
import android.content.Context
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal enum class PresenceSignal {
    ONLINE,
    OFFLINE,
    UNKNOWN,
}

internal data class AccessCodeObservation(
    val buildingKey: String,
    val displayAddress: String,
    val code: String,
)

/**
 * Small, testable classifiers shared by NotificationListenerService and AccessibilityService.
 * The rules intentionally prefer false negatives over opening a courier app for unrelated alerts.
 */
internal object CourierSignals {
    const val WOLT_PACKAGE = "com.wolt.courierapp"
    const val BOLT_PACKAGE = "com.bolt.deliverycourier"
    val courierPackages = setOf(WOLT_PACKAGE, BOLT_PACKAGE)

    private val strongOfferPhrases = listOf(
        "new task",
        "new order",
        "new delivery",
        "new offer",
        "new request",
        "incoming order",
        "incoming request",
        "order request",
        "delivery request",
        "new delivery request",
        "task available",
        "order available",
        "delivery available",
        "nauja užduotis",
        "nauja uzduotis",
        "naujas užsakymas",
        "naujas uzsakymas",
        "naujas pristatymas",
        "новый заказ",
        "новое задание",
        "нове замовлення",
        "нове завдання",
    )

    private val negativeNotificationPhrases = listOf(
        "order completed",
        "delivery completed",
        "delivery complete",
        "task completed",
        "picked up",
        "payment",
        "payout",
        "bonus",
        "campaign",
        "promotion",
        "promo code",
        "invite friends",
        "refer a friend",
        "referral",
        "survey",
        "courier news",
        "weekly summary",
        "new feature",
        "schedule",
        "customer message",
        "message from customer",
        // Lifecycle/status pushes are not new offers, even when Bolt reuses the same channel/id.
        "order is ready",
        "order ready",
        "ready for pickup",
        "ready to pick up",
        "pickup is ready",
        "marked the order as ready",
        "užsakymas paruoštas",
        "uzsakymas paruostas",
        "paruošta atsiimti",
        "paruosta atsiimti",
        "заказ готов",
        "готов к выдаче",
        "замовлення готове",
        "готове до видачі",
    )

    private val decisionPhrases = listOf(
        "accept",
        "decline",
        "reject",
        "priimti",
        "atmesti",
        "принять",
        "отклонить",
        "прийняти",
        "відхилити",
    )

    private val onlinePhrases = listOf(
        "you're online",
        "you are online",
        "go offline",
        "waiting for orders",
        "waiting for tasks",
        "looking for orders",
        "looking for tasks",
        "on duty",
        // Current Bolt Courier persistent notification while the courier is active.
        "bolt courier app is running",
        "we keep you active while app is in background",
        "laukiame užsakymų",
        "laukiame uzsakymu",
        "ieškome užsakymų",
        "ieskome uzsakymu",
        "вы онлайн",
        "ви онлайн",
    )

    private val offlinePhrases = listOf(
        "you're offline",
        "you are offline",
        "go online",
        "off duty",
        "start delivering",
        "start accepting orders",
        "start accepting tasks",
        "switch online",
        "перейти онлайн",
        "выйти онлайн",
        "вийти онлайн",
        "почати доставку",
    )

    private val codeCueRegex = Regex(
        "(?i)(door\\s*code|entry\\s*code|gate\\s*code|intercom|domofon|" +
            "dur[ųu]\\s*kod|laiptin[eė]s?\\s*kod|vart[ųu]\\s*kod|" +
            "kod\\w*\\s*(?:dur|laipt|vart)|" +
            "код\\s*(?:домоф|двер|подъезд|під.?їзд|воріт)|домофон)"
    )
    private val codeTokenRegex = Regex("(?i)[#*A-Z0-9]{2,12}")
    private val streetMarkerRegex = Regex(
        "(?i)(?:\\bg\\.|gatv[eė]|\\bpr\\.|prospekt|\\bal\\.|al[eė]ja|\\bpl\\.|plentas|" +
            "\\bskg\\.|skersgatv|\\bkel\\.|kelias|\\bst\\.|street|\\bstr\\.|улиц|\\bул\\.|\\bвул\\.)"
    )
    private val houseNumberRegex = Regex("\\b\\d{1,4}[A-Za-z]?(?:\\s*[-/]\\s*\\d{1,4})?\\b")
    private val apartmentSuffixRegex = Regex("(\\b\\d{1,4}[A-Za-z]?)\\s*[-/]\\s*\\d{1,4}\\b")
    private val trailingApartmentLabelRegex = Regex("(?i)(\\b\\d{1,4}[A-Za-z]?)\\s*[,;]?\\s*(?:butas|but\\.?|apt\\.?|apartment)\\s*#?\\s*\\d{1,4}\\b.*$")

    fun isCourierPackage(packageName: String): Boolean = packageName in courierPackages

    fun notificationText(notification: Notification): String {
        val e = notification.extras
        return buildString {
            append(e.getCharSequence(Notification.EXTRA_TITLE).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_TEXT).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_BIG_TEXT).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_SUB_TEXT).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).orEmpty())
            append(' ')
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().forEach { line ->
                append(line.orEmpty())
                append(' ')
            }
            append(notification.tickerText.orEmpty())
        }.trim()
    }

    fun notificationActionLabels(notification: Notification): List<String> =
        notification.actions.orEmpty().mapNotNull { it.title?.toString()?.trim() }.filter(String::isNotEmpty)

    fun isOfferNotification(notification: Notification): Boolean =
        isOfferNotificationText(notificationText(notification), notificationActionLabels(notification))

    fun hasStrongOfferSignal(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return strongOfferPhrases.any(lower::contains)
    }

    fun hasNegativeNotificationSignal(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return negativeNotificationPhrases.any(lower::contains)
    }

    fun hasDecisionActionSignal(actionLabels: List<String>): Boolean {
        val actions = actionLabels.joinToString(" ").lowercase(Locale.ROOT)
        return decisionPhrases.any(actions::contains)
    }

    fun isOfferNotificationText(text: String, actionLabels: List<String> = emptyList()): Boolean {
        if (hasNegativeNotificationSignal(text)) return false
        if (hasStrongOfferSignal(text)) return true

        // Text-only helper retained for tests/legacy callers. The notification listener itself uses
        // NotificationOfferClassifier, which also evaluates PendingIntent/channel/action structure.
        return hasDecisionActionSignal(actionLabels)
    }

    fun isOngoingPresenceNotification(notification: Notification): Boolean =
        notification.flags and Notification.FLAG_ONGOING_EVENT != 0 && !isOfferNotification(notification)

    fun detectPresence(text: String): PresenceSignal {
        val lower = text.lowercase(Locale.ROOT)
        if (offlinePhrases.any(lower::contains)) return PresenceSignal.OFFLINE
        if (onlinePhrases.any(lower::contains)) return PresenceSignal.ONLINE
        return PresenceSignal.UNKNOWN
    }

    fun looksLikeOfferScreen(text: String, parsed: ParsedOffer): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val hasDecision = decisionPhrases.any(lower::contains)
        val hasStrongNotificationStylePhrase = strongOfferPhrases.any(lower::contains)
        val hasWoltOfferStructure = lower.contains("delivery from") ||
            Regex("(?i)\\b\\d+\\s+deliver(?:y|ies)\\s+from\\b").containsMatchIn(text) ||
            lower.contains("expected earnings for the full delivery")
        val hasRouteEvidence = parsed.distanceMeters != null ||
            lower.contains("route distance") ||
            lower.contains("estimated")
        val hasStructuredStop = parsed.restaurant != null || parsed.dropoffAddresses.isNotEmpty()
        val hasPrice = parsed.priceCents != null

        if (lower.contains("expected earnings for the full delivery") && hasPrice) return true
        if (hasDecision && (hasPrice || hasWoltOfferStructure) && (hasRouteEvidence || hasStructuredStop || hasWoltOfferStructure)) return true
        return hasStrongNotificationStylePhrase && hasDecision && (hasPrice || hasRouteEvidence)
    }

    fun likelyAddresses(text: String): List<String> = text.lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 4..180 }
        .filter(::looksLikeAddressLine)
        .distinct()
        .toList()

    fun extractAccessCodeObservations(text: String, fallbackAddress: String? = null): List<AccessCodeObservation> {
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .toList()
        if (lines.isEmpty()) return emptyList()

        val addressesByIndex = lines.mapIndexedNotNull { index, line ->
            line.takeIf(::looksLikeAddressLine)?.let { index to it }
        }

        val out = mutableListOf<AccessCodeObservation>()
        lines.forEachIndexed { index, line ->
            val cue = codeCueRegex.find(line) ?: return@forEachIndexed
            val afterCue = line.substring(cue.range.last + 1)
            val candidates = codeCandidates(afterCue).ifEmpty { codeCandidates(line) }
            if (candidates.isEmpty()) return@forEachIndexed

            val nearestAddress = addressesByIndex
                .filter { kotlin.math.abs(it.first - index) <= 8 }
                .minByOrNull { kotlin.math.abs(it.first - index) }
                ?.second
                ?: fallbackAddress
                ?: return@forEachIndexed

            val normalized = normalizeBuildingAddress(nearestAddress) ?: return@forEachIndexed
            candidates.forEach { code ->
                out += AccessCodeObservation(normalized.first, normalized.second, code)
            }
        }
        return out.distinctBy { "${it.buildingKey}|${it.code}" }
    }

    fun normalizeBuildingAddress(raw: String): Pair<String, String>? {
        var display = raw.trim().replace(Regex("\\s+"), " ")
        if (!looksLikeAddressLine(display)) return null
        display = display.replace(Regex("(?i)^vilnius[,]?\\s*"), "")
        display = display.replace(Regex("(?i),?\\s*LT-?\\d{5}.*$"), "")
        display = display.replace(Regex("(?i),?\\s*Vilnius.*$"), "")
        display = trailingApartmentLabelRegex.replace(display) { match -> match.groupValues[1] }
        // Lithuanian courier/customer notation usually uses house-apartment, e.g. 1-36 or 1/36.
        // Building memory intentionally stores only the building part.
        display = apartmentSuffixRegex.replace(display) { match -> match.groupValues[1] }
        display = display.trim(' ', ',', ';')
        if (display.length < 4) return null

        val ascii = Normalizer.normalize(display, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        val key = ascii
            .replace(Regex("\\bgatve\\b|\\bstreet\\b|\\bstr\\b|\\bst\\b"), " g ")
            .replace(Regex("\\bprospektas\\b|\\bprospekt\\b|\\bpr\\b"), " pr ")
            .replace(Regex("\\baleja\\b|\\bal\\b"), " al ")
            .replace(Regex("\\bplentas\\b|\\bpl\\b"), " pl ")
            .replace(Regex("\\bskersgatvis\\b|\\bskersgatve\\b|\\bskg\\b"), " skg ")
            .replace(Regex("\\bkelias\\b|\\bkel\\b"), " kel ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        if (key.isBlank()) return null
        return key to display
    }

    /**
     * Semantic offer fingerprint. Unlike hashing the entire Accessibility/OCR frame, this ignores
     * dynamic ETA/spinner/UI text and uses fields that belong to the route itself. This lets a
     * notification-triggered capture and a later screen-triggered view of the same offer share one
     * identity.
     */
    fun offerFingerprint(packageName: String, parsed: ParsedOffer, text: String): String {
        val merchants = parsed.merchantNames
            .map(::identityToken)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
        val detectedAddresses = likelyAddresses(text)
            .map(::identityToken)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
        val structuredAddresses = (parsed.pickupAddresses + parsed.dropoffAddresses)
            .map(::identityToken)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
        val addresses = detectedAddresses.ifEmpty { structuredAddresses }
        val payload = buildString {
            append(packageName)
            append("|p=").append(parsed.priceCents ?: -1)
            append("|d=").append(parsed.distanceMeters ?: -1)
            append("|n=").append(parsed.deliveryCount ?: -1)
            append("|m=").append(merchants.joinToString(";"))
            append("|a=").append(addresses.joinToString(";"))
        }
        return shortHash(payload)
    }

    /** Existing callers now receive the semantic fingerprint too. */
    fun offerFingerprint(packageName: String, text: String): String =
        offerFingerprint(packageName, OfferParser.parse(text), text)

    fun hasStrongOfferIdentity(parsed: ParsedOffer, text: String): Boolean {
        if (parsed.priceCents == null) return false
        return likelyAddresses(text).isNotEmpty() ||
            (parsed.merchantNames.isNotEmpty() && parsed.distanceMeters != null)
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun looksLikeAddressLine(line: String): Boolean {
        if (!houseNumberRegex.containsMatchIn(line)) return false
        val lower = line.lowercase(Locale.ROOT)
        return streetMarkerRegex.containsMatchIn(line) || lower.contains("vilnius") || lower.contains("lt-")
    }

    private fun codeCandidates(value: String): List<String> = codeTokenRegex.findAll(value)
        .map { it.value.uppercase(Locale.ROOT).trim() }
        .filter { token -> token.count(Char::isDigit) >= 2 }
        .filterNot { token -> token.length == 4 && token.toIntOrNull() in 1900..2100 }
        .distinct()
        .take(4)
        .toList()
}

/** Prevents a still-visible offer screen from being archived again after a successful capture. */
internal object ScreenOfferDeduper {
    private const val PREFS = "courierpilot_screen_offer_dedupe"
    private const val DEDUPE_MS = 10L * 60L * 1000L

    fun shouldArm(context: Context, packageName: String, fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = packageName.replace('.', '_')
        val previous = prefs.getString("${prefix}_fingerprint", null)
        val previousAt = prefs.getLong("${prefix}_at", 0L)
        return previous != fingerprint || now - previousAt > DEDUPE_MS
    }

    fun markArmed(context: Context, packageName: String, fingerprint: String, now: Long = System.currentTimeMillis()) {
        val prefix = packageName.replace('.', '_')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("${prefix}_fingerprint", fingerprint)
            .putLong("${prefix}_at", now)
            .apply()
    }
}
