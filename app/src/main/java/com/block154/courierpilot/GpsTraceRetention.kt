package com.block154.courierpilot

internal fun RouteResearchDatabase.deleteGpsSession(sessionId: Long): Boolean =
    writableDatabase.delete(
        "gps_sessions",
        "id = ? AND ended_at IS NOT NULL",
        arrayOf(sessionId.toString()),
    ) > 0

internal fun RouteResearchDatabase.deleteAllFinishedGpsSessions(): Int =
    writableDatabase.delete(
        "gps_sessions",
        "ended_at IS NOT NULL",
        null,
    )
