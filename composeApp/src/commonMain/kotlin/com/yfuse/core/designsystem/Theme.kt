package com.yfuse.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Selectable accent colours; the prototype's default is the blue one. */
enum class AccentColor(val label: String, val value: Long) {
    Blue("蓝", 0xFF4C6BF5),
    Purple("紫", 0xFF8B5CF6),
    Teal("青", 0xFF14B8A6),
    Orange("橙", 0xFFF59E0B),
    Pink("粉", 0xFFEC4899),
    Green("绿", 0xFF22C55E),
    ;

    val color: Color get() = Color(value)
}

/** Light / dark / follow-system. */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Dark("深色"),
    Light("浅色"),
}

/**
 * Extra tokens the Material scheme has no slot for: the translucent "glass"
 * layers, their hairline borders, and the page backdrop wash.
 */
data class GlassTokens(
    val surface: Color,
    val surfaceStrong: Color,
    val border: Color,
    val borderStrong: Color,
    val scrim: Color,
    val backdropTop: Color,
    val backdropBottom: Color,
    val onGlass: Color,
    val onGlassMuted: Color,
    val isDark: Boolean,
)

private val DarkGlass = GlassTokens(
    surface = Color(0x14FFFFFF),
    surfaceStrong = Color(0x24FFFFFF),
    border = Color(0x1FFFFFFF),
    borderStrong = Color(0x33FFFFFF),
    scrim = Color(0xCC0B0D12),
    backdropTop = Color(0xFF12141A),
    backdropBottom = Color(0xFF0A0B0F),
    onGlass = Color(0xFFF2F4F8),
    onGlassMuted = Color(0xFF9AA3B2),
    isDark = true,
)

// "轻雾玻璃": cool-white base, frosted white panels, azure accent.
private val LightGlass = GlassTokens(
    surface = Color(0xB8FFFFFF),
    surfaceStrong = Color(0xF2FFFFFF),
    border = Color(0x14101828),
    borderStrong = Color(0x24101828),
    scrim = Color(0xB8FFFFFF),
    backdropTop = Color(0xFFEEF0F4),
    backdropBottom = Color(0xFFE7E9EE),
    onGlass = Color(0xFF141922),
    onGlassMuted = Color(0xFF6B7480),
    isDark = false,
)

val LocalGlass = staticCompositionLocalOf { DarkGlass }
val LocalAccent = staticCompositionLocalOf { AccentColor.Blue }

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.22f),
    onPrimaryContainer = Color(0xFFE8ECFF),
    secondary = Color(0xFF9AA3B2),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF14161C),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF1D212A),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF2A2F3A),
    outlineVariant = Color(0xFF20242D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0B0B),
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.16f),
    onPrimaryContainer = Color(0xFF10214D),
    secondary = Color(0xFF6B7480),
    background = Color(0xFFE9EBEE),
    onBackground = Color(0xFF141922),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141922),
    surfaceVariant = Color(0xFFE2E5EB),
    onSurfaceVariant = Color(0xFF6B7480),
    outline = Color(0xFFD0D5DE),
    outlineVariant = Color(0xFFE1E4EA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun YfuseTheme(
    dark: Boolean,
    accent: AccentColor,
    content: @Composable () -> Unit,
) {
    val glass = if (dark) DarkGlass else LightGlass
    CompositionLocalProvider(LocalGlass provides glass, LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(accent.color) else lightScheme(accent.color),
            content = content,
        )
    }
}
