package com.yfuse.core.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How far a pressed surface leans towards the finger, in degrees. */
private const val TILT_DEGREES = 7f

/**
 * Pushed well past Compose's default 8, which is close enough to the surface that a 7°
 * lean on a poster-sized tile reads as a smear rather than a lean.
 */
private const val TILT_CAMERA_DISTANCE = 20f

/** Keyboard and D-pad focus lifts without competing with the pressed state. */
private const val FOCUS_SCALE = 1.02f

/**
 * The pressed state layer: the surface's own ink laid over itself.
 *
 * Under 减弱动态效果 this is the whole of the press. The scale is motion and goes; the answer
 * to "did that register" must not — with ripples off and the scale pinned at 1, a pressed
 * control used to show nothing at all until whatever it did happened.
 */
internal const val PRESSED_LAYER_ALPHA = 0.10f

/**
 * A pointer resting on a control: a quieter wash of the same ink. Hover used to borrow the
 * focus language — a 1.02 lift and an accent ring — so a mouse crossing a page lit up every
 * card it passed and a wide hero grew under the cursor.
 */
internal const val HOVER_LAYER_ALPHA = 0.05f

/** The accent ring's dark underlay, so the ring holds its edge over bright artwork. */
private const val RING_HALO_ALPHA = 0.45f

/**
 * A tap inside a scrolling list is reported late and all at once — press and release in the
 * same frame — so feedback that follows the pressed state never drew. Hold it this long.
 */
internal const val MIN_PRESS_VISIBLE_MS = Motion.PRESS_IN

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
 * The pressed state as it should be drawn.
 *
 * Follows the press like `collectIsPressedAsState`, except that a release holds the state for
 * at least [MIN_PRESS_VISIBLE_MS] after the press began. Inside a scrolling parent a quick tap
 * arrives as press and release together, and without the hold neither the scale nor the state
 * layer ever reached the screen. A cancel — the finger turned into a scroll — clears at once.
 */
