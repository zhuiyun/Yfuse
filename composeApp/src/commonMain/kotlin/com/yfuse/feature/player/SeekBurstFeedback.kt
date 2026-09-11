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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
    LaunchedEffect(revision, moving, pulse) {
        if (revision == 0 || !moving) {
            pulse.snapTo(1f)
        } else {
            active = true
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(Motion.PLAYER_SEEK_FEEDBACK, easing = Motion.Curve))
            active = false
        }
    }
    if (moving) {
        Canvas(Modifier.fillMaxSize()) {
            val remaining = 1f - pulse.value
            if (remaining > 0f) {
                val radius = (24.dp.toPx() + 56.dp.toPx() * pulse.value).coerceAtMost(size.minDimension / 2f)
                drawCircle(Color.White.copy(alpha = 0.10f * remaining), radius, position)
                drawCircle(Color.White.copy(alpha = 0.45f * remaining), radius, position, style = Stroke(1.5.dp.toPx()))
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
}
