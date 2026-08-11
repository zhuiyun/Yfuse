package com.yfuse.core.designsystem

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/** 0f collapsed, 1f expanded; snaps when the user asks for reduced motion. */
@Composable
fun animateExpansionAsState(
    expanded: Boolean,
    label: String = "expansion",
): State<Float> = animateFloatAsState(
    targetValue = if (expanded) 1f else 0f,
    animationSpec = Motion.settle(LocalAccessibilityOptions.current.reduceMotion),
    label = label,
)

/** A shared rotation transition for chevrons and disclosure controls. */
@Composable
fun animateRotationAsState(
    targetDegrees: Float,
    label: String = "rotation",
): State<Float> = animateFloatAsState(
    targetValue = targetDegrees,
    animationSpec = Motion.settle(LocalAccessibilityOptions.current.reduceMotion),
    label = label,
)

/** Layout expansion without `animateContentSize` continuing under reduced motion. */
@Composable
fun Modifier.motionAwareAnimateContentSize(): Modifier =
    if (LocalAccessibilityOptions.current.reduceMotion) {
        this
    } else {
        animateContentSize(animationSpec = Motion.settle())
    }

/** Programmatic list movement that snaps under reduced motion. */
suspend fun LazyListState.motionAwareScrollToItem(
    index: Int,
    scrollOffset: Int = 0,
    reduceMotion: Boolean,
) {
    if (reduceMotion) scrollToItem(index, scrollOffset) else animateScrollToItem(index, scrollOffset)
}

/** Grid counterpart to [LazyListState.motionAwareScrollToItem]. */
suspend fun LazyGridState.motionAwareScrollToItem(
    index: Int,
    scrollOffset: Int = 0,
    reduceMotion: Boolean,
) {
    if (reduceMotion) scrollToItem(index, scrollOffset) else animateScrollToItem(index, scrollOffset)
}

/** Simple scroll-container counterpart to [LazyListState.motionAwareScrollToItem]. */
suspend fun ScrollState.motionAwareScrollTo(value: Int, reduceMotion: Boolean) {
    if (reduceMotion) scrollTo(value) else animateScrollTo(value)
}
