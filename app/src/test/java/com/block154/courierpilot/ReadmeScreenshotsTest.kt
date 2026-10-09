package com.block154.courierpilot

import android.content.ComponentName
import android.content.ContentValues
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.robolectric.RuntimeEnvironment
import com.block154.courierpilot.ui.AppearanceSettings
import com.block154.courierpilot.ui.ThemeMode
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real dashboard with fictional data for README screenshots. Skipped unless
 * COURIERPILOT_SCREENSHOT_DIR is set, e.g.
 * `COURIERPILOT_SCREENSHOT_DIR=/tmp/shots gradle testDebugUnitTest --tests '*ReadmeScreenshotsTest'`.
 * Every venue, customer, address and code below is invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ReadmeScreenshotsTest {
    private val outDir = System.getenv("COURIERPILOT_SCREENSHOT_DIR")?.takeIf(String::isNotBlank)

    @After
    fun resetTheme() {
        AppearanceSettings.setThemeMode(RuntimeEnvironment.getApplication(), ThemeMode.SYSTEM)
    }

    /** One activity for both themes: switching at runtime also proves every screen re-themes live. */
    @Test
    fun lightAndDarkScreens() {
        assumeTrue(outDir != null)
        val context = RuntimeEnvironment.getApplication()
        AppearanceSettings.setThemeMode(context, ThemeMode.LIGHT)
        grantCaptureAccess(context)
        seed(context)
        Robolectric.buildActivity(CourierPilotDashboardActivity::class.java).setup().use { controller ->
            val root = controller.get().window.decorView
            settle()
            listOf(ThemeMode.LIGHT to "light", ThemeMode.DARK to "dark").forEach { (mode, suffix) ->
                AppearanceSettings.setThemeMode(context, mode)
                tapNav(root, 0)
                save(root, "home-$suffix")
                tapNav(root, 1)
                save(root, "history-$suffix")
                tapNav(root, 2)
                save(root, "addresses-$suffix")
                tapNav(root, 3)
                save(root, "stats-$suffix")
                tapNav(root, 4)
                save(root, "pay-$suffix")
                tapNav(root, 0)
                tap(root, root.width - 38 * root.resources.displayMetrics.density, 47 * root.resources.displayMetrics.density)
                save(root, "settings-$suffix")
                controller.get().onBackPressedDispatcher.onBackPressed()
                settle()
            }
        }
    }

    private fun grantCaptureAccess(context: android.content.Context) {
        val resolver = context.contentResolver
        Settings.Secure.putString(
            resolver,
            "enabled_notification_listeners",
            ComponentName(context.packageName, "${context.packageName}.Listener").flattenToString(),
        )
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, OfferAccessibilityService::class.java).flattenToString(),
        )
    }

    private fun seed(context: android.content.Context) {
        val offers = OfferDatabase.get(context)
        if (offers.offerCount() > 0) return
        val now = System.currentTimeMillis()
        listOf(
            Sample("Wolt", "Pasta Corner (Gedimino pr.)", "Gedimino pr. 9, Vilnius", 723, 4_100, 8),
            Sample("Bolt", "Sushi Harbour (Vokiečių g.)", "Vokiečių g. 3, Vilnius", 311, 3_700, 16),
            Sample("Wolt", "Burger Lab (Kauno g.) + Green Bowl", "Kauno g. 5, Vilnius + Pylimo g. 21, Vilnius", 800, 8_000, 19),
            Sample("Bolt", "Bao House", "Pylimo g. 4, Vilnius", 341, 3_200, 33),
            Sample("Bolt", "Kebab Nord (Antakalnio g.)", "Antakalnio g. 71, Vilnius", 276, 2_500, 78),
            Sample("Wolt", "Morning Bakery", "Totorių g. 6, Vilnius", 502, 7_400, 109),
        ).forEach { sample ->
            val merchants = sample.venue.split(" + ")
            offers.insert(
                OfferRecord(
                    capturedAt = now - sample.minutesAgo * 60_000L,
                    platform = sample.platform,
                    packageName = if (sample.platform == "Wolt") CourierSignals.WOLT_PACKAGE else CourierSignals.BOLT_PACKAGE,
                    priceCents = sample.cents,
                    distanceMeters = sample.meters,
                    restaurant = merchants.joinToString(", "),
                    screenshotUri = "",
                    screenshotFilename = "",
                    rawText = "",
                    merchantNames = merchants,
                    pickupAddresses = sample.pickup.split(" + "),
                    captureKey = "readme-${sample.minutesAgo}",
                )
            )
        }

        // Earlier days for the Stats bars: a deterministic spread of invented offers.
        (1..13).forEach { daysAgo ->
            val dayOffers = (daysAgo * 7) % 6 + 2
            repeat(dayOffers) { n ->
                val cents = 250 + ((daysAgo * 37 + n * 53) % 600)
                val meters = 2_000 + ((daysAgo * 211 + n * 389) % 6_000)
                offers.insert(
                    OfferRecord(
                        capturedAt = now - daysAgo * 86_400_000L - n * 1_800_000L,
                        platform = if ((daysAgo + n) % 3 == 0) "Bolt" else "Wolt",
                        packageName = if ((daysAgo + n) % 3 == 0) CourierSignals.BOLT_PACKAGE else CourierSignals.WOLT_PACKAGE,
                        priceCents = cents,
                        distanceMeters = meters,
                        restaurant = "Demo Kitchen",
                        screenshotUri = "",
                        screenshotFilename = "",
                        rawText = "",
                        merchantNames = listOf("Demo Kitchen"),
                        captureKey = "readme-$daysAgo-$n",
                    )
                )
            }
        }

        val meta = CourierMetaDatabase.get(context)
        listOf(
            Triple("Sluškų g. 7, Vilnius", "Ona T.", "Bolt") to "1234",
            Triple("Vytenio g. 46, Vilnius", "Jonas K.", "Wolt") to null,
            Triple("Aguonų g. 17B, Vilnius", "Rūta S.", "Bolt") to "K0457",
            Triple("Žalgirio g. 24, Vilnius", "Tomas V.", "Bolt") to null,
            Triple("Čiurlionio g. 9, Vilnius", "Eglė M.", "Wolt") to "25#",
            Triple("Naugarduko g. 50, Vilnius", "Lukas B.", "Wolt") to null,
        ).forEachIndexed { index, (address, code) ->
            (index % 3 downTo 0).forEach { visit ->
                meta.saveAddressObservation(address.first, address.third, address.second, null, "", now - index * 3_600_000L - visit * 86_400_000L)
            }
            if (code != null) {
                val key = CourierSignals.normalizeBuildingAddress(address.first)!!
                meta.saveAccessCode(AccessCodeObservation(key.first, key.second, code), address.third, now)
            }
        }
        meta.writableDatabase.insert(
            "work_sessions",
            null,
            ContentValues().apply {
                put("started_at", now - 41 * 60_000L)
                put("start_reason", "readme")
            },
        )
        CourierPresence.markOfferOnline(context, CourierSignals.WOLT_PACKAGE, now = now)
    }

    private data class Sample(
        val platform: String,
        val venue: String,
        val pickup: String,
        val cents: Int,
        val meters: Int,
        val minutesAgo: Int,
    )

    private fun tapNav(root: View, index: Int) {
        val density = root.resources.displayMetrics.density
        tap(root, root.width / 10f * (index * 2 + 1), root.height - 50 * density)
    }

    private fun tap(root: View, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0))
        settle()
    }

    private fun settle() {
        repeat(30) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private fun save(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(outDir!!).mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
