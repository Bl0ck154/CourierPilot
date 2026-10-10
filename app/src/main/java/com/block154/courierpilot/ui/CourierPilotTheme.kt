package com.block154.courierpilot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Ink = Color(0xFF0B1220)
val InkElevated = Color(0xFF111B2E)
val BrandBlue = Color(0xFF2563EB)
val BrandCyan = Color(0xFF22B8CF)
val Success = Color(0xFF16A34A)
val Warning = Color(0xFFF59E0B)
val Danger = Color(0xFFDC2626)
val Purple = Color(0xFF7C3AED)
val Muted = Color(0xFF64748B)
val LightBackground = Color(0xFFF5F6F8)
val LightBorder = Color(0xFFDCE3EC)
val BlueTint = Color(0xFFEFF6FF)
val CyanTint = Color(0xFFECFEFF)
val GreenTint = Color(0xFFF0FDF4)
val AmberTint = Color(0xFFFFFBEB)
val VioletTint = Color(0xFFF5F3FF)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = BrandCyan,
    secondaryContainer = Color(0xFFEEF6FF),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Purple,
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFEEF0F3),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF13324A),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF55D6E8),
    secondaryContainer = Color(0xFF17283A),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFFB7A0FF),
    background = Color(0xFF0B1520),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111D2B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF17283A),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF0E1925),
    surfaceContainerLow = Color(0xFF111D2B),
    surfaceContainer = Color(0xFF111D2B),
    surfaceContainerHigh = Color(0xFF142233),
    surfaceContainerHighest = Color(0xFF17283A),
    outline = Color(0xFF2A3A4E),
    outlineVariant = Color(0xFF1A2737),
    error = Color(0xFFFF8A80),
)

/** User-selected app theme. The live card overlay is not affected: it always stays dark. */
enum class ThemeMode(val label: String, val hint: String) {
    SYSTEM("System", "Follows the phone’s dark mode"),
    LIGHT("Light", "Always light"),
    DARK("Dark", "Always dark"),
}

object AppearanceSettings {
    private const val PREFS = "courierpilot_appearance"
    private const val KEY_THEME = "theme_mode"

    // Process-wide snapshot state so every open screen re-themes as soon as the choice changes.
    private val current = mutableStateOf<ThemeMode?>(null)

    fun themeMode(context: Context): ThemeMode {
        current.value?.let { return it }
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        val mode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        current.value = mode
        return mode
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
        current.value = mode
    }
}

@Composable
fun courierPilotIsDark(): Boolean {
    val systemDark = isSystemInDarkTheme()
    return when (AppearanceSettings.themeMode(LocalContext.current)) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

@Composable
fun CourierPilotTheme(content: @Composable () -> Unit) {
    val dark = courierPilotIsDark()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // enableEdgeToEdge() picks bar icon colours from the *system* night mode. A forced
            // Light/Dark choice must flip them too, or the clock/battery icons vanish.
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                // 3-button navigation otherwise gets a translucent white scrim that reads as a
                // white strip under every screen; let the theme background run under the bar.
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalCourierPalette provides if (dark) DarkPalette else LightPalette) {
            // CourierPilot targets API 35, where Android enforces edge-to-edge. Keep all interactive
            // Compose content inside safeDrawing so status/navigation bars and display cutouts never
            // cover titles, back buttons, bottom actions, or scrolling content. Nested Material 3
            // components consume these insets and therefore do not apply them a second time.
            Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
                content()
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
