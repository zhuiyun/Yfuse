package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

// ------------------------------------------------------------------ the numbers (5.4)
//
// Plain arithmetic below, so how a row answers a finger can be tested without one.

/** A sideways drag has to travel this far before it is judged a swipe or a scroll. */
internal val SwipeRowSlop = 8.dp

/** No swipe starts this close to either side of the window: that strip is the system's back gesture. */
internal val SwipeRowSystemEdge = 24.dp

/** Let go past this and the row stays open on its button, to be tapped. */
internal val SwipeRowOpenThreshold = 64.dp

/** Where an open row rests: the width of the button it uncovers. */
internal val SwipeRowActionWidth = 88.dp

/** Let go past this share of the row and the action runs without waiting for its button. */
internal const val SWIPE_ROW_RUN_FRACTION = 0.45f

/** A flick runs the action from this share of the row, when it is faster than [SWIPE_ROW_RUN_VELOCITY]. */
internal const val SWIPE_ROW_FLICK_FRACTION = 0.30f

/** Pixels per millisecond, outward. */
internal const val SWIPE_ROW_RUN_VELOCITY = 0.9f

/** Past this share of the row the drag is damped: the action has long been decided. */
internal const val SWIPE_ROW_DAMPING_FROM = 0.8f

/** How far the row moves per pixel of finger past [SWIPE_ROW_DAMPING_FROM]. */
internal const val SWIPE_ROW_DAMPING = 0.25f

/**
 * A row that ran a removing action slid out of sight; if it is still composed after the list has
 * had time to take it away, the change kept it — 删除下载 on an episode that stays listed — and it
 * comes back into place.
 */
private const val SWIPE_ROW_RETURN_DELAY_MS = 320L

/** How a swiped row settles once the finger lets go. */
enum class SwipeRowSettle {
    /** Back where it was: not far enough, or flicked back. */
    Closed,

    /** Parked on its button, which waits to be tapped. */
    Open,

    /** Far enough, or flung hard enough: the action runs now. */
    Run,
}

/**
 * The row's offset for a finger that has travelled [travel] pixels from where the row rests
 * closed: one to one up to [SWIPE_ROW_DAMPING_FROM] of [width], a quarter of that past it.
 */
internal fun swipeRowOffset(
    travel: Float,
    width: Float,
): Float {
    if (width <= 0f || !travel.isFinite()) return 0f
    val limit = width * SWIPE_ROW_DAMPING_FROM
    val distance = abs(travel)
    val shown = if (distance <= limit) distance else limit + (distance - limit) * SWIPE_ROW_DAMPING
    return if (travel < 0f) -shown else shown
}

/** The finger travel that [swipeRowOffset] shows as [offset]: where a drag of a moving or open row picks up. */
internal fun swipeRowTravel(
    offset: Float,
    width: Float,
): Float {
    if (width <= 0f || !offset.isFinite()) return 0f
    val limit = width * SWIPE_ROW_DAMPING_FROM
    val distance = abs(offset)
    val travel = if (distance <= limit) distance else limit + (distance - limit) / SWIPE_ROW_DAMPING
    return if (offset < 0f) -travel else travel
}

/** [travel], held at rest on a side that has no action to uncover. */
internal fun swipeRowAllowedTravel(
    travel: Float,
    towardsRight: Boolean,
    towardsLeft: Boolean,
): Float =
    when {
        travel > 0f && !towardsRight -> 0f
        travel < 0f && !towardsLeft -> 0f
        else -> travel
    }

/** Whether a row at [offset] is past the line where letting go runs its action. */
internal fun swipeRowPastRunLine(
    offset: Float,
    width: Float,
): Boolean = width > 0f && offset.isFinite() && abs(offset) >= width * SWIPE_ROW_RUN_FRACTION

/**
 * How a row let go at [offset] with [velocity] settles. Both are pixels (per second for the
 * velocity), positive to the right; [width] is the row's and [openThreshold] is
 * [SwipeRowOpenThreshold] in pixels.
 *
 * Past [SWIPE_ROW_RUN_FRACTION] of the row, or a flick faster than [SWIPE_ROW_RUN_VELOCITY]
 * from [SWIPE_ROW_FLICK_FRACTION], runs. Otherwise the row opens when its release, projected as
 * every drag here is ([DragProgress]), carries it past [openThreshold]: a quick flick from a
 * little way in opens, and a row flicked back towards rest closes even from past the line.
 */