@Composable
internal fun InteractionSource.collectVisiblePressAsState(): State<Boolean> {
    val visible = remember(this) { mutableStateOf(false) }
    LaunchedEffect(this) {
        val presses = mutableListOf<PressInteraction.Press>()
        var shownAt: TimeMark? = null
        var clearing: Job? = null
        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    clearing?.cancel()
                    presses += interaction
                    shownAt = TimeSource.Monotonic.markNow()
                    visible.value = true
                }
                is PressInteraction.Release -> {
                    presses -= interaction.press
                    if (presses.isEmpty()) {
                        val shown = shownAt?.elapsedNow()?.inWholeMilliseconds ?: MIN_PRESS_VISIBLE_MS.toLong()
                        val remaining = MIN_PRESS_VISIBLE_MS - shown
                        if (remaining <= 0L) {
                            visible.value = false
                        } else {
                            clearing =
                                launch {
                                    delay(remaining)
                                    visible.value = false
                                }
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    presses -= interaction.press
                    if (presses.isEmpty()) {
                        clearing?.cancel()
                        visible.value = false
                    }
                }
            }
        }
    }
    return visible
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
 * Under 减弱动态效果 the scale goes and a state layer — the surface's own ink at
 * [PRESSED_LAYER_ALPHA] — takes its place, so a press is still answered without motion.
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
 * @param focusShape shape of the keyboard/D-pad focus ring and of the state layer. The
 *   default fits ordinary controls; artwork should pass the same shape it clips to.
 * @param label what the control *is*, for a screen reader, when nothing inside it says so —
 *   an icon-only button. Written as the node's `contentDescription`; leave it null where a
 *   visible text child already names the control, or the name would be read twice.
 * @param tintOnPress lays the state layer on every press, not only under 减弱动态效果. For
 *   wide rows, where a 0.97 scale reads as text sliding towards the middle rather than a press.
 * @param stateLayer false for a surface that already paints its own pressed colour
 *   ([softSelectionSurface]); it would otherwise be washed twice.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    tilt: Boolean = false,
    lightFeedback: Boolean = true,
    /** What the press draws; null keeps the default 节点 / 边缘 pairing. */
    lightEffect: LightEffect? = null,
    haptic: HapticSignal? = null,
    role: Role? = Role.Button,
    focusShape: Shape = AppShapes.control,
    /** Announced by the accessibility service in place of "activate", when it has a better verb. */
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Announced by the accessibility service for the long press, when it has its own name. */
    onLongClickLabel: String? = null,
    label: String? = null,
    interactionSource: MutableInteractionSource? = null,
    tintOnPress: Boolean = false,
    stateLayer: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val haptics = LocalHaptics.current
    val light = rememberLightFeedback(enabled && lightFeedback && role == Role.Button)
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectVisiblePressAsState()
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val down = pressed && enabled
    val targetScale =
        pressScaleTarget(
            reduceMotion = reduceMotion,
            pressed = down,
            highlighted = enabled && focused,
            pressedScale = pressedScale,
        )
    val scaleSpec: AnimationSpec<Float> =
        when {
            reduceMotion -> snap()
            down -> Motion.pressSpec(pressed = true, reduceMotion = false)
            else -> Motion.pressSpec(pressed = false, reduceMotion = false)
        }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = scaleSpec,
        label = "pressScale",
    )
    val ringAlpha =
        animateFloatAsState(
            targetValue = focusRingTargetAlpha(enabled, focused),
            animationSpec = if (reduceMotion) snap() else Motion.tween(Motion.QUICK),
            label = "focusRing",
        )
    val layerAlpha =
        animateFloatAsState(
            targetValue =
                pressLayerTargetAlpha(
                    enabled = enabled && stateLayer,
                    pressed = down,
                    hovered = hovered,
                    reduceMotion = reduceMotion,
                    tintOnPress = tintOnPress,
                ),
            animationSpec = if (reduceMotion) snap() else Motion.tween(if (down) Motion.PRESS_IN else Motion.QUICK),
            label = "pressLayer",
        )
    // Only the visibility boundary changes the modifier chain; fractional alpha stays in drawing.
    val decorated by remember(ringAlpha, layerAlpha) {
        derivedStateOf { ringAlpha.value > 0f || layerAlpha.value > 0f }
    }
    val focusColor = LocalAccentColors.current.accent
    val ink = LocalPalette.current.text

    // 减弱动态效果 turns the lean off rather than shortening it: a rotation that snaps to
    // its end state and back is exactly the kind of movement the setting exists to remove.
    val tilting = tilt && !reduceMotion
    var pressPoint by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(source, tilting, light) {
        if (!tilting && !light.enabled) return@LaunchedEffect
        source.interactions.collect { interaction ->
            // Only the press carries a position; the release and the cancel are the same
            // event as far as the lean is concerned, and [pressed] already covers them.
            when (interaction) {
                is PressInteraction.Press -> pressPoint = interaction.pressPosition
                // Focus moving across a page is navigation, not an event worth a burst of light.
                is FocusInteraction.Focus -> pressPoint = Offset.Unspecified
                is FocusInteraction.Unfocus, is PressInteraction.Cancel -> light.clear()
            }
        }
    }
    val lean by animateFloatAsState(
        targetValue = if (down && tilting) 1f else 0f,
        animationSpec = Motion.pressSpec(pressed = down, reduceMotion = reduceMotion),
        label = "pressTilt",
    )

    val onClickWithHaptic: () -> Unit = {
        haptic?.let(haptics::play)
        light.emit(lightEffect ?: if (tilt) LightEffect.Edge else LightEffect.Node, at = pressPoint)
        onClick()
    }
    val onLongClickWithHaptic: (() -> Unit)? =
        onLongClick?.let { action ->
            {
                // The one long-press tick: `combinedClickable`'s own is switched off below, or a
                // long press on a poster buzzed twice.
                haptics.play(HapticSignal.LongPress)
                light.emit(LightEffect.Edge, at = pressPoint)
                action()
            }
        }

    return this
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .lightFeedback(light)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            rotationX = 0f
            rotationY = 0f
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
            if (decorated) {
                Modifier.drawWithCache {
                    var cachedOutline: Outline? = null
                    var cachedClip: Path? = null
                    // Clip the outer half of each stroke, so the complete ring stays inside its hit
                    // target: a 2dp accent edge with a 1dp dark underlay that keeps it legible over
                    // bright artwork.
                    val ringStroke = Stroke(4.dp.toPx())
                    val haloStroke = Stroke(6.dp.toPx())
                    onDrawWithContent {
                        drawContent()
                        val ring = ringAlpha.value
                        val layer = layerAlpha.value
                        if (ring <= 0f && layer <= 0f) return@onDrawWithContent
                        // Most touch-only controls never show a ring; build their geometry only on demand.
                        val outline =
                            cachedOutline ?: focusShape.createOutline(size, layoutDirection, this).also {
                                cachedOutline = it
                            }
                        if (layer > 0f) drawOutline(outline, ink.copy(alpha = ink.alpha * layer))
                        if (ring > 0f) {
                            val ringClip =
                                cachedClip ?: when (outline) {
                                    is Outline.Generic -> outline.path
                                    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                                    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                                }.also { cachedClip = it }
                            clipPath(ringClip) {
                                drawOutline(
                                    outline,
                                    Color.Black.copy(alpha = RING_HALO_ALPHA * ring),
                                    style = haloStroke,
                                )
                                drawOutline(
                                    outline,
                                    focusColor.copy(alpha = focusColor.alpha * ring),
                                    style = ringStroke,
                                )
                            }
                        }
                    }
                }
            } else {
                Modifier
            },
        ).let { modifier ->
            if (onLongClickWithHaptic != null) {
                modifier.combinedClickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = onClickLabel,
                    role = role,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClickWithHaptic,
                    hapticFeedbackEnabled = false,
                    onClick = onClickWithHaptic,
                )
            } else {
                modifier.clickable(
                    interactionSource = source,
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
        // A snapped scale is still motion. Reduce Motion keeps the persistent focus ring and the
        // state layer but removes every geometric response, including press and focus.
        reduceMotion -> 1f
        pressed -> pressedScale
        highlighted -> FOCUS_SCALE
        else -> 1f
    }

internal fun focusRingTargetAlpha(
    enabled: Boolean,
    focused: Boolean,
): Float = if (focused && enabled) 1f else 0f

/**
 * How strongly the state layer washes a control: the full [PRESSED_LAYER_ALPHA] while pressed
 * — always under 减弱动态效果, where it replaces the scale, otherwise only for [tintOnPress]
 * rows — and [HOVER_LAYER_ALPHA] under a resting pointer.
 */
internal fun pressLayerTargetAlpha(
    enabled: Boolean,
    pressed: Boolean,
    hovered: Boolean,
    reduceMotion: Boolean,
    tintOnPress: Boolean,
): Float =
    when {
        !enabled -> 0f
        pressed && (reduceMotion || tintOnPress) -> PRESSED_LAYER_ALPHA
        pressed -> 0f
        hovered -> HOVER_LAYER_ALPHA
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
        Modifier.animateItem(
            fadeInSpec = Motion.tween(Motion.QUICK),
            placementSpec = Motion.settle(),
            fadeOutSpec = Motion.tween(Motion.STANDARD),
        )
    }

/** [motionAwareItem], for grids. */
@Composable
fun LazyGridItemScope.motionAwareItem(): Modifier =
    if (LocalAccessibilityOptions.current.reduceMotion) {
        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    } else {
        Modifier.animateItem(
            fadeInSpec = Motion.tween(Motion.QUICK),
            placementSpec = Motion.settle(),
            fadeOutSpec = Motion.tween(Motion.STANDARD),
        )
    }
