package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorRouteFailurePolicyTest {
    @Test
    fun lateFailurePreservesLastVerifiedRoutePresentation() {
        assertEquals(
            WoltRouteFailureAction.PRESERVE_LAST_GOOD,
            LiveAdvisorRouteFailurePolicy.decideWoltFinalFailure(
                hasResolvedRoute = true,
                retryCount = 1,
                reason = "route failed",
            ),
        )
    }

    @Test
    fun transientFailureGetsOneFreshRetryBeforeCardCanBeDismissed() {
        assertEquals(
            WoltRouteFailureAction.RETRY,
            LiveAdvisorRouteFailurePolicy.decideWoltFinalFailure(
                hasResolvedRoute = false,
                retryCount = 0,
                reason = "Geocoder timed out after 7s",
            ),
        )
        assertEquals(
            WoltRouteFailureAction.DISMISS,
            LiveAdvisorRouteFailurePolicy.decideWoltFinalFailure(
                hasResolvedRoute = false,
                retryCount = 1,
                reason = "Geocoder timed out after 7s",
            ),
        )
    }

    @Test
    fun deterministicIncompleteRouteDoesNotRetryIntoAnotherDeadCard() {
        assertFalse(LiveAdvisorRouteFailurePolicy.isRetryableWoltFailure("incomplete textual Wolt route"))
        assertFalse(LiveAdvisorRouteFailurePolicy.isRetryableWoltFailure("location permission missing"))
        assertEquals(
            WoltRouteFailureAction.DISMISS,
            LiveAdvisorRouteFailurePolicy.decideWoltFinalFailure(
                hasResolvedRoute = false,
                retryCount = 0,
                reason = "incomplete textual Wolt route",
            ),
        )
    }

    @Test
    fun unknownNetworkStyleFailureIsRetryableOnce() {
        assertTrue(LiveAdvisorRouteFailurePolicy.isRetryableWoltFailure(null))
        assertTrue(LiveAdvisorRouteFailurePolicy.isRetryableWoltFailure("SocketTimeoutException"))
    }
}