internal fun swipeRowSettle(
    offset: Float,
    velocity: Float,
    width: Float,
    openThreshold: Float,
): SwipeRowSettle {
    if (width <= 0f || openThreshold <= 0f || offset == 0f || !offset.isFinite()) return SwipeRowSettle.Closed
    val distance = abs(offset)
    val outward = if (velocity.isFinite()) (if (offset < 0f) -velocity else velocity) else 0f
    if (distance >= width * SWIPE_ROW_RUN_FRACTION) return SwipeRowSettle.Run
    if (outward > SWIPE_ROW_RUN_VELOCITY * 1_000f && distance > width * SWIPE_ROW_FLICK_FRACTION) {
        return SwipeRowSettle.Run
    }
    val release = DragProgress(offset = distance, velocity = outward, extent = openThreshold)
    return if (release.projectedFraction(DRAG_PROJECTION_MS) >= 1f) SwipeRowSettle.Open else SwipeRowSettle.Closed
}

// ------------------------------------------------------------------ the row

/** Where one row is; plain fields, read by its gesture and written by its own animations. */
@Stable
private class SwipeRowState {
    /** Pixels the row has moved, positive to the right. Read while laying out and drawing. */
    var offset by mutableFloatStateOf(0f)

    /** The side parked open: 1 when moved right, -1 when moved left, 0 when closed. */
    var open by mutableIntStateOf(0)

    /** The row's own opacity: 减弱动态效果 brings a row home with a fade, not a slide. */
    val fade = Animatable(1f)

    var width = 0f
    var coordinates: LayoutCoordinates? = null
    var motion: Job? = null
}

/**
 * A list row that swipes sideways for its actions (5.4): right for [leading], left for
 * [trailing] — in a right-to-left layout the other way round, so leading stays the start side.
 *
 * - A drag becomes a swipe once it has moved [SwipeRowSlop] and is clearly sideways
 *   ([resolveDragAxis]); anything else is left to the list's scroll, and a gesture that starts in
 *   the system's back strip ([inSystemBackEdge]) is never a swipe. A swipe towards a side with
 *   nothing on it is not claimed at all, so a pager around the list still gets it.
 * - Let go past [SwipeRowOpenThreshold] and the row parks on its button; past
 *   [SWIPE_ROW_RUN_FRACTION] of the row, or flicked, the action runs at once. Crossing that line
 *   plays [HapticSignal.Threshold], coming back over it [HapticSignal.ThresholdRelease].
 * - An open row's own tap closes it rather than opening it; so does scrolling the list.
 * - A [ItemAction.destructive] action slides the row away before it runs, since what it does is
 *   usually take the row away; any other runs, and the row springs home.
 * - 减弱动态效果 and 静息 keep the row under the finger, and bring it home with a short fade.
 *
 * The actions also reach a screen reader, as custom actions: [content] is handed a modifier
 * carrying them, to go on the row's clickable surface next to its `pressable`. A screen reader
 * lands on that node, never on this wrapper, so actions put here would never be read out.
 */
