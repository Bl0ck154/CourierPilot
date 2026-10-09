package com.block154.courierpilot

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal data class LiveAdvisorDecisionThresholdSnapshot(
    val currencyCode: String,
    val thresholds: OfferDecisionThresholds?,
    val source: String,
)

/**
 * Market thresholds are warmed once per (platform, currency) and frozen at session start.
 * A route recorded during this offer must not train the reference distribution used for its
 * own verdict; refresh is queued only after the previous session has ended.
 */
internal class LiveAdvisorDecisionThresholds(
    private val loadCurrency: (String) -> String,
    private val loadAdaptiveThresholds: (String, String) -> OfferDecisionThresholds?,
    private val executeBackground: ((() -> Unit) -> Unit),
    private val postToMain: ((() -> Unit) -> Unit),
    private val warmedStore: MutableMap<Pair<String, String>, LiveAdvisorDecisionThresholdSnapshot> = mutableMapOf(),
) {
    private var platform = ""
    private var generation = -1L
    private var snapshot: LiveAdvisorDecisionThresholdSnapshot? = null
    private var frozenAtBegin: Map<String, LiveAdvisorDecisionThresholdSnapshot> = emptyMap()
    private val warmingPlatforms = mutableSetOf<String>()

    fun beginOffer(platform: String, generation: Long) {
        if (this.platform.isNotBlank() && this.generation != generation) clearOffer()
        this.platform = platform
        this.generation = generation
        snapshot = null
        // A preload finishing during a live offer is *never* used by that offer.
        frozenAtBegin = synchronized(warmedStore) {
            warmedStore.filterKeys { it.first.equals(platform, ignoreCase = true) }
                .mapKeys { it.key.second.uppercase() }
        }
    }

    fun clearOffer() {
        val previousPlatform = platform
        platform = ""
        generation = -1L
        snapshot = null
        frozenAtBegin = emptyMap()
        if (previousPlatform.isNotBlank()) warmPlatform(previousPlatform)
    }

    fun snapshotFor(currencyCode: String): LiveAdvisorDecisionThresholdSnapshot {
        snapshot?.takeIf { it.currencyCode.equals(currencyCode, ignoreCase = true) }?.let { return it }
        val frozen = frozenAtBegin[currencyCode.uppercase()] ?: run {
            val fallback = LiveOfferColdStartThresholds.forCurrency(currencyCode)
            LiveAdvisorDecisionThresholdSnapshot(
                currencyCode = currencyCode,
                thresholds = fallback,
                source = if (fallback == null) "none_frozen" else "currency_cold_start_frozen",
            )
        }
        snapshot = frozen
        return frozen
    }

    /** Existing callers can ask for prewarm; during an offer it must not refresh thresholds. */
    fun prewarm() {
        if (generation < 0 && platform.isNotBlank()) warmPlatform(platform)
    }

    /** Called at advisor creation, before any offer is scored, and after the previous offer ends. */
    fun warmPlatform(platform: String) {
        if (platform.isBlank() || !warmingPlatforms.add(platform)) return
        executeBackground background@{
            val currency = runCatching { loadCurrency(platform) }.getOrNull().orEmpty()
            if (currency.isBlank()) {
                postToMain { warmingPlatforms.remove(platform) }
                return@background
            }
            val adaptive = runCatching { loadAdaptiveThresholds(platform, currency) }.getOrNull()
            val fallback = LiveOfferColdStartThresholds.forCurrency(currency)
            val warmed = LiveAdvisorDecisionThresholdSnapshot(
                currencyCode = currency,
                thresholds = adaptive ?: fallback,
                source = when {
                    adaptive != null -> "adaptive"
                    fallback != null -> "currency_cold_start"
                    else -> "none"
                },
            )
            postToMain {
                synchronized(warmedStore) { warmedStore[platform to currency.uppercase()] = warmed }
                warmingPlatforms.remove(platform)
            }
        }
    }

    companion object {
        private val PREWARM_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CourierPilot-ScorePrewarm").apply { isDaemon = true }
        }
        private val PROCESS_WARMED =
            mutableMapOf<Pair<String, String>, LiveAdvisorDecisionThresholdSnapshot>()

        fun production(context: Context, handler: Handler): LiveAdvisorDecisionThresholds {
            val app = context.applicationContext
            return LiveAdvisorDecisionThresholds(
                loadCurrency = { platform -> MarketIntelligence.currencyFor(app, platform) },
                loadAdaptiveThresholds = { platform, currencyCode ->
                    MarketIntelligence.thresholdsFor(app, platform, currencyCode)
                },
                executeBackground = { task -> PREWARM_EXECUTOR.execute(task) },
                postToMain = { task -> handler.post(task) },
                warmedStore = PROCESS_WARMED,
            ).apply {
                warmPlatform("Wolt")
                warmPlatform("Bolt")
            }
        }
    }
}
