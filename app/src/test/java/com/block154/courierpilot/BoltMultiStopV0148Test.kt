package com.block154.courierpilot

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoltMultiStopV0148Test {

    @Test
    fun sameRestaurantDoubleKeepsOnePickupAndTwoDeliveries() {
        val parsed = OfferParser.parse(
            """
            Hong Kong (Basanavičiaus g.)
            BASANAVIČIAUS 19, VILNIUS
            ~16 min
            Drop-off points: 2
            31 min, 4,97 €
            """.trimIndent()
        )

        assertEquals(listOf("Hong Kong (Basanavičiaus g.)"), parsed.merchantNames)
        assertEquals(listOf("BASANAVIČIAUS 19, VILNIUS"), parsed.pickupAddresses)
        assertEquals(2, parsed.deliveryCount)
        assertEquals(497, parsed.priceCents)
        assertEquals(31, parsed.estimatedMinutesMin)
    }

    @Test
    fun twoRestaurantDoublePreservesBothPickupRows() {
        val parsed = OfferParser.parse(
            """
            Sushi Express (Ogmios miestas)
            Verkių g. 29C, Vilnius
            ~13 min
            Can Can Pizza Vilnius Outlet Park
            Verkių g. 31a, Vilnius
            ~16 min
            Drop-off points: 2
            29 min, 4,46 €
            """.trimIndent()
        )

        assertEquals(
            listOf("Sushi Express (Ogmios miestas)", "Can Can Pizza Vilnius Outlet Park"),
            parsed.merchantNames,
        )
        assertEquals(listOf("Verkių g. 29C, Vilnius", "Verkių g. 31a, Vilnius"), parsed.pickupAddresses)
        assertEquals(2, parsed.deliveryCount)
        assertEquals(446, parsed.priceCents)
    }

    @Test
    fun screenshotDetectorKeepsTwoBlueAndTwoGreenPins() {
        val bitmap = Bitmap.createBitmap(700, 1_500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(245, 245, 245))
        val green = Color.rgb(34, 147, 93)
        val blue = Color.rgb(94, 105, 235)
        val cyan = Color.rgb(60, 177, 224)

        drawThinLine(bitmap, 350, 180, 300, 480, blue)
        drawThinLine(bitmap, 300, 480, 470, 700, blue)
        drawThinLine(bitmap, 470, 700, 430, 860, green)
        drawThinLine(bitmap, 430, 860, 390, 930, green)
        drawDisk(bitmap, 350, 180, 18, cyan)
        drawDisk(bitmap, 300, 480, 36, blue)
        drawDisk(bitmap, 470, 700, 36, blue)
        drawDisk(bitmap, 430, 860, 36, green)
        drawDisk(bitmap, 390, 930, 36, green)

        val markers = BoltScreenshotMarkerExtractor.extract(bitmap)
        assertNotNull(markers)
        markers!!
        assertEquals(2, markers.pickups.size)
        assertEquals(2, markers.dropoffs.size)
        assertNotNull(markers.currentLocation)
        bitmap.recycle()
    }

    @Test
    fun oneKnownPickupPlusExtraBlueMarkerRecoversAddOnPickupAndTwoDropoffs() {
        val current = RoutePoint(54.6800000, 25.2800000)
        val metersPerPixel = 5.0
        val currentScreen = ScreenPoint(100.0, 100.0)
        val knownPickupScreen = ScreenPoint(200.0, 100.0)
        val hiddenPickupScreen = ScreenPoint(200.0, 200.0)
        val dropoff1Screen = ScreenPoint(300.0, 180.0)
        val dropoff2Screen = ScreenPoint(330.0, 250.0)
        val knownPickupPoint = screenToGeoNorthUp(current, currentScreen, knownPickupScreen, metersPerPixel)

        val recovery = BoltMultiStopMapRecovery.recover(
            markers = markers(
                current = currentScreen,
                pickups = listOf(knownPickupScreen, hiddenPickupScreen),
                dropoffs = listOf(dropoff1Screen, dropoff2Screen),
            ),
            current = current,
            knownPickups = listOf(pickup(knownPickupPoint, "Visible add-on restaurant")),
            expectedDropoffs = 2,
        )

        assertNotNull(recovery)
        recovery!!
        assertEquals(2, recovery.orderedPickups.size)
        assertEquals(2, recovery.orderedDropoffs.size)
        assertTrue(recovery.orderedPickups.any { it.provenance == CoordinateProvenance.BOLT_MAP_RECOVERY })
    }

    @Test
    fun twoKnownPickupsAreMatchedToTwoMapPinsWithoutCreatingFakeThirdPickup() {
        val current = RoutePoint(54.6800000, 25.2800000)
        val metersPerPixel = 4.0
        val currentScreen = ScreenPoint(120.0, 120.0)
        val firstScreen = ScreenPoint(230.0, 150.0)
        val secondScreen = ScreenPoint(330.0, 260.0)
        val first = screenToGeoNorthUp(current, currentScreen, firstScreen, metersPerPixel)
        val second = screenToGeoNorthUp(current, currentScreen, secondScreen, metersPerPixel)

        val recovery = BoltMultiStopMapRecovery.recover(
            markers = markers(
                current = currentScreen,
                pickups = listOf(secondScreen, firstScreen), // deliberately reverse detector order
                dropoffs = listOf(ScreenPoint(390.0, 330.0), ScreenPoint(430.0, 390.0)),
            ),
            current = current,
            knownPickups = listOf(pickup(first, "Restaurant A"), pickup(second, "Restaurant B")),
            expectedDropoffs = 2,
        )

        assertNotNull(recovery)
        recovery!!
        assertEquals(2, recovery.orderedPickups.size)
        assertEquals(2, recovery.matchedPickupMarkerIndices.size)
        assertEquals(0, recovery.orderedPickups.count { it.provenance == CoordinateProvenance.BOLT_MAP_RECOVERY })
        assertEquals(2, recovery.orderedDropoffs.size)
    }


    @Test
    fun activePickupFallbackComesFirstAndDoesNotDuplicateSameRestaurant() {
        val merged = BoltPickupAddressPlanner.merge(
            active = listOf("Basanavičiaus g. 19, Vilnius"),
            offered = listOf("BASANAVIČIAUS G. 19, VILNIUS", "Gedimino pr. 5, Vilnius"),
        )

        assertEquals(2, merged.size)
        assertTrue(BoltPickupAddressPlanner.sameAddress("Basanavičiaus g. 19, Vilnius", merged[0]))
        assertTrue(BoltPickupAddressPlanner.sameAddress("Gedimino pr. 5, Vilnius", merged[1]))
    }

    @Test
    fun currentOfferPickupAnchorsIgnoreStaleCachedRestaurants() {
        val anchors = BoltPickupAddressPlanner.routeAnchors(
            active = listOf(
                "Old restaurant A, Vilnius",
                "Old restaurant B, Vilnius",
            ),
            offered = listOf("Upės g. 9, Vilnius"),
        )

        assertEquals(listOf("Upės g. 9, Vilnius"), anchors)
    }

    @Test
    fun cachedPickupIsOnlyLastResortWhenCurrentOfferHasNoAddress() {
        val anchors = BoltPickupAddressPlanner.routeAnchors(
            active = listOf("Older pickup, Vilnius", "Latest pickup, Vilnius"),
            offered = emptyList(),
        )

        assertEquals(listOf("Latest pickup, Vilnius"), anchors)
    }

    @Test
    fun twoExpectedCustomersAreNotCollapsedJustBecauseTheyAreWithinThirtyFiveMeters() {
        val current = RoutePoint(54.6800000, 25.2800000)
        val metersPerPixel = 5.0
        val currentScreen = ScreenPoint(100.0, 100.0)
        val pickupScreen = ScreenPoint(200.0, 100.0)
        val pickupPoint = screenToGeoNorthUp(current, currentScreen, pickupScreen, metersPerPixel)

        val recovery = BoltMultiStopMapRecovery.recover(
            markers = markers(
                current = currentScreen,
                pickups = listOf(pickupScreen),
                dropoffs = listOf(ScreenPoint(300.0, 200.0), ScreenPoint(304.0, 202.0)),
            ),
            current = current,
            knownPickups = listOf(pickup(pickupPoint, "Restaurant")),
            expectedDropoffs = 2,
        )

        assertNotNull(recovery)
        assertEquals(2, recovery!!.orderedDropoffs.size)
    }

    private fun markers(
        current: ScreenPoint,
        pickups: List<ScreenPoint>,
        dropoffs: List<ScreenPoint>,
    ) = BoltSemanticMarkers(
        currentLocation = BoltMarkerEvidence(BoltMarkerKind.CURRENT_LOCATION, current, confidence = 0.9),
        pickups = pickups.map { BoltMarkerEvidence(BoltMarkerKind.PICKUP, it, confidence = 0.8) },
        dropoffs = dropoffs.map { BoltMarkerEvidence(BoltMarkerKind.DROPOFF, it, confidence = 0.8) },
        unknown = emptyList(),
    )

    private fun pickup(point: RoutePoint, label: String) = ResolvedWaypoint(
        kind = WaypointKind.PICKUP,
        point = point,
        label = label,
        provenance = CoordinateProvenance.GEOCODED_ADDRESS,
        confidence = 0.85,
    )

    private fun screenToGeoNorthUp(
        current: RoutePoint,
        currentScreen: ScreenPoint,
        target: ScreenPoint,
        metersPerPixel: Double,
    ): RoutePoint {
        val east = (target.x - currentScreen.x) * metersPerPixel
        val north = -(target.y - currentScreen.y) * metersPerPixel
        val metersPerDegree = 111_320.0
        val lat = current.latitude + north / metersPerDegree
        val lon = current.longitude + east / (metersPerDegree * cos(Math.toRadians(current.latitude)))
        return RoutePoint(lat, lon)
    }

    private fun drawDisk(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int, color: Int) {
        val radiusSquared = radius * radius
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(bitmap.height - 1)) {
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(bitmap.width - 1)) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= radiusSquared) bitmap.setPixel(x, y, color)
            }
        }
    }

    private fun drawThinLine(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        color: Int,
    ) {
        val steps = maxOf(kotlin.math.abs(endX - startX), kotlin.math.abs(endY - startY)).coerceAtLeast(1)
        for (step in 0..steps) {
            val fraction = step.toDouble() / steps
            val x = (startX + (endX - startX) * fraction).toInt()
            val y = (startY + (endY - startY) * fraction).toInt()
            for (offsetX in -1..1) {
                for (offsetY in -1..1) {
                    val px = x + offsetX
                    val py = y + offsetY
                    if (px in 0 until bitmap.width && py in 0 until bitmap.height) bitmap.setPixel(px, py, color)
                }
            }
        }
    }
}