@Composable
fun SwipeActionsRow(
    modifier: Modifier = Modifier,
    leading: ItemAction? = null,
    trailing: ItemAction? = null,
    enabled: Boolean = true,
    /** The row's own outline, which the uncovered strip follows at its corners. */
    shape: Shape = AppShapes.card,
    content: @Composable (actions: Modifier) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val haptics = LocalHaptics.current
    val tips = LocalTips.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The gesture below works in screen directions; which action lives on which side does not.
    val right = if (rtl) trailing else leading
    val left = if (rtl) leading else trailing
    val latestRight by rememberUpdatedState(right)
    val latestLeft by rememberUpdatedState(left)
    val latestStill by rememberUpdatedState(still)
    val latestHaptics by rememberUpdatedState(haptics)
    val latestTips by rememberUpdatedState(tips)
    val openWidth = with(LocalDensity.current) { SwipeRowActionWidth.toPx() }
    val scope = rememberCoroutineScope()
    val state = remember { SwipeRowState() }

    fun actionOn(side: Int): ItemAction? =
        when {
            side > 0 -> latestRight
            side < 0 -> latestLeft
            else -> null
        }

    /** Moves the row to [target] — a spring that carries the release, or a fade in place. */
    fun settle(
        target: Float,
        velocity: Float,
    ) {
        state.motion?.cancel()
        state.motion =
            scope.launch {
                if (latestStill) {
                    state.offset = target
                    state.fade.snapTo(0f)
                    state.fade.animateTo(1f, Motion.tween(Motion.REDUCED_FADE, easing = LinearEasing))
                } else {
                    Animatable(state.offset).animateTo(
                        targetValue = target,
                        animationSpec = Motion.settle(),
                        initialVelocity = velocity,
                    ) {
                        state.offset = value
                    }
                }
            }
    }

    fun close() {
        state.open = 0
        settle(0f, 0f)
    }

    fun run(
        side: Int,
        velocity: Float,
    ) {
        val action = actionOn(side) ?: return close()
        state.open = 0
        if (!action.destructive || latestStill) {
            action.onSelect()
            settle(0f, velocity)
            return
        }
        state.motion?.cancel()
        state.motion =
            scope.launch {
                Animatable(state.offset).animateTo(
                    targetValue = side * state.width,
                    animationSpec = Motion.tween(Motion.QUICK, easing = Motion.Curve),
                ) {
                    state.offset = value
                }
                action.onSelect()
                delay(SWIPE_ROW_RETURN_DELAY_MS)
                state.offset = 0f
                state.fade.snapTo(0f)
                state.fade.animateTo(1f, Motion.tween(Motion.STANDARD, easing = LinearEasing))
            }
    }

    fun release(
        velocity: Float,
        width: Float,
        openThreshold: Float,
    ) {
        val side =
            when {
                state.offset > 0f -> 1
                state.offset < 0f -> -1
                else -> 0
            }
        val settled =
            if (actionOn(side) == null) {
                SwipeRowSettle.Closed
            } else {
                swipeRowSettle(state.offset, velocity, width, openThreshold)
            }
        when (settled) {
            SwipeRowSettle.Closed -> {
                state.open = 0
                settle(0f, velocity)
            }
            SwipeRowSettle.Open -> {
                state.open = side
                settle(side * openWidth, velocity)
            }
            SwipeRowSettle.Run -> run(side, velocity)
        }
    }

    // A row that can no longer be swiped does not stay open.
    LaunchedEffect(enabled, right == null, left == null) {
        val side = state.open
        if (side != 0 && (!enabled || actionOn(side) == null)) close()
    }

    val gesture =
        Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val slop = SwipeRowSlop.toPx()
            val edge = SwipeRowSystemEdge.toPx()
            val openThreshold = SwipeRowOpenThreshold.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val placed = state.coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val window = placed.findRootCoordinates()
                val startX = placed.localToRoot(down.position).x
                if (inSystemBackEdge(startX, window.size.width.toFloat(), edge)) return@awaitEachGesture
                val width = size.width.toFloat()
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)
                var moved = Offset.Zero
                var axis = DragAxis.Undecided
                while (axis == DragAxis.Undecided) {
                    val change =
                        awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    // Up inside the slop is a tap, left to the row; a move someone else has taken
                    // — the list's scroll, a drag-select sweep — is theirs.
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    tracker.addPointerInputChange(change)
                    moved += change.positionChange()
                    axis = resolveDragAxis(moved.x, moved.y, slop)
                }
                if (axis == DragAxis.Vertical) {
                    // Scrolling the list closes an open row, as it does in 邮件.
                    if (state.open != 0) close()
                    return@awaitEachGesture
                }
                val towardsRight = latestRight != null
                val towardsLeft = latestLeft != null
                if (state.offset == 0f && swipeRowAllowedTravel(moved.x, towardsRight, towardsLeft) == 0f) {
                    return@awaitEachGesture
                }
                state.motion?.cancel()
                state.open = 0
                latestTips?.markUsed(Tips.SWIPE_ROW)
                var travel = swipeRowTravel(state.offset, width)
                var past = swipeRowPastRunLine(state.offset, width)
                var released = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPointerInputChange(change)
                        // Read before consuming: a consumed change reports no movement at all.
                        val delta = change.positionChange().x
                        change.consume()
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        travel = swipeRowAllowedTravel(travel + delta, towardsRight, towardsLeft)
                        state.offset = swipeRowOffset(travel, width)
                        val nowPast = swipeRowPastRunLine(state.offset, width)
                        if (nowPast != past) {
                            past = nowPast
                            latestHaptics.play(if (nowPast) HapticSignal.Threshold else HapticSignal.ThresholdRelease)
                        }
                    }
                } finally {
                    // The stream was taken away — the row left, the window lost focus: nothing runs.
                    if (!released) close()
                }
                if (released) release(tracker.calculateVelocity().x, width, openThreshold)
            }
        }

    val rightColors = right?.let { swipeActionColors(it, palette, accent) }
    val leftColors = left?.let { swipeActionColors(it, palette, accent) }
    val actions = listOfNotNull(leading, trailing)
    val semantics =
        if (actions.isEmpty()) Modifier else Modifier.semantics { customActions = actions.accessibilityActions() }
    // A resting row composes no buttons; this flips only when a row leaves rest or returns to it.
    val uncovered by remember { derivedStateOf { state.offset != 0f } }
    Box(
        modifier
            .onPlaced {
                state.coordinates = it
                state.width = it.size.width.toFloat()
            }.then(gesture),
    ) {
        if (uncovered && (right != null || left != null)) {
            // What the swipe uncovers, drawn only in the strip the row has moved off: rows are
            // glass, and anything under the row itself would show through it.
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .drawWithContent {
                        val x = state.offset
                        val fill = (if (x > 0f) rightColors else leftColors)?.fill
                        if (x == 0f || fill == null) return@drawWithContent
                        clipRect(
                            left = if (x > 0f) 0f else size.width + x,
                            right = if (x > 0f) x else size.width,
                        ) {
                            drawRect(fill)
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                if (right != null && rightColors != null) {
                    SwipeActionButton(
                        action = right,
                        onLeft = true,
                        ink = rightColors.ink,
                        open = state.open > 0,
                        offset = { state.offset },
                        openWidth = openWidth,
                        onClick = { run(1, 0f) },
                    )
                }
                if (left != null && leftColors != null) {
                    SwipeActionButton(
                        action = left,
                        onLeft = false,
                        ink = leftColors.ink,
                        open = state.open < 0,
                        offset = { state.offset },
                        openWidth = openWidth,
                        onClick = { run(-1, 0f) },
                    )
                }
            }
        }
        Box(
            Modifier
                .absoluteOffset { IntOffset(state.offset.roundToInt(), 0) }
                .graphicsLayer { alpha = state.fade.value },
        ) {
            content(semantics)
            if (state.open != 0) {
                // An open row's tap closes it; opening what it shows would surprise.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { close() } },
                )
            }
        }
    }
}

