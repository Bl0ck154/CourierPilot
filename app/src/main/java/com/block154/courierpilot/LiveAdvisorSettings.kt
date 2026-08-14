package com.block154.courierpilot

import android.content.Context

internal object LiveAdvisorSettings {
    private const val PREFS = "courierpilot_live_advisor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WOLT_ROUTING = "wolt_routing"
    private const val KEY_VOICE = "voice"

    /** Local platform-metric overlay. It does not accept/reject anything. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Existing users who already explicitly enabled the protected Valhalla endpoint inherit that
     * opt-in for Wolt auto-routing. A later explicit toggle always overrides the inherited default.
     */
    fun automaticWoltRouting(context: Context): Boolean = prefs(context).getBoolean(
        KEY_WOLT_ROUTING,
        RouteEndpointSettings.load(context).enabled,
    )

    fun setAutomaticWoltRouting(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WOLT_ROUTING, enabled).apply()
    }

    fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
