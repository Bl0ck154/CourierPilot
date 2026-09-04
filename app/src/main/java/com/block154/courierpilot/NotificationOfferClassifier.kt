package com.block154.courierpilot

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

internal enum class PendingIntentKind {
    NONE,
    ACTIVITY,
    BROADCAST,
    SERVICE,
    FOREGROUND_SERVICE,
    UNKNOWN,
}

/**
 * Structural description of a courier notification. Text values are intentionally excluded: the
 * point is to survive wording/localisation changes and learn the stable shape of real offer pushes.
 */
internal data class NotificationStructure(
    val packageName: String,
    val channelId: String = "",
    val category: String = "",
    val flags: Int = 0,
    val contentIntentPresent: Boolean = false,
    val contentIntentCreatorPackage: String = "",
    val contentIntentKind: PendingIntentKind = PendingIntentKind.NONE,
    val actionCount: Int = 0,
    val actionIntentCount: Int = 0,
    val sameCreatorActionIntentCount: Int = 0,
    val semanticActions: List<Int> = emptyList(),
    val hasFullScreenIntent: Boolean = false,
    val groupSummary: Boolean = false,
    val extrasKeys: Set<String> = emptySet(),
    val notificationId: Int = 0,
    val tag: String = "",
    val observedAt: Long = System.currentTimeMillis(),
) {
    val ongoing: Boolean get() = flags and Notification.FLAG_ONGOING_EVENT != 0
    val contentCreatorMatchesApp: Boolean get() =
        contentIntentPresent && contentIntentCreatorPackage == packageName
}

internal data class OfferNotificationDecision(
    val isOffer: Boolean,
    val score: Int,
    val reasons: List<String>,
    val learnedMatchScore: Int = 0,
)

/**
 * Offer classification is deliberately evidence-based instead of title-based.
 *
 * Automatic opening stays fail-closed for visible copy: explicit incoming-order wording or the
 * distinctive accept + decline/reject action pair is required. The one narrow adaptive exception is
 * a completely textless transient notification that exactly matches a previously screen-confirmed
 * offer profile; this recovers real Bolt/Wolt pushes whose visible text intermittently disappears.
 */
internal object NotificationOfferClassifier {
    internal const val LEARNED_PROFILE_MATCH_THRESHOLD = 7

    fun classify(context: Context, sbn: StatusBarNotification): OfferNotificationDecision {
        val structure = NotificationOfferProfileStore.snapshot(sbn)
        val learned = NotificationOfferProfileStore.confirmedProfiles(context, sbn.packageName)
        return classify(
            structure = structure,
            text = CourierSignals.notificationText(sbn.notification),
            actionLabels = CourierSignals.notificationActionLabels(sbn.notification),
            learnedProfiles = learned,
        )
    }

