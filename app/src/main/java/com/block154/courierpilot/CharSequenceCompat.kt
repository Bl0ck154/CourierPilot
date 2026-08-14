package com.block154.courierpilot

/**
 * Android notification/accessibility APIs commonly expose CharSequence?. Normalize it to String
 * so classifier and package-name code does not accidentally propagate CharSequence types.
 */
internal fun CharSequence?.orEmpty(): String = this?.toString() ?: ""
