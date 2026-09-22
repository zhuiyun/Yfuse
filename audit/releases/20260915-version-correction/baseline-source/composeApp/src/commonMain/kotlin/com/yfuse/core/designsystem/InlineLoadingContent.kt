package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Reserve the larger icon slot; optional trailing text changes width with the handoff. */
@Composable
internal fun InlineLoadingContent(
    loading: Boolean,
    slotSize: Dp,
    color: Color,
    orbSize: Dp = slotSize,
    content: @Composable () -> Unit,
) {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            (
                fadeIn(if (moving) tween(Motion.QUICK, easing = Motion.Curve) else snap()) togetherWith
                    fadeOut(if (moving) tween(Motion.QUICK, easing = Motion.Curve) else snap())
            ).using(
                SizeTransform(clip = false) { _, _ ->
                    if (moving) tween(Motion.QUICK, easing = Motion.Curve) else snap()
                },
            )
        },
        contentAlignment = Alignment.Center,
        label = "inline-loading",
    ) { busy ->
        Box(Modifier.defaultMinSize(minWidth = slotSize, minHeight = slotSize), contentAlignment = Alignment.Center) {
            if (busy) {
                OrbProgress(size = orbSize, color = color)
            } else {
                content()
            }
        }
    }
}
