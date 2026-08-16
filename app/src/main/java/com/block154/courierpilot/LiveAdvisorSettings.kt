package com.block154.courierpilot

import android.content.Context

internal object LiveAdvisorSettings {
    private const val PREFS = "courierpilot_live_advisor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WOLT_ROUTING = "wolt_routing"
    private const val KEY_BOLT_ROUTING = "bolt_routing"
    private const val KEY_VOICE = "voice"

    /** Local platform-metric overlay. It does not accept/reject anything. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Automatic Wolt routing is a separate privacy-sensitive opt-in. Merely configuring the manual
     * Valhalla research endpoint does not enable sending offer-time coordinates automatically.
     */
    fun automaticWoltRouting(context: Context): Boolean = prefs(context).getBoolean(KEY_WOLT_ROUTING, false)

    fun setAutomaticWoltRouting(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WOLT_ROUTING, enabled).apply()
    }

    /**
     * Bolt routing is separately opt-in. It can always calculate current GPS -> textual pickup.
     * A full route is attempted only when enough map-marker evidence exists to recover the customer
     * point without guessing.
     */
    fun automaticBoltRouting(context: Context): Boolean = prefs(context).getBoolean(KEY_BOLT_ROUTING, false)

    fun setAutomaticBoltRouting(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOLT_ROUTING, enabled).apply()
    }

    fun routeEnabled(context: Context, platform: String): Boolean = when {
        platform.equals("Wolt", ignoreCase = true) -> automaticWoltRouting(context)
        platform.equals("Bolt", ignoreCase = true) -> automaticBoltRouting(context)
        else -> false
    }

    fun setRouteEnabled(context: Context, platform: String, enabled: Boolean) {
        when {
            platform.equals("Wolt", ignoreCase = true) -> setAutomaticWoltRouting(context, enabled)
            platform.equals("Bolt", ignoreCase = true) -> setAutomaticBoltRouting(context, enabled)
        }
    }

    fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
