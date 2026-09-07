package com.yfuse.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun rememberCarouselCaptionProgress(selected: Boolean): State<Float> {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val visible = LocalRouteVisible.current
    // A restored, already selected card starts at rest, so returning from detail never replays it.
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, reduceMotion, visible) {
        when {
            reduceMotion || !visible -> progress.snapTo(1f)
            !selected -> progress.snapTo(0f)
            else -> progress.animateTo(1f, tween(Motion.CAROUSEL_CAPTION, easing = LinearEasing))
        }
    }
    return progress.asState()
}

fun Modifier.carouselCaptionEntry(
    progress: State<Float>,
    stage: Int,
): Modifier =
    graphicsLayer {
        translationY = carouselCaptionOffset(progress.value, stage).dp.toPx()
    }

fun Modifier.carouselArtworkMotion(
    offset: () -> Float,
    reduceMotion: Boolean,
): Modifier =
    graphicsLayer {
        val visual = carouselArtworkVisual(offset(), reduceMotion)
        scaleX = visual.scale
        scaleY = visual.scale
        translationX = size.width * visual.translationFraction
    }

/** Observe presses without consuming the pager's drag or any child control's click. */
fun Modifier.carouselTouchPause(touched: MutableState<Boolean>): Modifier =
    pointerInput(touched) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touched.value = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                touched.value = false
            }
        }
    }

@Composable
fun rememberCarouselPageColor(target: Color?): Color? {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val color by animateColorAsState(
        targetValue = target ?: LocalPalette.current.background,
        animationSpec = tween(if (reduceMotion) 0 else Motion.CAROUSEL_COLOR, easing = Motion.Curve),
        label = "carousel-page-color",
    )
    return color.takeIf { target != null }
}
