package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** Only numeric bounds are retained, never a view, context, image, or layout coordinates. */
internal object SearchDockOrigin {
    var bounds: Rect? = null
    private var pending: Rect? = null

    fun begin() {
        pending = bounds
    }

    fun consume(): Rect? = pending.also { pending = null }
}

internal fun Modifier.searchDockSource(): Modifier =
    onGloballyPositioned { SearchDockOrigin.bounds = it.boundsInWindow() }

/** A navigation click grants one morph. Query edits, pages and returning to the route do not. */
@Composable
internal fun Modifier.searchFieldArrival(): Modifier {
    val origin = remember { SearchDockOrigin.consume() }
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    var target by remember { mutableStateOf<Rect?>(null) }
    val progress = remember { Animatable(if (origin == null || !moving) 1f else 0f) }
    LaunchedEffect(target, moving) {
        if (!moving) {
            progress.snapTo(1f)
        } else if (target != null
        ) {
            progress.animateTo(
                1f,
                tween(Motion.MODAL, easing = Motion.Curve),
            )
        }
    }
    return onGloballyPositioned {
        // Capture the untransformed bounds once. Later graphics-layer positions are not targets.
        if (target == null) target = it.boundsInWindow()
    }.graphicsLayer {
        val destination = target
        if (moving && origin != null && destination != null && destination.width > 0f && destination.height > 0f) {
            val p = progress.value
            val remaining = 1f - p
            translationX = (origin.center.x - destination.center.x) * remaining
            translationY = (origin.center.y - destination.center.y) * remaining
            scaleX = 1f + (origin.width / destination.width - 1f) * remaining
            scaleY = 1f + (origin.height / destination.height - 1f) * remaining
            alpha = 0.35f + 0.65f * p
        }
    }
}
