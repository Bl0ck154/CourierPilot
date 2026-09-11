package com.block154.courierpilot

import android.location.LocationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLiveLocationPolicyTest {
    @Test
    fun networkFixCannotWinEarlyWhileGpsIsEnabled() {
        assertFalse(
            RouteLiveLocationPolicy.shouldEarlyAccept(
                provider = LocationManager.NETWORK_PROVIDER,
                accuracyMeters = 8f,
                gpsProviderEnabled = true,
            )
        )
    }

    @Test
    fun accurateGpsCanWinEarly() {
        assertTrue(
            RouteLiveLocationPolicy.shouldEarlyAccept(
                provider = LocationManager.GPS_PROVIDER,
                accuracyMeters = 20f,
                gpsProviderEnabled = true,
            )
        )
    }

    @Test
    fun networkCanStillWinWhenGpsProviderIsUnavailable() {
        assertTrue(
            RouteLiveLocationPolicy.shouldEarlyAccept(
                provider = LocationManager.NETWORK_PROVIDER,
                accuracyMeters = 20f,
                gpsProviderEnabled = false,
            )
        )
    }

    @Test
    fun liveCacheReusesOnlyFreshAccurateGps() {
        val gps = CurrentLocationFix(
            RoutePoint(54.68, 25.28),
            15f,
            5_000L,
            LocationManager.GPS_PROVIDER,
        )
        val network = gps.copy(provider = LocationManager.NETWORK_PROVIDER)
        val staleGps = gps.copy(ageMillis = 60_000L)
        val vagueGps = gps.copy(accuracyMeters = 120f)
        val unknownAccuracyGps = gps.copy(accuracyMeters = null)

        assertTrue(RouteLiveLocationPolicy.shouldReuseCached(gps))
        assertFalse(RouteLiveLocationPolicy.shouldReuseCached(network))
        assertFalse(RouteLiveLocationPolicy.shouldReuseCached(staleGps))
        assertFalse(RouteLiveLocationPolicy.shouldReuseCached(vagueGps))
        assertFalse(RouteLiveLocationPolicy.shouldReuseCached(unknownAccuracyGps))
    }
    @Test
    fun freshAccurateFusedFixIsReusableRegardlessOfProviderLabel() {
        val fused = CurrentLocationFix(RoutePoint(54.68, 25.28), 12f, 4_000L, "fused-cache")
        val stale = fused.copy(ageMillis = RouteLiveLocationPolicy.FUSED_FIX_MAX_AGE_MS + 1)
        val vague = fused.copy(accuracyMeters = RouteLiveLocationPolicy.FUSED_FIX_MAX_ACCURACY_METERS + 1)

        assertTrue(RouteLiveLocationPolicy.shouldReuseFused(fused))
        assertFalse(RouteLiveLocationPolicy.shouldReuseFused(stale))
        assertFalse(RouteLiveLocationPolicy.shouldReuseFused(vague))
    }

}
