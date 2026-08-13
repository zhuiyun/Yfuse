package com.yfuse.core.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far a pressed surface leans towards the finger, in degrees. */
private const val TILT_DEGREES = 7f

/**
 * Pushed well past Compose's default 8, which is close enough to the surface that a 7°
 * lean on a poster-sized tile reads as a smear rather than a lean.
 */
private const val TILT_CAMERA_DISTANCE = 20f

/** Keyboard, D-pad and mouse focus lifts without competing with the pressed state. */
private const val FOCUS_SCALE = 1.02f

private const val HOVER_RING_ALPHA = 0.56f

/**
 * 44pt — the smallest thing a finger can be asked to hit, and the number Apple has not
 * moved off since the first iPhone.
 */
val MinTouchTarget: Dp = 48.dp

/**
 * Grows the node to at least [minSize] on both axes without changing what is drawn.
 *
 * The content is measured normally and placed centred; only the slot around it grows. Chain
 * it directly after [pressable] — the click handler measures whatever is to its right, so
 * anything between the two would be left out of the target:
 *
 * ```
 * Modifier.pressable(onClick = ::clear).touchTarget().clip(CircleShape).size(13.dp)
 * ```
 *
 * This does take real layout space, which is the honest cost of an adequate target: a 13dp
 * glyph that answers to a 44dp region has to own that region, or it steals taps from its
 * neighbours instead.
 */
fun Modifier.touchTarget(minSize: Dp = MinTouchTarget): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val floor = minSize.roundToPx()
        val width = maxOf(placeable.width, floor)
        val height = maxOf(placeable.height, floor)
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }

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
 * The two halves of the press are not the same movement and no longer share a spec. Going
 * down is a short ease: the finger is already there, and anything slower feels like lag.
 * Coming back up is a spring with a little overshoot — that is the half that reads as a
 * physical release, and being a spring it also keeps its velocity when interrupted, so a
 * double tap answers twice instead of restarting a fixed 140ms ramp from wherever it got to.
 *
 * @param tilt leans the surface towards the touch point as well as scaling it. Reserved
 *   for artwork — a poster is a thing you can almost pick up, and the parallax says so.
 *   On small controls the rotation is illegible and only costs a layer.
 * @param haptic played on click. Null for ordinary navigation, where the thing that
 *   happens next is its own feedback; set it where the tap changes state in place and
 *   the screen alone might not make that obvious.
 * @param role what an assistive service should call this. Defaults to [Role.Button];
 *   pass [Role.Tab], [Role.Checkbox] or [Role.RadioButton] where that is what it is, and
 *   null only for a surface that is genuinely not a control.
 * @param onLongClick when set, the whole gesture goes through `combinedClickable`.
 * @param focusShape shape of the keyboard/D-pad focus ring. The default fits ordinary
 *   controls; artwork should pass the same shape it clips to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    tilt: Boolean = false,
    haptic: HapticSignal? = null,
    role: Role? = Role.Button,
    focusShape: Shape = AppShapes.control,
    /** Announced by the accessibility service in place of "activate", when it has a better verb. */
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Announced by the accessibility service for the long press, when it has its own name. */
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val haptics = LocalHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val down = pressed && enabled
    val highlighted = enabled && (focused || hovered)
    val targetScale =
        pressScaleTarget(
            reduceMotion = reduceMotion,
            pressed = down,
            highlighted = highlighted,
            pressedScale = pressedScale,
        )
    val scaleSpec: AnimationSpec<Float> =
        when {
            reduceMotion -> snap()
            down -> Motion.pressSpec(pressed = true, reduceMotion = false)
            else -> Motion.settle()
        }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = scaleSpec,
        label = "pressScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = focusRingTargetAlpha(enabled, focused, hovered),
        animationSpec = if (reduceMotion) snap() else tween(Motion.QUICK, easing = Motion.Curve),
        label = "focusRing",
    )
    val focusColor = LocalAccentColors.current.accent

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
        targetValue = if (down && tilting) 1f else 0f,
        animationSpec = Motion.pressSpec(pressed = down, reduceMotion = reduceMotion),
        label = "pressTilt",
    )

    val onClickWithHaptic: () -> Unit = {
        haptic?.let(haptics::play)
        onClick()
    }
    val onLongClickWithHaptic: (() -> Unit)? =
        onLongClick?.let { action ->
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
        }.then(
            if (ringAlpha > 0f) {
                Modifier.border(
                    width = 2.dp,
                    color = focusColor.copy(alpha = focusColor.alpha * ringAlpha),
                    shape = focusShape,
                )
            } else {
                Modifier
            },
        ).let { modifier ->
            if (onLongClickWithHaptic != null) {
                modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = onClickLabel,
                    role = role,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClickWithHaptic,
                    onClick = onClickWithHaptic,
                )
            } else {
                modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = onClickLabel,
                    role = role,
                    onClick = onClickWithHaptic,
                )
            }
        }
}

internal fun pressScaleTarget(
    reduceMotion: Boolean,
    pressed: Boolean,
    highlighted: Boolean,
    pressedScale: Float,
): Float =
    when {
        // A snapped scale is still motion. Reduce Motion keeps the persistent focus ring but
        // removes every geometric response, including press and hover.
        reduceMotion -> 1f
        pressed -> pressedScale
        highlighted -> FOCUS_SCALE
        else -> 1f
    }

internal fun focusRingTargetAlpha(
    enabled: Boolean,
    focused: Boolean,
    hovered: Boolean,
): Float =
    when {
        focused && enabled -> 1f
        hovered && enabled -> HOVER_RING_ALPHA
        else -> 0f
    }

/**
 * `animateItem`, silenced under 减弱动态效果.
 *
 * List reordering was the last family of animation in the app the setting did not reach:
 * six call sites across 媒体库网格, 搜索, 日历 and 一起看聊天 called `animateItem()` directly,
 * and its default springs run whatever the user has asked for. Passing null for all three
 * specs is how the platform spells "no animation" here.
 */
@Composable
fun LazyItemScope.motionAwareItem(): Modifier =
    if (LocalAccessibilityOptions.current.reduceMotion) {
        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    } else {
        Modifier.animateItem()
    }

/** [motionAwareItem], for grids. */
@Composable
fun LazyGridItemScope.motionAwareItem(): Modifier =
    if (LocalAccessibilityOptions.current.reduceMotion) {
        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    } else {
        Modifier.animateItem()
    }
