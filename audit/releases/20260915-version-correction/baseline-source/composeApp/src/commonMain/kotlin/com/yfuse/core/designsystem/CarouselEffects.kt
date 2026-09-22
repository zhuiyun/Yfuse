package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
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

/** The page theme uses its target once; only background draw nodes observe this frame clock. */
@Composable
fun rememberCarouselPageColor(target: Color?): State<Color> {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val visible = LocalRouteVisible.current
    val palette = rememberArtworkPagePalette(target)
    val targetColor = target ?: palette.background
    val previousOutput = remember { arrayOfNulls<State<Color>>(1) }
    val transition =
        remember(targetColor, palette, reduceMotion, visible) {
            // This is a retarget-time read, not a subscription that recomposes the host each frame.
            val start = Snapshot.withoutReadObservation { previousOutput[0]?.value ?: targetColor }
            val safe =
                artworkPageTransitionIsSafe(
                    start,
                    targetColor,
                    listOf(palette.text, palette.sub, palette.sub2, palette.body, palette.hint, palette.error),
                )
            PageColorTransition(start, targetColor, !reduceMotion && visible && start != targetColor && safe)
        }
    // Each retarget starts at the last displayed colour, even if its predecessor snapped before
    // its effect could run. Hidden/reduced/unsafe targets are correct on their very first draw.
    val output =
        remember(transition) {
            mutableStateOf(if (transition.animate) transition.start else transition.target)
        }
    LaunchedEffect(transition) {
        if (transition.animate) {
            animate(0f, 1f, animationSpec = tween(Motion.CAROUSEL_COLOR, easing = Motion.Curve)) { value, _ ->
                output.value = interpolateArtworkPageColor(transition.start, transition.target, value)
            }
            output.value = transition.target
        }
    }
    SideEffect { previousOutput[0] = output }
    return output
}

private data class PageColorTransition(
    val start: Color,
    val target: Color,
    val animate: Boolean,
)
