package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer

/** How far a pressed surface leans towards the finger, in degrees. */
private const val TILT_DEGREES = 7f

/**
 * Pushed well past Compose's default 8, which is close enough to the surface that a 7°
 * lean on a poster-sized tile reads as a smear rather than a lean.
 */
private const val TILT_CAMERA_DISTANCE = 20f

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
 *
 * @param tilt leans the surface towards the touch point as well as scaling it. Reserved
 *   for artwork — a poster is a thing you can almost pick up, and the parallax says so.
 *   On small controls the rotation is illegible and only costs a layer.
 * @param haptic played on click. Null for ordinary navigation, where the thing that
 *   happens next is its own feedback; set it where the tap changes state in place and
 *   the screen alone might not make that obvious.
 * @param onLongClick when set, the whole gesture goes through `combinedClickable`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    tilt: Boolean = false,
    haptic: HapticSignal? = null,
    onLongClick: (() -> Unit)? = null,
    /** Announced by the accessibility service for the long press, when it has its own name. */
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val haptics = LocalHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val duration = if (reduceMotion) 0 else 140
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = tween(durationMillis = duration, easing = Motion.Curve),
        label = "pressScale",
    )

    // 减弱动态效果 turns the lean off rather than shortening it: a rotation that snaps to
    // its end state and back is exactly the kind of movement the setting exists to remove.
    val tilting = tilt && !reduceMotion
    var pressPoint by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(interactionSource, tilting) {
        if (!tilting) return@LaunchedEffect
        interactionSource.interactions.collect { interaction ->
            // Only the press carries a position; the release and the cancel are the same
            // event as far as the lean is concerned, and [pressed] already covers them.
            if (interaction is PressInteraction.Press) pressPoint = interaction.pressPosition
        }
    }
    val lean by animateFloatAsState(
        targetValue = if (pressed && enabled && tilting) 1f else 0f,
        animationSpec = tween(durationMillis = duration, easing = Motion.Curve),
        label = "pressTilt",
    )

    val onClickWithHaptic: () -> Unit = {
        haptic?.let(haptics::play)
        onClick()
    }
    val onLongClickWithHaptic: (() -> Unit)? = onLongClick?.let { action ->
        {
            haptics.play(HapticSignal.Confirm)
            action()
        }
    }

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            if (lean > 0f && pressPoint.isSpecified && size.minDimension > 0f) {
                // -1..1 across the surface, so the corner nearest the finger drops away
                // and the opposite one lifts.
                val horizontal = ((pressPoint.x / size.width) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                val vertical = ((pressPoint.y / size.height) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                cameraDistance = TILT_CAMERA_DISTANCE * density
                rotationY = horizontal * TILT_DEGREES * lean
                rotationX = -vertical * TILT_DEGREES * lean
            }
        }
        .let { modifier ->
            if (onLongClickWithHaptic != null) {
                modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClickWithHaptic,
                    onClick = onClickWithHaptic,
                )
            } else {
                modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClickWithHaptic,
                )
            }
        }
}
