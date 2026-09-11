package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.view.Display

/**
 * Low-level Android screenshot acquisition for offer capture.
 *
 * This component owns only platform mechanics: window-vs-display capture, the Android 16/ColorOS
 * display fallback, temporary overlay suppression, HardwareBuffer cleanup and conversion to a
 * software Bitmap. OCR, retry lifetime, Wolt/Bolt offer state and persistence stay in
 * [OfferAccessibilityService].
 */
internal class OfferScreenshotCapture(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    fun takeTargetScreenshot(
        windowId: Int,
        callback: TakeScreenshotCallback,
        preferDisplay: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT < 34) {
            LiveAdvisorHub.setCaptureSuppressed(service, true)
            val cleanCallback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    LiveAdvisorHub.setCaptureSuppressed(service, false)
                    callback.onSuccess(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    LiveAdvisorHub.setCaptureSuppressed(service, false)
                    callback.onFailure(errorCode)
                }
            }
            runCatching { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, cleanCallback) }
                .onFailure {
                    LiveAdvisorHub.setCaptureSuppressed(service, false)
                    CaptureEventLog.append(
                        service,
                        "screenshot_request_exception",
                        "Display screenshot request threw ${it.javaClass.simpleName}",
                        dedupeWindowMs = 3_000L,
                    )
                    callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                }
            return
        }

        // On the real Android 16/ColorOS courier device takeScreenshotOfWindow can sit for several
        // seconds and fail only after Wolt has already replaced the offer. A display capture returns
        // much sooner and the overlay is suppressed around it, so use that direct path for
        // time-critical Wolt OCR/proof frames.
        if (preferDisplay) {
            requestDisplayScreenshot(callback, fallback = false)
            return
        }

        // Window-scoped capture is ideal, but courier activities transition quickly and Android can
        // reject a perfectly valid request because the window id went stale between discovery and
        // capture. Fall back to a display screenshot instead of losing the offer.
        val windowCallback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                callback.onSuccess(screenshot)
            }

            override fun onFailure(errorCode: Int) {
                if (!shouldFallbackToDisplayScreenshot(errorCode)) {
                    callback.onFailure(errorCode)
                    return
                }

                val delay = if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    RATE_LIMIT_RETRY_MS
                } else {
                    DISPLAY_FALLBACK_DELAY_MS
                }
                CaptureEventLog.append(
                    service,
                    "screenshot_window_fallback",
                    "Window screenshot failed with Android error $errorCode; retrying as display capture",
                    dedupeWindowMs = 2_000L,
                )
                handler.postDelayed({ requestDisplayScreenshot(callback, fallback = true) }, delay)
            }
        }

        runCatching { service.takeScreenshotOfWindow(windowId, service.mainExecutor, windowCallback) }
            .onFailure {
                CaptureEventLog.append(
                    service,
                    "screenshot_window_exception",
                    "Window screenshot request threw ${it.javaClass.simpleName}; trying display capture",
                    dedupeWindowMs = 3_000L,
                )
                handler.postDelayed(
                    { requestDisplayScreenshot(callback, fallback = true) },
                    DISPLAY_FALLBACK_DELAY_MS,
                )
            }
    }

    fun discard(screenshot: ScreenshotResult) {
        runCatching { screenshot.hardwareBuffer.close() }
    }

    fun toBitmap(screenshot: ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            try {
                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardwareBitmap.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun requestDisplayScreenshot(callback: TakeScreenshotCallback, fallback: Boolean) {
        LiveAdvisorHub.setCaptureSuppressed(service, true)
        val displayCallback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                LiveAdvisorHub.setCaptureSuppressed(service, false)
                CaptureEventLog.append(
                    service,
                    if (fallback) "screenshot_display_fallback_ok" else "screenshot_display_direct_ok",
                    if (fallback) "Display screenshot fallback succeeded" else "Direct display screenshot succeeded",
                    dedupeWindowMs = 3_000L,
                )
                callback.onSuccess(screenshot)
            }

            override fun onFailure(displayErrorCode: Int) {
                LiveAdvisorHub.setCaptureSuppressed(service, false)
                CaptureEventLog.append(
                    service,
                    if (fallback) "screenshot_display_fallback_failed" else "screenshot_display_direct_failed",
                    if (fallback) {
                        "Display screenshot fallback failed with Android error $displayErrorCode"
                    } else {
                        "Direct display screenshot failed with Android error $displayErrorCode"
                    },
                    dedupeWindowMs = 3_000L,
                )
                callback.onFailure(displayErrorCode)
            }
        }
        runCatching { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, displayCallback) }
            .onFailure {
                LiveAdvisorHub.setCaptureSuppressed(service, false)
                CaptureEventLog.append(
                    service,
                    "screenshot_display_exception",
                    "Display screenshot request threw ${it.javaClass.simpleName}",
                    dedupeWindowMs = 3_000L,
                )
                callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            }
    }

    private fun shouldFallbackToDisplayScreenshot(errorCode: Int): Boolean {
        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return false
        if (Build.VERSION.SDK_INT >= 34 && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) return false
        return true
    }

    companion object {
        const val RATE_LIMIT_RETRY_MS = 750L
        private const val DISPLAY_FALLBACK_DELAY_MS = 120L
    }
}
