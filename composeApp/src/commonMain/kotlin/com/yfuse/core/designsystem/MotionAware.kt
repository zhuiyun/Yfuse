package com.yfuse.core.designsystem

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/**
 * A chevron turning on its own, with no body height to keep pace with — otherwise use
 * [rememberDisclosureProgress] and [disclosureRotation]. Same clock as a disclosure body
 * ([Motion.DISCLOSURE] on the house curve), so every arrow in the app turns alike; it used to
 * spring here and tween there.
 */
@Composable
fun animateRotationAsState(
    targetDegrees: Float,
    label: String = "rotation",
): State<Float> {
    val still = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
    return animateFloatAsState(
        targetValue = targetDegrees,
        animationSpec = if (still) snap() else Motion.tween(Motion.DISCLOSURE),
        label = label,
    )
}

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
suspend fun ScrollState.motionAwareScrollTo(
    value: Int,
    reduceMotion: Boolean,
) {
    if (reduceMotion) scrollTo(value) else animateScrollTo(value)
}
