package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuFilter
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.ToastAction
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.util.rememberShareHandler
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

/** How far the toast sits above its usual floor, clear of the player's progress bar. */
private val DanmakuToastLift = 96.dp

/** A comment quoted in a toast is cut to this many characters. */
private const val DANMAKU_QUOTE_CHARS = 12

/** What putting an entry on the block list came to. */
internal sealed interface DanmakuBlockOutcome {
    /** On the list now, as [entry] — what 撤销 takes off it again. */
    data class Added(
        val entry: String,
    ) : DanmakuBlockOutcome

    data object AlreadyBlocked : DanmakuBlockOutcome

    data object ListFull : DanmakuBlockOutcome
}

/**
 * 点弹幕 across the three layouts it touches: the overlay that draws the comments
 * ([DanmakuOverlay]), the picture whose tap decides whether a tap was a tap (the chrome's tap
 * catcher), and the menu above the chrome ([DanmakuPickLayer]). This carries a finger from one's
 * frame into another's; what the finger found is [state]'s business.
 */
@Stable
internal class DanmakuPicker {
    val state = DanmakuPickState()

    /** The chrome's root, where presses and taps are reported. */
    var touchFrame: LayoutCoordinates? = null

    /** The overlay's comment area. */
    var laneFrame: LayoutCoordinates? = null

    var density: Float = 1f

    private fun toLane(local: Offset): Offset? {
        val touch = touchFrame?.takeIf { it.isAttached } ?: return null
        val lane = laneFrame?.takeIf { it.isAttached } ?: return null
        return lane.localPositionOf(touch, local) / density
    }

    /** A finger went down at [local], in the chrome's frame. */
    fun press(local: Offset) {
        toLane(local)?.let(state::press)
    }

    /** The picture's single tap at [local]: true when it landed on a comment, which then stops. */
    fun claim(local: Offset): Boolean = toLane(local)?.let(state::claim) ?: false

    /** The stopped comment's box in [frame]'s pixels, while its menu is open. */
    fun heldBoundsIn(frame: LayoutCoordinates): Rect? {
        val hold = state.hold?.takeIf { it.held } ?: return null
        val lane = laneFrame?.takeIf { it.isAttached } ?: return null
        if (!frame.isAttached) return null
        val box = hold.boundsAt(renderedMs = 0L)
        val topLeft = frame.localPositionOf(lane, Offset(box.left * density, box.top * density))
        return Rect(topLeft, Size(box.width * density, box.height * density))
    }
}

/**
 * Notes where each finger lands, for [DanmakuPicker], and takes nothing from anyone.
 *
 * Chained on the chrome's root it sees every press first — on the picture or on a key — which is
 * the one moment the comment under the finger is exactly where it is drawn: the tap it may turn
 * into is only confirmed a double-tap window later, when that comment has flown on.
 */
internal fun Modifier.danmakuPressWatch(picker: DanmakuPicker?): Modifier =
    if (picker == null) {
        this
    } else {
        onPlaced { picker.touchFrame = it }.pointerInput(picker) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { picker.press(it.position) }
                }
            }
        }
    }

/**
 * 点弹幕's menu — 复制, 屏蔽此词, 屏蔽同类 — beside the comment a tap stopped, over everything else.
 *
 * While it is open a press anywhere else closes it and goes no further: it is a popup, and the
 * press that dismisses it should not also pause the film or hide the controls. The comment then
 * sets off again from where it stopped; blocking it takes it off the screen with the rest of its
 * kind. The block toast offers 撤销 for [TOAST_UNDO_WINDOW_MS][com.yfuse.core.designsystem.TOAST_UNDO_WINDOW_MS].
 */