private class SwipeActionColors(
    val fill: Color,
    val ink: Color,
)

private fun swipeActionColors(
    action: ItemAction,
    palette: Palette,
    accent: AccentColors,
): SwipeActionColors =
    if (action.destructive) {
        SwipeActionColors(palette.error, palette.onError)
    } else {
        SwipeActionColors(accent.accent, accent.onAccent)
    }

/**
 * The button a swipe uncovers: pinned to its edge while the row is within [openWidth], then
 * travelling with the row's edge, so the icon never floats away from what it acts on. Only a
 * parked row's button can be pressed or found by a screen reader; the row's custom actions
 * cover the rest of the time.
 */
@Composable
private fun BoxScope.SwipeActionButton(
    action: ItemAction,
    onLeft: Boolean,
    ink: Color,
    open: Boolean,
    offset: () -> Float,
    openWidth: Float,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .align(if (onLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
            .fillMaxHeight()
            .width(SwipeRowActionWidth)
            .absoluteOffset {
                val x = offset()
                val shift = if (onLeft) maxOf(x - openWidth, 0f) else minOf(x + openWidth, 0f)
                IntOffset(shift.roundToInt(), 0)
            }.then(
                if (open) {
                    Modifier.pressable(onClickLabel = action.label, onClick = onClick)
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        action.icon?.let { icon ->
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Text(
            action.label,
            style = AppTypography.caption.strong,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
