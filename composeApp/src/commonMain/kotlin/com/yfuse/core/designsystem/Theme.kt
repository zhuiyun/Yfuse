package com.yfuse.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
    One("云端跃入", "轻快水滴落入云端，普通启动约 0.5 秒"),
    Two("涟漪绽放", "柔和水滴积成涟漪，普通启动约 0.5 秒"),
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

/**
 * The user's semantic emphasis colours, resolved for the active light/dark surface.
 *
 * [Brand] remains the immutable product identity used by the logo and artwork-derived
 * treatments. Interactive controls consume this object instead, so changing the user's
 * [AccentColor] changes buttons and selected states without recolouring the brand itself.
 * [accent] and [onAccent] clear 4.5:1 against their intended surfaces; [container] is an
 * opaque quiet selection fill, and [border] is the visible selected/focus edge.
 */
@Immutable
data class AccentColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val border: Color,
)

private const val MinimumAccentContrast = 4.5f
private val DarkAccentSurface = Color(0xFF182235)
private val DarkAccentInk = Color(0xFF0B111C)

private fun contrastRatio(first: Color, second: Color): Float {
    val light = maxOf(first.luminance(), second.luminance())
    val dark = minOf(first.luminance(), second.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}

/** Pure resolver for semantic accents used by theme code, previews and tests. */
fun resolveAccentColors(base: Color, dark: Boolean): AccentColors {
    val surface = if (dark) DarkAccentSurface else Color.White
    val adjustmentTarget = if (dark) Color.White else Color.Black
    val containerBlend = if (dark) 0.14f else 0.08f
    val accent = (0..20).firstNotNullOfOrNull { step ->
        val candidate = lerp(base, adjustmentTarget, step / 20f)
        val container = lerp(surface, candidate, containerBlend)
        candidate.takeIf {
            contrastRatio(it, surface) >= MinimumAccentContrast &&
                contrastRatio(it, container) >= MinimumAccentContrast
        }
    } ?: adjustmentTarget
    val container = lerp(surface, accent, containerBlend)
    val onAccent = listOf(Color.White, DarkAccentInk)
        .maxBy { contrastRatio(it, accent) }
    return AccentColors(
        accent = accent,
        onAccent = onAccent,
        container = container,
        border = accent,
    )
}

fun AccentColor.resolveColors(dark: Boolean): AccentColors = resolveAccentColors(color, dark)

val LocalAccentColors = staticCompositionLocalOf {
    AccentColor.Blue.resolveColors(dark = false)
}

/** Compatibility local for preference and preview UIs that need the selected enum itself. */
val LocalAccent = staticCompositionLocalOf { AccentColor.Blue }

/**
 * Resolves the selected accent for a surface whose luminance contract is independent from the
 * app theme. The player is the canonical example: its chrome always sits on a dark video
 * surface, even while the rest of the app is using the light theme.
 */
@Composable
fun rememberAccentColorsForSurface(dark: Boolean): AccentColors {
    val accent = LocalAccent.current
    return remember(accent, dark) { accent.resolveColors(dark) }
}

@Immutable
data class AccessibilityOptions(
    val reduceTransparency: Boolean = false,
    val largeText: Boolean = false,
    val reduceMotion: Boolean = false,
)

val LocalAccessibilityOptions = staticCompositionLocalOf { AccessibilityOptions() }

private fun darkScheme(accent: AccentColors) = darkColorScheme(
    primary = accent.accent,
    onPrimary = accent.onAccent,
    primaryContainer = accent.container,
    onPrimaryContainer = accent.accent,
    secondary = DarkPalette.sub,
    tertiary = accent.accent,
    onTertiary = accent.onAccent,
    tertiaryContainer = accent.container,
    onTertiaryContainer = accent.accent,
    background = DarkPalette.background,
    onBackground = DarkPalette.text,
    surface = DarkPalette.card,
    onSurface = DarkPalette.text,
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = DarkPalette.sub2,
    outline = Color(0xFF2A2F3A),
    outlineVariant = Color(0xFF20242D),
    error = DarkPalette.error,
    onError = DarkPalette.onError,
    errorContainer = DarkPalette.errorContainer,
    onErrorContainer = DarkPalette.onErrorContainer,
)

private fun lightScheme(accent: AccentColors) = lightColorScheme(
    primary = accent.accent,
    onPrimary = accent.onAccent,
    primaryContainer = accent.container,
    onPrimaryContainer = accent.accent,
    secondary = LightPalette.sub,
    tertiary = accent.accent,
    onTertiary = accent.onAccent,
    tertiaryContainer = accent.container,
    onTertiaryContainer = accent.accent,
    background = LightPalette.background,
    onBackground = LightPalette.text,
    surface = LightPalette.card,
    onSurface = LightPalette.text,
    surfaceVariant = Color(0xFFE2E5EB),
    onSurfaceVariant = LightPalette.sub2,
    outline = Color(0xFFD0D5DE),
    outlineVariant = Color(0xFFE1E4EA),
    error = LightPalette.error,
    onError = LightPalette.onError,
    errorContainer = LightPalette.errorContainer,
    onErrorContainer = LightPalette.onErrorContainer,
)

@Composable
fun YfuseTheme(
    dark: Boolean,
    accent: AccentColor,
    accessibility: AccessibilityOptions = AccessibilityOptions(),
    content: @Composable () -> Unit,
) {
    val palette = if (dark) DarkPalette else LightPalette
    val accentColors = remember(accent, dark) { accent.resolveColors(dark) }
    val density = LocalDensity.current
    val adjustedDensity = if (accessibility.largeText) {
        Density(density.density, density.fontScale * 1.12f)
    } else {
        density
    }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides accent,
        LocalAccentColors provides accentColors,
        LocalAccessibilityOptions provides accessibility,
        LocalDensity provides adjustedDensity,
        LocalHaptics provides rememberHaptics(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(accentColors) else lightScheme(accentColors),
            typography = AppTypography.material,
            shapes = AppShapes.material,
            content = content,
        )
    }
}
