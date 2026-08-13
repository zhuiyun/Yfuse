package com.yfuse.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Per-item accent. The prototype derives it from the artwork; this stays as the
 * fallback used before a poster's dominant colour resolves.
 */
enum class AccentColor(
    val label: String,
    val value: Long,
) {
    Blue("蓝", 0xFF3D64C9),
    Purple("紫", 0xFF8B6FAE),
    Teal("青", 0xFF3FA89A),
    Orange("橙", 0xFFC07A4A),
    Pink("粉", 0xFFC98FA4),
    Green("绿", 0xFF5F9F6F),

    // The second row. Six swatches left obvious gaps — no red, no yellow, nothing between
    // 蓝 and 紫 — and a colour preference is picked by eye from what is on offer, so the
    // gaps were the answer for anyone who wanted one of them. New entries only ever append:
    // the choice persists by enum name, and reordering would repaint everyone's app.
    Red("红", 0xFFC2564C),
    Amber("黄", 0xFFC0982F),
    Sky("天蓝", 0xFF4E93C4),
    Indigo("靛", 0xFF5B5FB0),

    /**
     * Not a colour — the answer "don't pick one".
     *
     * Every other entry paints every emphasis in the app the same hue, whatever is on
     * screen. This one hands that decision to the artwork: buttons, selected states and
     * highlights take the colour of the poster or backdrop the page is currently showing,
     * which is what 影视详情页 has always done for its own controls. [value] is only the
     * fallback for pages that have no artwork to take a colour from — settings, servers,
     * search — and it is the design's own brand blue, so those pages look like the app
     * shipped rather than like a page whose colour failed to load.
     */
    Artwork("跟随封面", 0xFF3D64C9),
    ;

    val color: Color get() = Color(value)

    /** True for the entry that defers to what is on screen instead of naming a colour. */
    val followsArtwork: Boolean get() = this == Artwork
}

/**
 * Which glass the app is made of.
 *
 * Both were already implemented and in use — [Modifier.glass] draws a soft diagonal sheen,
 * [Modifier.liquidGlass] adds a body ramp and a specular highlight — but which surface got
 * which was a decision made per call site, and the user had no say at all. 减弱透明度 was the
 * only nearby control and it is an accessibility switch that flattens everything.
 */
enum class GlassStyle(
    val label: String,
) {
    /** Softer and quieter: the sheen without the specular. */
    Frosted("毛玻璃"),

    /** The product default — lit edges, a body ramp, and a highlight that reads as a surface. */
    Liquid("液态玻璃"),
}

/** Light / dark / follow-system. */
enum class ThemeMode(
    val label: String,
) {
    System("跟随系统"),
    Dark("深色"),
    Light("浅色"),
}

/**
 * Which mark a launch is drawn around.
 *
 * A logo and the animation that assembles it are one decision, not two: the water-fire
 * choreographies unfold that mark's ribbon, and the cloud ones are built entirely out of the
 * cloud player's own shapes. Pairing them here means neither settings UI nor the splash has
 * to carry a table of which goes with which.
 */
enum class SplashMark(
    val label: String,
) {
    /** The current mark. */
    WaterFire("当前 Logo"),

    /** The mark the app carried before it. */
    CloudPlayer("云朵播放器 Logo"),
}

/**
 * Which launch choreography plays. Each one is a self-contained implementation; adding a
 * variant here is enough to make it selectable.
 *
 * Entries only ever append — the choice persists by name, and reordering or renaming would
 * hand somebody an animation they never chose.
 */
enum class SplashAnimation(
    val label: String,
    val description: String,
    val mark: SplashMark,
) {
    One("折带展开", "速度线冲入 → 折带绕轴展开 → 渐变线拉出", SplashMark.WaterFire),
    Two("水火交接", "标志弹入 → 光缝扫过 → 字标浮起", SplashMark.WaterFire),

    // The two the cloud mark shipped with, kept with it rather than retired: the drop, the
    // cloud taking the hit, and the splash gathering into the play head are that logo's own
    // story, and they read as nothing at all next to the water-fire ribbon.
    CloudDrop("水滴入云", "水滴坠落 → 云朵回弹 → 水花聚成播放键", SplashMark.CloudPlayer),
    CloudWell("水漾成键", "水滴落进凹槽 → 沸腾冒泡 → 水花四溅成播放键", SplashMark.CloudPlayer),
}

/** The choreographies drawn around this mark, in the order they are offered. */
val SplashMark.animations: List<SplashAnimation>
    get() = SplashAnimation.entries.filter { it.mark == this }

/** What a switch to this mark selects when the current animation belongs to the other one. */
val SplashMark.defaultAnimation: SplashAnimation
    get() = animations.first()

