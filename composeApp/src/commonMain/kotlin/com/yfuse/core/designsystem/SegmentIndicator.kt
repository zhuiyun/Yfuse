package com.yfuse.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class SegmentIndicator(
    val container: Modifier,
    val item: (Int) -> Modifier,
)

/** Measured bounds support unequal labels and RTL; only drawing consumes the moving edges. */
@Composable
internal fun rememberSegmentIndicator(
    selectedIndex: Int,
    fill: Color,
    border: Color = Color.Transparent,
    underline: Boolean = false,
): SegmentIndicator {
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val target = bounds[selectedIndex]
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val placed = remember { booleanArrayOf(false) }
    LaunchedEffect(target, reduceMotion) {
        val bounds = target ?: return@LaunchedEffect
        if (!placed[0] || reduceMotion) {
            left.snapTo(bounds.left)
            right.snapTo(bounds.right)
            placed[0] = true
        } else {
            val movingRight = bounds.center.x >= (left.value + right.value) / 2f
            coroutineScope {
                launch {
                    left.animateTo(
                        bounds.left,
                        if (movingRight) Motion.tabIndicatorTrailing() else Motion.tabIndicatorLeading(),
                    )
                }
                launch {
                    right.animateTo(
                        bounds.right,
                        if (movingRight) Motion.tabIndicatorLeading() else Motion.tabIndicatorTrailing(),
                    )
                }
            }
        }
    }
    val inMotion by remember(left, right, target) {
        derivedStateOf { target != null && (left.value != target.left || right.value != target.right) }
    }
    val lights = rememberPhaseLightCount(inMotion)
    val lightColor = LocalPalette.current.text
    return SegmentIndicator(
        Modifier.drawBehind {
            if (target != null) {
                val width = (right.value - left.value).coerceAtLeast(0f)
                if (underline) {
                    val line = 28.dp.toPx().coerceAtMost(width)
                    drawRoundRect(
                        fill,
                        Offset(left.value + (width - line) / 2f, size.height - 2.dp.toPx()),
                        Size(line, 2.dp.toPx()),
                        CornerRadius(1.dp.toPx()),
                    )
                } else if (width > 0f) {
                    translate(left = left.value) {
                        val outline = GlassShapes.chip.createOutline(Size(width, size.height), layoutDirection, this)
                        drawOutline(outline, fill)
                        drawOutline(outline, border, style = Stroke(1.dp.toPx()))
                    }
                }
                drawPhaseLight(
                    Rect(left.value, if (underline) size.height - 4.dp.toPx() else 0f, right.value, size.height),
                    0.5f,
                    lights,
                    lightColor,
                    trail = true,
                )
            }
        },
        { index ->
            Modifier.onPlaced { coordinates ->
                val position = coordinates.positionInParent()
                bounds[index] =
                    Rect(position, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            }
        },
    )
}

@Composable
internal fun selectionColor(target: Color): Color =
    animateColorAsState(
        target,
        Motion.settle(LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current),
        label = "selectionColor",
    ).value
