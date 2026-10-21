package com.yfuse.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion

/** Measure the complete primary/secondary block first, including wrapped lines and bitmap bounds. */
@Composable
internal fun SubtitleRegionLayout(
    pictureSize: IntSize,
    region: SubtitleRegion,
    position: Float,
    content: @Composable () -> Unit,
) {
    // Only deliberate position changes animate; changing dialogue height must not bounce.
    val animatedPosition by animateFloatAsState(
        position,
        animationSpec = Motion.settle(LocalAccessibilityOptions.current.reduceMotion),
        label = "subtitleRegionPosition",
    )
    Layout(content = { Box { content() } }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val height = constraints.maxHeight
        val width = constraints.maxWidth
        val pictureHeight = pictureSize.height.coerceIn(0, height).takeIf { it > 0 } ?: height
        val child = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val placement =
            placeSubtitleBlock(height, pictureHeight, child.height, region, animatedPosition, 8.dp.roundToPx())
        layout(width, height) { child.place((width - child.width) / 2, placement.top) }
    }
}
