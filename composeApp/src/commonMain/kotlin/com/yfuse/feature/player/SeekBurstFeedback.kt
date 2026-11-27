package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import kotlin.math.roundToInt

@Composable
internal fun SeekBurstFeedback(
    revision: Int,
    position: Offset,
    itemKey: Any,
) {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val pulse = remember(itemKey) { Animatable(1f) }
    var active by remember(itemKey) { mutableStateOf(false) }
    // The revision whose pulse has already played out. Without it the two full-screen layers below
    // stayed in the tree — measured, laid out and composited — for the whole film to draw nothing.
    var finishedRevision by remember(itemKey) { mutableIntStateOf(0) }
    LaunchedEffect(revision, moving, pulse) {
        if (revision == 0 || !moving) {
            active = false
            pulse.snapTo(1f)
        } else {
            active = true
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(Motion.PLAYER_SEEK_FEEDBACK, easing = Motion.Curve))
            active = false
            finishedRevision = revision
        }
    }
    // Composed from the press that starts the pulse until the pulse ends, so [BurstIcon] is in
    // the tree before [active] turns on — it deliberately does not burst on its first frame.
    if (!moving || revision == 0 || revision == finishedRevision) return
    val density = LocalDensity.current
    val chevronPath = remember { Path() }
    val innerRingStroke = remember(density) { Stroke(with(density) { 1.5.dp.toPx() }) }
    val outerRingStroke = remember(density) { Stroke(with(density) { 1.dp.toPx() }) }
    val chevronStroke =
        remember(density) {
            Stroke(width = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
    Canvas(Modifier.fillMaxSize()) {
        val t = pulse.value
        val remaining = (1f - t).coerceIn(0f, 1f)
        if (remaining > 0f) {
            val isRewind = position.x < size.width / 2f
            // Soft directional edge wash on the tapped half of the screen
            val washCenter = Offset(if (isRewind) 0f else size.width, size.height * 0.5f)
            val washRadius = (size.width * 0.42f).coerceAtLeast(120.dp.toPx())
            drawOval(
                brush =
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.14f * remaining),
                        0.65f to Color.White.copy(alpha = 0.05f * remaining),
                        1f to Color.Transparent,
                        center = washCenter,
                        radius = washRadius,
                    ),
                topLeft = Offset(washCenter.x - washRadius, washCenter.y - size.height * 0.65f),
                size = Size(washRadius * 2f, size.height * 1.3f),
            )
            // Dual concentric ripples centered on tap point
            val radius = (24.dp.toPx() + 64.dp.toPx() * t).coerceAtMost(size.minDimension / 2f)
            drawCircle(Color.White.copy(alpha = 0.12f * remaining), radius, position)
            drawCircle(Color.White.copy(alpha = 0.48f * remaining), radius, position, style = innerRingStroke)
            val outerRadius = (36.dp.toPx() + 92.dp.toPx() * t).coerceAtMost(size.minDimension / 2f)
            drawCircle(
                Color.White.copy(alpha = 0.22f * remaining * remaining),
                outerRadius,
                position,
                style = outerRingStroke,
            )
            // Three staggered directional chevrons flowing outward from the tap point
            val dir = if (isRewind) -1f else 1f
            val chevronSize = 7.dp.toPx()
            repeat(3) { index ->
                val phase = (t * 1.35f - index * 0.18f).coerceIn(0f, 1f)
                val chevronAlpha = kotlin.math.sin(phase * kotlin.math.PI).toFloat().coerceIn(0f, 1f) * remaining
                if (chevronAlpha > 0.01f) {
                    val cx = position.x + dir * (28.dp.toPx() + index * 11.dp.toPx() + 14.dp.toPx() * t)
                    val cy = position.y
                    chevronPath.rewind()
                    chevronPath.moveTo(cx - dir * chevronSize * 0.55f, cy - chevronSize)
                    chevronPath.lineTo(cx + dir * chevronSize * 0.55f, cy)
                    chevronPath.lineTo(cx - dir * chevronSize * 0.55f, cy + chevronSize)
                    drawPath(chevronPath, Color.White.copy(alpha = 0.85f * chevronAlpha), style = chevronStroke)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val half = with(density) { 20.dp.toPx() }
        BurstIcon(
            icon = if (position.x < constraints.maxWidth / 2f) AppIcons.Rewind else AppIcons.Forward,
            active = active,
            contentDescription = null,
            tint = Color.White,
            burstColor = Color.White,
            iconSize = 24.dp,
            modifier =
                Modifier
                    .offset {
                        IntOffset((position.x - half).roundToInt(), (position.y - half).roundToInt())
                    }.size(40.dp)
                    .graphicsLayer { alpha = 1f - pulse.value },
        )
    }
}
