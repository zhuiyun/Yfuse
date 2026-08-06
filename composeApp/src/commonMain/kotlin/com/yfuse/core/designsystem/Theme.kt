package com.yfuse.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

/**
 * Per-item accent. The prototype derives it from the artwork; this stays as the
 * fallback used before a poster's dominant colour resolves.
 */
enum class AccentColor(val label: String, val value: Long) {
    Blue("蓝", 0xFF3D64C9),
    Purple("紫", 0xFF8B6FAE),
    Teal("青", 0xFF3FA89A),
    Orange("橙", 0xFFC07A4A),
    Pink("粉", 0xFFC98FA4),
    Green("绿", 0xFF5F9F6F),
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
 * Which launch choreography plays. Each one is a self-contained implementation; adding a
 * variant here is enough to make it selectable.
 */
enum class SplashAnimation(val label: String, val description: String) {
    One("动画1", "水滴落入云端，碎成播放键"),
    Two("动画2", "水滴积成一汪，炸开满屏水花"),
}

/**
 * The single definition of "is the UI dark right now". The splash, the window background and
 * the app itself all have to agree — when they each rolled their own `== ThemeMode.Dark` the
 * launch traded a black frame for a white one before the first screen appeared.
 */
fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
}

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalAccent = staticCompositionLocalOf { AccentColor.Blue }

@Immutable
data class AccessibilityOptions(
    val reduceTransparency: Boolean = false,
    val largeText: Boolean = false,
    val reduceMotion: Boolean = false,
)

val LocalAccessibilityOptions = staticCompositionLocalOf { AccessibilityOptions() }

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.22f),
    onPrimaryContainer = Color(0xFFE8ECFF),
    secondary = DarkPalette.sub,
    background = DarkPalette.background,
    onBackground = DarkPalette.text,
    surface = DarkPalette.card,
    onSurface = DarkPalette.text,
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = DarkPalette.sub2,
    outline = Color(0xFF2A2F3A),
    outlineVariant = Color(0xFF20242D),
    error = Brand.Danger,
    onError = Color.White,
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.10f),
    onPrimaryContainer = Brand.Primary,
    secondary = LightPalette.sub,
    background = LightPalette.background,
    onBackground = LightPalette.text,
    surface = LightPalette.card,
    onSurface = LightPalette.text,
    surfaceVariant = Color(0xFFE2E5EB),
    onSurfaceVariant = LightPalette.sub2,
    outline = Color(0xFFD0D5DE),
    outlineVariant = Color(0xFFE1E4EA),
    error = Brand.Danger,
    onError = Color.White,
)

@Composable
fun YfuseTheme(
    dark: Boolean,
    accent: AccentColor,
    accessibility: AccessibilityOptions = AccessibilityOptions(),
    content: @Composable () -> Unit,
) {
    val palette = if (dark) DarkPalette else LightPalette
    val density = LocalDensity.current
    val adjustedDensity = if (accessibility.largeText) {
        Density(density.density, density.fontScale * 1.12f)
    } else {
        density
    }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides accent,
        LocalAccessibilityOptions provides accessibility,
        LocalDensity provides adjustedDensity,
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(accent.color) else lightScheme(accent.color),
            content = content,
        )
    }
}
