package com.yfuse.core.designsystem

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.isSpecified

internal val LocalThemeColorTarget = compositionLocalOf<ThemeColors?> { null }

private class ThemeConsumerMemory(
    var theme: ThemeColors?,
    var color: Color,
    var shown: State<Color>? = null,
)

/** Ordinary status/artwork animations pass through. Only a theme target change starts this clock. */
@Composable
internal fun rememberThemeConsumerColor(target: Color): State<Color> {
    val theme = LocalThemeColorTarget.current
    val reduced = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
    val memory = remember { ThemeConsumerMemory(theme, target) }
    val animation =
        remember(theme) {
            val visible = Snapshot.withoutReadObservation { memory.shown?.value ?: memory.color }
            if (theme != memory.theme &&
                visible.isSpecified &&
                target.isSpecified &&
                visible != target &&
                !reduced
            ) {
                Animatable(visible)
            } else {
                null
            }
        }
    var finished by remember(animation) { mutableStateOf(animation == null) }
    val shown = if (!finished && animation != null) animation.asState() else rememberUpdatedState(target)
    SideEffect {
        memory.theme = theme
        memory.color = target
        memory.shown = shown
    }
    if (animation != null && !finished) {
        LaunchedEffect(animation, target, reduced) {
            if (reduced || !target.isSpecified) {
                animation.snapTo(if (target.isSpecified) target else animation.value)
            } else {
                animation.animateTo(target, tween(THEME_CROSSFADE_MS, easing = Motion.Curve))
            }
            finished = true
        }
    }
    return shown
}

@Composable
internal fun Modifier.themeBackground(
    color: Color,
    shape: Shape = RectangleShape,
): Modifier {
    val shown = rememberThemeConsumerColor(color)
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawOutline(outline, shown.value) }
    }
}
