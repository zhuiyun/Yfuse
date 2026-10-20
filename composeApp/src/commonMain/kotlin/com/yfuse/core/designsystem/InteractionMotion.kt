package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun Modifier.directionalHandoff(value: Long): Modifier {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val calmMotionValue = calmMotion()
    val previous = remember { longArrayOf(value) }
    val direction = remember(value) { value.compareTo(previous[0]) }
    val progress = remember(value, moving) { Animatable(if (moving && direction != 0) 0f else 1f) }
    SideEffect { previous[0] = value }
    LaunchedEffect(progress) { if (progress.value < 1f) progress.animateTo(1f, Motion.tween(Motion.QUICK)) }
    return graphicsLayer {
        alpha = progress.value
        translationX = if (calmMotionValue) 0f else 16.dp.toPx() * direction * (1f - progress.value)
    }
}

/** A brief locator on return. It never moves the row or repeats while the page is covered. */
@Composable
internal fun Modifier.returnLocator(active: Boolean): Modifier {
    val visible = LocalRouteVisible.current
    val still = LocalAccessibilityOptions.current.reduceMotion
    val color = LocalAccentColors.current.accent
    val amount = remember { Animatable(0f) }
    LaunchedEffect(active, visible, still) {
        amount.snapTo(if (active && visible) 0.16f else 0f)
        if (active && visible) {
            delay(600)
            if (still) amount.snapTo(0f) else amount.animateTo(0f, Motion.tween(Motion.EMPHASIZED))
        }
    }
    return drawWithContent {
        drawContent()
        if (amount.value > 0f) drawRoundRect(color.copy(alpha = amount.value))
    }
}
