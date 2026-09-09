package com.block154.courierpilot

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteComparisonEngineTest {
    @Test
    fun runsIndependentProfilesConcurrently() {
        val barrier = CyclicBarrier(2)
        val provider = object : RouteProvider {
            override fun route(request: RouteRequest): Result<RouteResult> = runCatching {
                barrier.await(1, TimeUnit.SECONDS)
                RouteResult(
                    provider = "test",
                    profile = request.profile,
                    distanceMeters = if (request.profile == RouteProfile.PEDESTRIAN_SHORTCUT) 4_000 else 4_500,
                    durationSeconds = 900,
                    legShapes = emptyList(),
                )
            }
        }

        val comparison = RouteComparisonEngine(provider).compare(
            listOf(RoutePoint(54.68, 25.28), RoutePoint(54.69, 25.29)),
        )

        assertTrue(comparison.pedestrian.isSuccess)
        assertTrue(comparison.cycleway.isSuccess)
        assertEquals(4_000, comparison.pedestrian.getOrThrow().distanceMeters)
        assertEquals(4_500, comparison.cycleway.getOrThrow().distanceMeters)
    }

    @Test
    fun retriesExactlyOneMissingProfileBeforeReturningComparison() {
        val pedestrianCalls = AtomicInteger(0)
        val provider = object : RouteProvider {
            override fun route(request: RouteRequest): Result<RouteResult> {
                if (request.profile == RouteProfile.PEDESTRIAN_SHORTCUT && pedestrianCalls.incrementAndGet() == 1) {
                    return Result.failure(IllegalStateException("transient pedestrian failure"))
                }
                return Result.success(
                    RouteResult(
                        provider = "test",
                        profile = request.profile,
                        distanceMeters = if (request.profile == RouteProfile.PEDESTRIAN_SHORTCUT) 4_000 else 4_500,
                        durationSeconds = 900,
                        legShapes = emptyList(),
                    )
                )
            }
        }

        val comparison = RouteComparisonEngine(provider).compare(
            listOf(RoutePoint(54.68, 25.28), RoutePoint(54.69, 25.29)),
        )

        assertTrue(comparison.pedestrian.isSuccess)
        assertTrue(comparison.cycleway.isSuccess)
        assertEquals(2, pedestrianCalls.get())
        assertEquals(4_000, comparison.pedestrian.getOrThrow().distanceMeters)
        assertEquals(4_500, comparison.cycleway.getOrThrow().distanceMeters)
    }
}
