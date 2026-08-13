package com.block154.couriernotificationlistener

/**
 * Kotlin stdlib provides orEmpty() for String?, but Android notification extras and
 * tickerText are CharSequence?. Keep notification text collection null-safe without
 * forcing conversions at each call site.
 */
internal fun CharSequence?.orEmpty(): CharSequence = this ?: ""
