package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal enum class ContentPhase { Loading, Content, Empty, Error }

internal fun contentPhase(
    loading: Boolean,
    hasContent: Boolean,
    error: Boolean,
): ContentPhase =
    when {
        hasContent -> ContentPhase.Content
        loading -> ContentPhase.Loading
        error -> ContentPhase.Error
        else -> ContentPhase.Empty
    }

/** A phase handoff keeps one content tree and fixed host bounds; refreshes retain visible data. */
@Composable
internal fun Modifier.contentHandoff(phase: Any): Modifier {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val committed = remember { arrayOf<Any>(phase) }
    val progress = remember(phase, moving) { Animatable(if (moving && committed[0] != phase) 0f else 1f) }
    SideEffect { committed[0] = phase }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(Motion.STATE_HANDOFF, easing = Motion.Curve))
        }
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = 6.dp.toPx() * (1f - progress.value)
    }
}