@Composable
internal fun BoxScope.DanmakuPickLayer(
    picker: DanmakuPicker,
    onBlock: (String) -> DanmakuBlockOutcome,
    onUnblock: (String) -> Unit,
) {
    val hold = picker.state.hold?.takeIf { it.held }
    val haptics = LocalHaptics.current
    val share = rememberShareHandler()
    var notice by remember { mutableStateOf<DanmakuNotice?>(null) }
    PlatformBackHandler(enabled = hold != null) { picker.state.release() }
    // Leaving for 画中画 or closing the player lets the comment go rather than leaving it pinned.
    DisposableEffect(picker) {
        onDispose { picker.state.release() }
    }

    fun block(
        entry: String?,
        quote: String,
        similar: Boolean,
    ) {
        picker.state.release()
        val outcome = entry?.let(onBlock) ?: DanmakuBlockOutcome.AlreadyBlocked
        val short = if (quote.length > DANMAKU_QUOTE_CHARS) quote.take(DANMAKU_QUOTE_CHARS) + "…" else quote
        notice =
            when (outcome) {
                is DanmakuBlockOutcome.Added -> {
                    haptics.play(HapticSignal.Confirm)
                    DanmakuNotice(if (similar) "已屏蔽同类「$short」" else "已屏蔽「$short」", undo = outcome.entry)
                }
                DanmakuBlockOutcome.AlreadyBlocked -> {
                    haptics.play(HapticSignal.Reject)
                    DanmakuNotice("「$short」已在屏蔽列表里")
                }
                DanmakuBlockOutcome.ListFull -> {
                    haptics.play(HapticSignal.Reject)
                    DanmakuNotice("屏蔽词已满，请先在设置里删掉一些")
                }
            }
    }

    if (hold != null) {
        var frame by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(Modifier.fillMaxSize().onPlaced { frame = it }) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(picker) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            picker.state.release()
                        }
                    },
            )
            val anchor = frame?.let(picker::heldBoundsIn)
            if (anchor != null) {
                DanmakuMenu(
                    anchor = anchor,
                    appearKey = hold,
                    onCopy = {
                        share.copyText(hold.comment.text)
                        haptics.play(HapticSignal.Confirm)
                        picker.state.release()
                    },
                    onBlockWord = { block(hold.comment.text.trim(), hold.comment.text, similar = false) },
                    onBlockSimilar = {
                        block(DanmakuFilter.similarRule(hold.comment.text), hold.comment.text, similar = true)
                    },
                )
            }
        }
    }

    val current = notice
    ActionToast(
        message = current?.message,
        onDismiss = { notice = null },
        modifier = Modifier.padding(bottom = DanmakuToastLift),
        action =
            current?.undo?.let { entry ->
                ToastAction("撤销") {
                    onUnblock(entry)
                    notice = null
                }
            },
    )
}

private class DanmakuNotice(
    val message: String,
    val undo: String? = null,
)

@Composable
private fun DanmakuMenu(
    anchor: Rect,
    appearKey: Any,
    onCopy: () -> Unit,
    onBlockWord: () -> Unit,
    onBlockSimilar: () -> Unit,
) {
    val density = LocalDensity.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val shown = remember(appearKey) { Animatable(0f) }
    LaunchedEffect(shown, still) {
        shown.animateTo(1f, Motion.tween(if (still) Motion.REDUCED_FADE else Motion.STANDARD))
    }
    val gap = with(density) { 8.dp.toPx() }
    val margin = with(density) { 16.dp.toPx() }
    Row(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val at =
                    danmakuMenuPosition(
                        comment = anchor,
                        menu = Size(placeable.width.toFloat(), placeable.height.toFloat()),
                        bounds = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                        gap = gap,
                        margin = margin,
                    )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(at.x.roundToInt(), at.y.roundToInt())
                }
            }.graphicsLayer {
                alpha = shown.value
                // Grows out of the comment it belongs to; under 减弱动态效果 it only fades.
                if (!still) {
                    val scale = 0.92f + 0.08f * shown.value
                    scaleX = scale
                    scaleY = scale
                }
            }.glass(shape = GlassShapes.chip, fill = PlayerTokens.panelFill, border = PlayerTokens.chipBorder)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DanmakuMenuItem("复制", onCopy)
        DanmakuMenuItem("屏蔽此词", onBlockWord)
        DanmakuMenuItem("屏蔽同类", onBlockSimilar)
    }
}

@Composable
private fun DanmakuMenuItem(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppTypography.caption.medium, color = Color.White)
    }
}
