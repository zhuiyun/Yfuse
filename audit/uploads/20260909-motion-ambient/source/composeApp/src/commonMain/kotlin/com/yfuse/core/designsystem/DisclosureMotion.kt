package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** One reversible clock drives the body height and its disclosure arrow. */
@Composable
internal fun rememberDisclosureProgress(expanded: Boolean): State<Float> {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val target = if (expanded) 1f else 0f
    // Policy changes discard an unfinished animation, including when returning to the page.
    val progress = remember(moving) { Animatable(target) }
    LaunchedEffect(expanded, moving) {
        if (moving) {
            progress.animateTo(target, tween(Motion.DISCLOSURE, easing = Motion.Curve))
        } else {
            progress.snapTo(target)
        }
    }
    return remember(progress, moving, target) {
        derivedStateOf { if (moving) progress.value else target }
    }
}

/** Keeps only one body, removes it after collapse, and reads motion during layout. */
@Composable
internal fun DisclosureContent(
    expanded: Boolean,
    progress: State<Float>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val keepContent by remember(expanded, progress) { derivedStateOf { expanded || progress.value > 0f } }
    if (keepContent) {
        Column(
            modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val height = (placeable.height * progress.value.coerceIn(0f, 1f)).roundToInt()
                    layout(placeable.width, constraints.constrainHeight(height)) {
                        placeable.placeRelative(0, 0)
                    }
                },
            content = content,
        )
    }
}
