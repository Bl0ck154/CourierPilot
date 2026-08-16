package com.block154.courierpilot

import android.content.Context

internal object DeveloperModeSettings {
    private const val PREFS = "courierpilot_developer_mode"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
