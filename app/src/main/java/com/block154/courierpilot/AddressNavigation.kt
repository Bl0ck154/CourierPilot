package com.block154.courierpilot

import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun Context.openAddressInMaps(address: String) {
    val clean = address.trim()
    if (clean.isEmpty()) return
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(clean)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure {
        CaptureEventLog.append(
            this,
            stage = "ui_error",
            message = "Could not open address in maps: ${it.javaClass.simpleName}",
            dedupeWindowMs = 5_000L,
        )
    }
}