    internal fun classify(
        structure: NotificationStructure,
        text: String,
        actionLabels: List<String>,
        learnedProfiles: List<NotificationStructure> = emptyList(),
    ): OfferNotificationDecision {
        if (!CourierSignals.isCourierPackage(structure.packageName)) {
            return OfferNotificationDecision(false, -100, listOf("not_courier_package"))
        }

        if (CourierSignals.hasNegativeNotificationSignal(text)) {
            return OfferNotificationDecision(false, -100, listOf("negative_delivery_state"))
        }

        // Presence/status notifications are a hard negative, not merely a weak hint. Real-device
        // Bolt uses the persistent "Bolt Courier app is running" notification while online. It must
        // never auto-open the app even if an OEM mutates its flags/actions to resemble an order push.
        if (CourierSignals.detectPresence(text) != PresenceSignal.UNKNOWN) {
            return OfferNotificationDecision(false, -100, listOf("presence_notification"))
        }

        val strongOfferText = CourierSignals.hasStrongOfferSignal(text)
        val decisionPair = CourierSignals.hasOfferDecisionPair(actionLabels)
        val anyDecisionAction = CourierSignals.hasDecisionActionSignal(actionLabels)

        var score = 0
        val reasons = mutableListOf<String>()

        if (strongOfferText) {
            score += 8
            reasons += "strong_offer_text"
        }
        if (decisionPair) {
            score += 8
            reasons += "decision_pair"
        } else if (anyDecisionAction) {
            reasons += "single_decision_action"
        }

        // Structural fields are still scored and logged because they are useful diagnostics, but they
        // are never enough for visible unrelated copy.
        if (!structure.ongoing) {
            score += 1
            reasons += "transient"
        }
        if (structure.contentCreatorMatchesApp) {
            score += 2
            reasons += "content_intent_creator"
        }
        if (structure.contentIntentKind == PendingIntentKind.ACTIVITY) {
            score += 1
            reasons += "activity_intent"
        }
        val hasTwoAppActions =
            structure.actionCount >= 2 &&
                structure.actionIntentCount >= 2 &&
                structure.sameCreatorActionIntentCount >= 2
        if (hasTwoAppActions) {
            score += 4
            reasons += "two_app_actions"
        }
        if (structure.hasFullScreenIntent) {
            score += 1
            reasons += "full_screen_intent"
        }

        val learnedMatch = learnedProfiles.maxOfOrNull { profileMatchScore(structure, it) } ?: 0
        val learnedProfileMatched = learnedMatch >= LEARNED_PROFILE_MATCH_THRESHOLD
        if (learnedProfileMatched) {
            score += 7
            reasons += "learned_profile:$learnedMatch"
        }

        val learnedTextlessOffer =
            learnedProfileMatched &&
                text.isBlank() &&
                structure.contentIntentKind == PendingIntentKind.ACTIVITY
        if (learnedTextlessOffer) {
            reasons += "learned_textless_offer"
        } else if (learnedProfileMatched && !strongOfferText && !decisionPair) {
            reasons += "learned_profile_diagnostic_only"
        }

        // Fail closed for everything except explicit order evidence or the narrow textless learned
        // profile above. Also reject group summaries, ongoing status notifications, and notifications
        // whose tap intent is not owned by the courier app.
        val strictOffer =
            !structure.ongoing &&
                !structure.groupSummary &&
                structure.contentCreatorMatchesApp &&
                (strongOfferText || decisionPair || learnedTextlessOffer)

        if (!strictOffer) {
            if (hasTwoAppActions && !strongOfferText && !decisionPair) reasons += "structure_diagnostic_only"
            if (structure.ongoing) reasons += "ongoing_guard"
            if (structure.groupSummary) reasons += "group_summary_guard"
            if (!structure.contentCreatorMatchesApp) reasons += "app_content_intent_guard"
        }

        return OfferNotificationDecision(
            strictOffer,
            score,
            reasons,
            learnedMatch,
        )
    }

    internal fun profileMatchScore(current: NotificationStructure, profile: NotificationStructure): Int {
        if (current.packageName != profile.packageName) return 0

        val sameChannel = current.channelId.isNotBlank() && current.channelId == profile.channelId
        val sameCategory = current.category.isNotBlank() && current.category == profile.category
        val sameNotificationId = current.notificationId == profile.notificationId
        val sameActionShape = current.actionCount >= 2 &&
            current.actionCount == profile.actionCount &&
            current.sameCreatorActionIntentCount >= 2 &&
            profile.sameCreatorActionIntentCount >= 2
        val sameNamedTag = current.tag.isNotBlank() && current.tag == profile.tag
        // Android framework keys (android.title/android.text/etc.) exist on almost every push and
        // must not contribute to notification identity. Only app/custom extras carry structural value.
        val currentCustomExtras = current.extrasKeys.filterNot { it.startsWith("android.") }.toSet()
        val profileCustomExtras = profile.extrasKeys.filterNot { it.startsWith("android.") }.toSet()
        val union = currentCustomExtras union profileCustomExtras
        val extrasOverlap = if (union.isEmpty()) 0.0
            else (currentCustomExtras intersect profileCustomExtras).size.toDouble() / union.size

        // One reusable field must never teach unrelated pushes. Require a pair of app-controlled
        // anchors (or the distinctive two-action shape) before scoring a learned profile.
        val highExtrasOverlap = extrasOverlap >= 0.80
        val structurallyAnchored = sameActionShape ||
            (sameChannel && (sameNotificationId || sameNamedTag || sameCategory || highExtrasOverlap)) ||
            (sameNamedTag && (sameNotificationId || sameCategory || highExtrasOverlap)) ||
            (sameNotificationId && sameCategory && highExtrasOverlap)
        if (!structurallyAnchored) return 0

        var score = 0
        if (sameChannel) score += 4
        if (sameNotificationId) score += 2
        if (sameActionShape) score += 3
        if (sameNamedTag) score += 2
        if (sameCategory) score += 1
        if (current.contentCreatorMatchesApp && profile.contentCreatorMatchesApp) score += 2
        if (
            current.contentIntentKind != PendingIntentKind.NONE &&
            current.contentIntentKind == profile.contentIntentKind
        ) score += 2
        if (current.actionCount == profile.actionCount) score += 1
        if (
            current.semanticActions.isNotEmpty() &&
            current.semanticActions == profile.semanticActions
        ) score += 1
        if (current.ongoing == profile.ongoing) score += 1
        if (current.groupSummary == profile.groupSummary) score += 1
        if (extrasOverlap >= 0.60) score += 1
        return score
    }
}

