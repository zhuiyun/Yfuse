package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp

/** The two colour sets a theme change swaps: everything the page paints with. */
@Immutable
data class ThemeColors(
    val palette: Palette,
    val accent: AccentColors,
)

/**
 * Switching 深色/浅色 or the accent used to repaint every surface on one frame. The colours
 * now travel: the old set and the new set are blended over [THEME_CROSSFADE_MS], so the
 * page looks like it changed rather than like it was replaced. `isDark` flips at once —
 * it is a policy, not a colour — and reduced motion keeps the instant swap.
 */
@Composable
fun rememberThemeCrossfade(
    target: ThemeColors,
    reduceMotion: Boolean,
): ThemeColors {
    val state = remember { ThemeCrossfadeState(target) }
    LaunchedEffect(target, reduceMotion) { state.retarget(target, animate = !reduceMotion) }
    return state.shown()
}

private class ThemeCrossfadeState(
    initial: ThemeColors,
) {
    private var from by mutableStateOf(initial)
    private var to by mutableStateOf(initial)
    private val progress = Animatable(1f)

    fun shown(): ThemeColors {
        val t = progress.value
        return if (t >= 1f) to else lerpThemeColors(from, to, t)
    }

    suspend fun retarget(
        target: ThemeColors,
        animate: Boolean,
    ) {
        if (target == to) return
        from = shown()
        to = target
        if (!animate) {
            progress.snapTo(1f)
            return
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(THEME_CROSSFADE_MS, easing = Motion.Curve))
    }
}

internal fun lerpThemeColors(
    from: ThemeColors,
    to: ThemeColors,
    t: Float,
): ThemeColors {
    // Colour interpolation goes through a perceptual space and back, so even t = 0 would
    // land a rounding step away from the real set; the ends are the real sets, exactly.
    if (t <= 0f) return from
    if (t >= 1f) return to
    return ThemeColors(
        palette = lerpPalette(from.palette, to.palette, t),
        accent =
            AccentColors(
                accent = lerp(from.accent.accent, to.accent.accent, t),
                onAccent = lerp(from.accent.onAccent, to.accent.onAccent, t),
                container = lerp(from.accent.container, to.accent.container, t),
                border = lerp(from.accent.border, to.accent.border, t),
            ),
    )
}

internal fun lerpPalette(
    from: Palette,
    to: Palette,
    t: Float,
): Palette =
    Palette(
        background = lerp(from.background, to.background, t),
        text = lerp(from.text, to.text, t),
        sub = lerp(from.sub, to.sub, t),
        sub2 = lerp(from.sub2, to.sub2, t),
        body = lerp(from.body, to.body, t),
        hint = lerp(from.hint, to.hint, t),
        error = lerp(from.error, to.error, t),
        onError = lerp(from.onError, to.onError, t),
        errorContainer = lerp(from.errorContainer, to.errorContainer, t),
        onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, t),
        card = lerp(from.card, to.card, t),
        card2 = lerp(from.card2, to.card2, t),
        card3 = lerp(from.card3, to.card3, t),
        sheet = lerp(from.sheet, to.sheet, t),
        glass = lerp(from.glass, to.glass, t),
        glassStrong = lerp(from.glassStrong, to.glassStrong, t),
        border = lerp(from.border, to.border, t),
        tabbarBorder = lerp(from.tabbarBorder, to.tabbarBorder, t),
        // Contrast rules, status-bar icons and material choice follow the destination from
        // the first frame; only the paint is in between.
        isDark = to.isDark,
    )

const val THEME_CROSSFADE_MS = 380
