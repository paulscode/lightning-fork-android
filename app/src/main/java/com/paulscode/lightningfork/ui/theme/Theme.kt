package com.paulscode.lightningfork.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = SurfaceRaised,
    onPrimaryContainer = TextPrimary,
    secondary = AccentSky,
    onSecondary = Page,
    tertiary = Success,
    background = Page,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextMuted,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = SurfaceRaised,
    surfaceContainerLow = SurfaceSoft,
    surfaceContainerLowest = Page,
    outline = BorderStrong,
    outlineVariant = Border,
    error = Danger,
    onError = Color.White,
    scrim = Color(0xCC02060F),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The dashboard's dark theme; the app has no light one. */
@Composable
fun LightningForkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
