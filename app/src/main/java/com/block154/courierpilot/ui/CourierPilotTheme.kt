package com.block154.courierpilot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B1220)
val InkElevated = Color(0xFF111B2E)
val BrandBlue = Color(0xFF2563EB)
val BrandCyan = Color(0xFF22B8CF)
val Success = Color(0xFF16A34A)
val Warning = Color(0xFFF59E0B)
val Danger = Color(0xFFDC2626)
val Purple = Color(0xFF7C3AED)
val Muted = Color(0xFF64748B)
val LightBackground = Color(0xFFF3F6FA)
val LightBorder = Color(0xFFDCE3EC)
val BlueTint = Color(0xFFEFF6FF)
val CyanTint = Color(0xFFECFEFF)
val GreenTint = Color(0xFFF0FDF4)
val AmberTint = Color(0xFFFFFBEB)
val VioletTint = Color(0xFFF5F3FF)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = BrandCyan,
    tertiary = Purple,
    background = LightBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAF0F6),
    onSurfaceVariant = Muted,
    outline = LightBorder,
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CB7FF),
    onPrimary = Color(0xFF002F65),
    secondary = Color(0xFF55D6E8),
    tertiary = Color(0xFFB7A0FF),
    background = Color(0xFF080D17),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF101827),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF152033),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF243247),
    error = Color(0xFFFF8A80),
)

@Composable
fun CourierPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
