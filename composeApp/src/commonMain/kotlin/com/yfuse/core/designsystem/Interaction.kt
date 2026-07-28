package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Press feedback for liquid-glass controls.
 *
 * Material's ripple is all but invisible on translucent glass, so pressed state is
 * carried by a short scale-down instead — the same affordance the prototype uses.
 *
 * Chain it **before** the surface modifiers (`cssShadow` / `glass` / `background`):
 * everything to its right is drawn inside the scaled layer, everything to its left
 * is not.
 *
 * ```
 * Modifier.weight(1f).height(46.dp).pressable(onClick = ::play).glass(...)
 * ```
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 140, easing = Motion.Curve),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
