package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Lease particles for an existing motion clock. Navigation/dialogs get no second frame loop. */
@Composable
internal fun rememberPhaseLightCount(
    active: Boolean,
    enhancedOnly: Boolean = false,
): Int {
    val level = LocalParticleLight.current
    val budget = LocalParticleBudget.current
    val limit = LocalParticleLimit.current
    val allowed =
        active &&
            LocalParticleActive.current &&
            LocalRouteVisible.current &&
            LocalWindowInfo.current.isWindowFocused &&
            !LocalAccessibilityOptions.current.reduceMotion &&
            level != ParticleLight.Off &&
            (!enhancedOnly || level == ParticleLight.Enhanced)
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(allowed, budget, limit, level) {
        var leased = 0
        if (allowed) {
            repeat(if (level == ParticleLight.Enhanced) 12 else 6) {
                if (budget.acquire(limit)) leased++
            }
        }
        count = leased
        onDispose {
            repeat(leased) { budget.release() }
            count = 0
        }
    }
    return count
}

internal fun DrawScope.drawPhaseLight(
    rect: Rect,
    progress: Float,
    count: Int,
    color: Color,
    trail: Boolean = false,
) {
    if (count <= 0 || progress <= 0f || progress >= 1f || rect.width <= 0f || rect.height <= 0f) return
    val alpha = sin(progress * PI.toFloat()).coerceAtLeast(0f) * 0.56f
    for (i in 0 until count) {
        val f = (i + 0.5f) / count
        val x = if (trail) rect.left + rect.width * (0.12f + f * 0.76f) else rect.left + rect.width * f
        val y = if (i % 2 == 0) rect.top + 1.dp.toPx() else rect.bottom - 1.dp.toPx()
        val drift = sin((progress + f) * PI.toFloat()) * 2.dp.toPx()
        val center = Offset(x + if (trail) -progress * 6.dp.toPx() else drift, y)
        val radius = (0.6f + i % 3 * 0.2f).dp.toPx()
        drawCircle(color.copy(alpha = alpha * 0.12f), radius * 2.5f, center)
        drawCircle(color.copy(alpha = alpha * (0.5f + f * 0.5f)), radius, center)
    }
}

@Composable
internal fun Modifier.phaseLightEdge(
    progress: () -> Float,
    count: Int,
): Modifier {
    val color = LocalPalette.current.text
    if (count == 0) return this
    return drawWithContent {
        drawContent()
        drawPhaseLight(Rect(Offset.Zero, size), progress(), count, color)
    }
}
