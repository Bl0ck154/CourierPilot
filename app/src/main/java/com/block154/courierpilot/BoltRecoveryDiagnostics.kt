package com.block154.courierpilot

/**
 * Local-only geometry evidence. Never send projected customer points to telemetry,
 * CaptureEventLog, RemoteDiagnostics, or any uploaded diagnostic stream.
 */
internal data class BoltRecoveryDiagnostics(
    val scaleMetersPerPixel: Double? = null,
    val measuredRotationDegrees: Double? = null,
    val anchorBaselinePx: Double? = null,
    val anchorBaselineMeters: Double? = null,
    val pickupMarkerCount: Int = 0,
    val dropoffMarkerCount: Int = 0,
    val projectedPickups: List<RoutePoint> = emptyList(),
    val projectedDropoffs: List<RoutePoint> = emptyList(),
    val weakAnchorReason: String? = null,
    val etaConflict: Boolean = false,
)

internal data class BoltRecoveryResult(
    val recovery: BoltMapStopRecovery?,
    val diagnostics: BoltRecoveryDiagnostics,
    val confidence: Double?,
)