/**
 * Learns only from notifications already identified as offers and subsequently confirmed on screen.
 * The profile is tied to the exact StatusBarNotification key, so an unrelated push arriving nearby
 * in time cannot poison the learned offer shape.
 */
internal object NotificationOfferProfileStore {
    private const val PREFS = "courier_offer_notification_profiles"
    private const val PROFILE_PREFIX = "profiles_"
    private const val CANDIDATE_PREFIX = "candidate_"
    private const val MAX_PROFILES = 6
    private const val CANDIDATE_TTL_MS = 2L * 60L * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberCandidate(context: Context, sbn: StatusBarNotification): NotificationStructure {
        val structure = snapshot(sbn)
        val payload = structure.toJson().apply { put("notificationKey", sbn.key) }.toString()
        prefs(context).edit()
            .putString(candidateKey(sbn.packageName, sbn.key), payload)
            .apply()
        return structure
    }

    fun confirmCandidate(context: Context, packageName: String, notificationKey: String): Boolean {
        if (notificationKey.isBlank() || notificationKey.startsWith("screen:")) return false
        val p = prefs(context)
        val key = candidateKey(packageName, notificationKey)
        val raw = p.getString(key, null) ?: return false
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val structure = runCatching { structureFromJson(obj) }.getOrNull() ?: return false
        if (structure.packageName != packageName || isExpired(structure)) return false
        storeConfirmedProfile(context, structure)
        p.edit().remove(key).apply()
        return true
    }

