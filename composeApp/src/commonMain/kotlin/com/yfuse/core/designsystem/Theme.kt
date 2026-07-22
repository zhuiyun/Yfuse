package com.yfuse.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Yfuse uses a light, calm palette: soft off-white surfaces, a muted
 * slate-blue accent, low saturation throughout. Generous whitespace and
 * quiet contrast are applied at the component level.
 */
private val YfuseLightColors = lightColorScheme(
    primary = Color(0xFF4C6B8A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E3EF),
    onPrimaryContainer = Color(0xFF13293C),
    secondary = Color(0xFF6B7684),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E7EC),
    onSecondaryContainer = Color(0xFF272E36),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1F2933),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2933),
    surfaceVariant = Color(0xFFECEFF3),
    onSurfaceVariant = Color(0xFF5B6672),
    outline = Color(0xFFCED4DB),
    outlineVariant = Color(0xFFE2E6EB),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun YfuseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YfuseLightColors,
        content = content,
    )
}
