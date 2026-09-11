package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Small immutable labels/glyphs only: never duplicate a live player or a screen's effects. */
@Composable
internal fun <T> MotionSwap(
    value: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val duration = if (LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current) 0 else Motion.QUICK
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
                .using(SizeTransform(clip = false) { _, _ -> tween(duration, easing = Motion.Curve) })
        },
        contentAlignment = Alignment.Center,
        label = "small-state-handoff",
    ) { content(it) }
}

/** Keep the scroll slot stable; only the rendered hero recedes as it leaves the viewport. */
@Composable
internal fun Modifier.heroScrollCollapse(
    state: LazyListState,
    height: Dp,
): Modifier {
    val moving = !LocalAccessibilityOptions.current.reduceMotion && LocalRouteVisible.current
    return graphicsLayer {
        val amount =
            if (!moving || state.firstVisibleItemIndex != 0) {
                0f
            } else {
                (state.firstVisibleItemScrollOffset / height.toPx().coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
        transformOrigin = TransformOrigin(0.5f, 0f)
        scaleX = 1f - 0.04f * amount
        scaleY = 1f - 0.16f * amount
        translationY = height.toPx() * 0.12f * amount
        alpha = 1f - amount * amount
        clip = amount > 0f
    }
}

/** One content tree survives a window-tier/orientation change; no player Surface snapshots. */
@Composable
internal fun Modifier.windowSizeHandoff(): Modifier {
    val density = LocalDensity.current.density
    var windowClass by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val handoff = windowClass?.let { Modifier.contentHandoff(it) } ?: Modifier
    return onSizeChanged { size ->
        if (size.width > 0 && size.height > 0) {
            val width = size.width / density
            windowClass = (
                if (width < 600f) {
                    0
                } else if (width < 840f) {
                    1
                } else {
                    2
                }
            ) to (size.width > size.height)
        }
    }.then(handoff)
}