    fun confirmedProfiles(context: Context, packageName: String): List<NotificationStructure> {
        val raw = prefs(context).getString(PROFILE_PREFIX + packageName, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(structureFromJson(obj))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun storeConfirmedProfile(context: Context, structure: NotificationStructure) {
        val existing = confirmedProfiles(context, structure.packageName).toMutableList()
        val signature = profileSignature(structure)
        existing.removeAll { profileSignature(it) == signature }
        existing.add(0, structure.copy(observedAt = System.currentTimeMillis()))
        val trimmed = existing.take(MAX_PROFILES)
        val array = JSONArray()
        trimmed.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(PROFILE_PREFIX + structure.packageName, array.toString()).apply()
    }

    internal fun snapshot(sbn: StatusBarNotification): NotificationStructure {
        val n = sbn.notification
        val actions = n.actions.orEmpty()
        val actionIntents = actions.mapNotNull { it.actionIntent }
        val creatorMatches = actionIntents.count { it.creatorPackage == sbn.packageName }
        return NotificationStructure(
            packageName = sbn.packageName,
            channelId = n.channelId.orEmpty(),
            category = n.category.orEmpty(),
            flags = n.flags,
            contentIntentPresent = n.contentIntent != null,
            contentIntentCreatorPackage = n.contentIntent?.creatorPackage.orEmpty(),
            contentIntentKind = pendingIntentKind(n.contentIntent),
            actionCount = actions.size,
            actionIntentCount = actionIntents.size,
            sameCreatorActionIntentCount = creatorMatches,
            semanticActions = actions.map { it.semanticAction },
            hasFullScreenIntent = n.fullScreenIntent != null,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            extrasKeys = safeExtrasKeys(n.extras),
            notificationId = sbn.id,
            tag = sbn.tag.orEmpty(),
            observedAt = System.currentTimeMillis(),
        )
    }

    private fun pendingIntentKind(intent: PendingIntent?): PendingIntentKind = when {
        intent == null -> PendingIntentKind.NONE
        intent.isActivity -> PendingIntentKind.ACTIVITY
        intent.isBroadcast -> PendingIntentKind.BROADCAST
        intent.isForegroundService -> PendingIntentKind.FOREGROUND_SERVICE
        intent.isService -> PendingIntentKind.SERVICE
        else -> PendingIntentKind.UNKNOWN
    }

    private fun safeExtrasKeys(extras: Bundle?): Set<String> =
        runCatching { extras?.keySet()?.toSet().orEmpty() }.getOrDefault(emptySet())

    private fun candidateKey(packageName: String, notificationKey: String): String =
        "$CANDIDATE_PREFIX$packageName:${notificationKey.hashCode().toUInt().toString(16)}"

    private fun isExpired(structure: NotificationStructure): Boolean =
        System.currentTimeMillis() - structure.observedAt > CANDIDATE_TTL_MS

    private fun profileSignature(s: NotificationStructure): String = listOf(
        s.packageName,
        s.channelId,
        s.category,
        s.contentIntentCreatorPackage,
        s.contentIntentKind.name,
        s.actionCount.toString(),
        s.semanticActions.joinToString(","),
        s.ongoing.toString(),
        s.groupSummary.toString(),
        s.notificationId.toString(),
        s.tag,
        s.extrasKeys.sorted().joinToString(","),
    ).joinToString("|")

    private fun NotificationStructure.toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("channelId", channelId)
        put("category", category)
        put("flags", flags)
        put("contentIntentPresent", contentIntentPresent)
        put("contentIntentCreatorPackage", contentIntentCreatorPackage)
        put("contentIntentKind", contentIntentKind.name)
        put("actionCount", actionCount)
        put("actionIntentCount", actionIntentCount)
        put("sameCreatorActionIntentCount", sameCreatorActionIntentCount)
        put("semanticActions", JSONArray(semanticActions))
        put("hasFullScreenIntent", hasFullScreenIntent)
        put("groupSummary", groupSummary)
        put("extrasKeys", JSONArray(extrasKeys.sorted()))
        put("notificationId", notificationId)
        put("tag", tag)
        put("observedAt", observedAt)
    }

    private fun structureFromJson(obj: JSONObject): NotificationStructure {
        val semantic = obj.optJSONArray("semanticActions")
        val extras = obj.optJSONArray("extrasKeys")
        return NotificationStructure(
            packageName = obj.optString("packageName"),
            channelId = obj.optString("channelId"),
            category = obj.optString("category"),
            flags = obj.optInt("flags"),
            contentIntentPresent = obj.optBoolean("contentIntentPresent"),
            contentIntentCreatorPackage = obj.optString("contentIntentCreatorPackage"),
            contentIntentKind = runCatching {
                PendingIntentKind.valueOf(obj.optString("contentIntentKind", PendingIntentKind.UNKNOWN.name))
            }.getOrDefault(PendingIntentKind.UNKNOWN),
            actionCount = obj.optInt("actionCount"),
            actionIntentCount = obj.optInt("actionIntentCount"),
            sameCreatorActionIntentCount = obj.optInt("sameCreatorActionIntentCount"),
            semanticActions = buildList {
                if (semantic != null) for (i in 0 until semantic.length()) add(semantic.optInt(i))
            },
            hasFullScreenIntent = obj.optBoolean("hasFullScreenIntent"),
            groupSummary = obj.optBoolean("groupSummary"),
            extrasKeys = buildSet {
                if (extras != null) for (i in 0 until extras.length()) add(extras.optString(i))
            },
            notificationId = obj.optInt("notificationId"),
            tag = obj.optString("tag"),
            observedAt = obj.optLong("observedAt"),
        )
    }
}
