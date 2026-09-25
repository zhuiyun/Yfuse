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

/**
 * Reserve the larger icon slot; optional trailing text changes width with the handoff.
 *
 * The orb waits [Motion.BUSY_SHOW_AFTER] before replacing the icon and then stays at least
 * [Motion.BUSY_MIN_VISIBLE] — the same clock as [waitingPulse] on the control around it, which
 * used to hold back 180ms while the orb in the same button swapped in on frame 0.
 */
@Composable
internal fun InlineLoadingContent(
    loading: Boolean,
    slotSize: Dp,
    color: Color,
    orbSize: Dp = slotSize,
    content: @Composable () -> Unit,
) {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val busy = rememberDelayedBusy(loading)
    AnimatedContent(
        targetState = busy,
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