/**
 * The single definition of "is the UI dark right now". The splash, the window background and
 * the app itself all have to agree — when they each rolled their own `== ThemeMode.Dark` the
 * launch traded a black frame for a white one before the first screen appeared.
 */
fun ThemeMode.resolveDark(systemDark: Boolean): Boolean =
    when (this) {
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

private fun contrastRatio(
    first: Color,
    second: Color,
): Float {
    val light = maxOf(first.luminance(), second.luminance())
    val dark = minOf(first.luminance(), second.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}

/** Pure resolver for semantic accents used by theme code, previews and tests. */
fun resolveAccentColors(
    base: Color,
    dark: Boolean,
): AccentColors {
    val surface = if (dark) DarkAccentSurface else Color.White
    val adjustmentTarget = if (dark) Color.White else Color.Black
    val containerBlend = if (dark) 0.14f else 0.08f
    val accent =
        (0..20).firstNotNullOfOrNull { step ->
            val candidate = lerp(base, adjustmentTarget, step / 20f)
            val container = lerp(surface, candidate, containerBlend)
            candidate.takeIf {
                contrastRatio(it, surface) >= MinimumAccentContrast &&
                    contrastRatio(it, container) >= MinimumAccentContrast
            }
        } ?: adjustmentTarget
    val container = lerp(surface, accent, containerBlend)
    val onAccent =
        listOf(Color.White, DarkAccentInk)
            .maxBy { contrastRatio(it, accent) }
    return AccentColors(
        accent = accent,
        onAccent = onAccent,
        container = container,
        border = accent,
    )
}

fun AccentColor.resolveColors(dark: Boolean): AccentColors = resolveAccentColors(color, dark)

val LocalAccentColors =
    staticCompositionLocalOf {
        AccentColor.Blue.resolveColors(dark = false)
    }

/** Compatibility local for preference and preview UIs that need the selected enum itself. */
val LocalAccent = staticCompositionLocalOf { AccentColor.Blue }

/**
 * The colour of whatever the page currently on screen is showing, or null where it shows no
 * artwork at all. Published by the screens that already derive one — see [ArtworkAccent].
 */
val LocalArtworkAccent = staticCompositionLocalOf<Color?> { null }

/**
 * Runs [content] with the interactive accent taken from [color] while 跟随封面 is the user's
 * choice; a no-op under every other choice, and under any choice when [color] is null.
 *
 * Re-providing [LocalAccentColors] rather than asking each control to look up the artwork is
 * what makes this reach all of them: buttons, chips, switches, selected tabs and focus rings
 * already read that local, so a page publishes one colour and its whole surface follows.
 */
@Composable
fun ArtworkAccent(
    color: Color?,
    content: @Composable () -> Unit,
) {
    val choice = LocalAccent.current
    val dark = LocalPalette.current.isDark
    if (!choice.followsArtwork || color == null) {
        content()
        return
    }
    val colors = remember(color, dark) { resolveAccentColors(color, dark) }
    CompositionLocalProvider(
        LocalArtworkAccent provides color,
        LocalAccentColors provides colors,
        content = content,
    )
}

/**
 * Resolves the selected accent for a surface whose luminance contract is independent from the
 * app theme. The player is the canonical example: its chrome always sits on a dark video
 * surface, even while the rest of the app is using the light theme.
 */
@Composable
fun rememberAccentColorsForSurface(dark: Boolean): AccentColors {
    val accent = LocalAccent.current
    // 跟随封面 reaches this path too, for surfaces composed inside a page that publishes an
    // artwork colour. The player is its own activity and publishes none, so its chrome uses
    // the fallback — which is the right answer there anyway: it sits on video, not a poster.
    val artwork = LocalArtworkAccent.current
    val base = if (accent.followsArtwork && artwork != null) artwork else accent.color
    return remember(base, dark) { resolveAccentColors(base, dark) }
}

@Immutable
data class AccessibilityOptions(
    val reduceTransparency: Boolean = false,
    val largeText: Boolean = false,
    val reduceMotion: Boolean = false,
)

val LocalAccessibilityOptions = staticCompositionLocalOf { AccessibilityOptions() }

private fun darkScheme(accent: AccentColors) =
    darkColorScheme(
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

private fun lightScheme(accent: AccentColors) =
    lightColorScheme(
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
    glassStyle: GlassStyle = GlassStyle.Liquid,
    content: @Composable () -> Unit,
) {
    val palette = if (dark) DarkPalette else LightPalette
    val accentColors = remember(accent, dark) { accent.resolveColors(dark) }
    val density = LocalDensity.current
    val adjustedDensity =
        if (accessibility.largeText) {
            Density(density.density, density.fontScale * 1.12f)
        } else {
            density
        }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides accent,
        LocalAccentColors provides accentColors,
        LocalAccessibilityOptions provides accessibility,
        LocalGlassStyle provides glassStyle,
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
