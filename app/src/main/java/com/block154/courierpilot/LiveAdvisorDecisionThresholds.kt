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
 * Owns the per-offer threshold snapshot used by the live advisor.
 *
 * Adaptive market thresholds are warmed off the UI thread. The first snapshot actually used for
 * an offer is frozen for that offer's lifetime, so a late database/profile result can improve the
 * next offer but can never repaint the current verdict while the courier is deciding.
 */
internal class LiveAdvisorDecisionThresholds(
    private val loadCurrency: (String) -> String,
    private val loadAdaptiveThresholds: (String, String) -> OfferDecisionThresholds?,
    private val executeBackground: ((() -> Unit) -> Unit),
    private val postToMain: ((() -> Unit) -> Unit),
) {
    private var platform = ""
    private var generation = -1L
    private var snapshot: LiveAdvisorDecisionThresholdSnapshot? = null
    private var prewarmGeneration = -1L

    fun beginOffer(platform: String, generation: Long) {
        this.platform = platform
        this.generation = generation
        snapshot = null
        prewarmGeneration = -1L
    }

    fun clearOffer() {
        platform = ""
        generation = -1L
        snapshot = null
        prewarmGeneration = -1L
    }

    fun snapshotFor(currencyCode: String): LiveAdvisorDecisionThresholdSnapshot {
        snapshot?.takeIf { it.currencyCode.equals(currencyCode, ignoreCase = true) }?.let { return it }

        // Never scan the local market database on the UI thread just to show money/km. The numeric
        // rate depends only on price + Valhalla distance, so use the cheap currency fallback while
        // an adaptive snapshot continues warming for a future offer if it lost this race.
        prewarm()
        val coldStart = LiveOfferColdStartThresholds.forCurrency(currencyCode)
        val frozen = LiveAdvisorDecisionThresholdSnapshot(
            currencyCode = currencyCode,
            thresholds = coldStart,
            source = if (coldStart != null) "currency_cold_start_frozen" else "none_frozen",
        )
        snapshot = frozen
        return frozen
    }

    fun prewarm() {
        if (platform.isBlank() || snapshot != null) return
        val expectedGeneration = generation
        if (prewarmGeneration == expectedGeneration) return
        prewarmGeneration = expectedGeneration
        val expectedPlatform = platform
        executeBackground background@{
            val currencyCode = runCatching { loadCurrency(expectedPlatform) }.getOrNull().orEmpty()
            if (currencyCode.isBlank()) return@background
            val adaptive = runCatching {
                loadAdaptiveThresholds(expectedPlatform, currencyCode)
            }.getOrNull()
            val coldStart = LiveOfferColdStartThresholds.forCurrency(currencyCode)
            val warmed = LiveAdvisorDecisionThresholdSnapshot(
                currencyCode = currencyCode,
                thresholds = adaptive ?: coldStart,
                source = when {
                    adaptive != null -> "adaptive"
                    coldStart != null -> "currency_cold_start"
                    else -> "none"
                },
            )
            postToMain {
                if (generation == expectedGeneration && snapshot == null) {
                    snapshot = warmed
                }
            }
        }
    }

    companion object {
        private val PREWARM_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CourierPilot-ScorePrewarm").apply { isDaemon = true }
        }

        fun production(context: Context, handler: Handler): LiveAdvisorDecisionThresholds {
            val app = context.applicationContext
            return LiveAdvisorDecisionThresholds(
                loadCurrency = { platform -> MarketIntelligence.currencyFor(app, platform) },
                loadAdaptiveThresholds = { platform, currencyCode ->
                    MarketIntelligence.thresholdsFor(app, platform, currencyCode)
                },
                executeBackground = { task -> PREWARM_EXECUTOR.execute(task) },
                postToMain = { task -> handler.post(task) },
            )
        }
    }
}
