package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.glass
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * What a drag on the seek bar is doing, for whatever follows it above the rail: how finely it
 * moves, how far up the finger has lifted, and the filmstrip frame under it. The bar writes it on
 * every sample; [liftPx] is only ever read while placing, so following the finger up never
 * recomposes anything.
 */
@Stable
internal class SeekScrubUi {
    var tier by mutableStateOf(SeekScrubTier.Normal)
    var liftPx by mutableFloatStateOf(0f)
    var frame by mutableIntStateOf(0)

    /** The finger is off the rail: the next drag starts at normal speed, on the rail. */
    fun reset() {
        tier = SeekScrubTier.Normal
        liftPx = 0f
    }

    /** How far to raise a preview so the finger never covers it, in whole pixels. */
    fun previewLiftPx(maxPx: Float): Int = liftPx.coerceIn(0f, maxPx.coerceAtLeast(0f)).roundToInt()
}

/**
 * The highest a preview rises with the finger. Past it the finger is well clear of the rail and
 * the preview stays put rather than climbing into the title bar.
 */
internal val SeekPreviewMaxLift: Dp = 160.dp

/**
 * The seek bar's drag. It starts like a horizontal drag — only a sideways move past touch slop
 * starts it, so a vertical swipe across the bar is left alone — and from then on reports every
 * move, upward ones included. Moving up off the rail is how the drag turns finer, and the stock
 * horizontal detector only reports moves that change x: a finger rising straight up went unheard.
 *
 * [onDragStart] gets where the finger went down, which the lift is measured from, and where the
 * drag began. A release ends the drag; anything else — a cancelled stream, a consumed move —
 * cancels it, as before.
 */
internal suspend fun PointerInputScope.detectSeekScrubGestures(
    onDragStart: (down: Offset, start: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (PointerInputChange) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start =
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
        onDragStart(down.position, start.position)
        val released =
            drag(start.id) { change ->
                onDrag(change)
                change.consume()
            }
        if (released) onDragEnd() else onDragCancel()
    }
}

/** The strip's frame size and spacing, and its width with [slots] frames. */
internal val SeekFilmstripFrameWidth: Dp = 64.dp

internal val SeekFilmstripGap: Dp = 4.dp

internal val SeekFilmstripPadding: Dp = 6.dp

internal fun filmstripWidth(slots: Int): Dp =
    SeekFilmstripFrameWidth * slots + SeekFilmstripGap * (slots - 1).coerceAtLeast(0) + SeekFilmstripPadding * 2

/**
 * 胶片条: the storyboard's frames either side of [selected], one per step of the finger. The
 * selected frame keeps the middle, ringed and at full strength; its neighbours are dimmed, and
 * slots past either end of the file stay empty rather than sliding the selection off centre.
 *
 * Only ever seen under a finger, so it says nothing to a screen reader: the bar's own
 * adjustable semantics, and Shift + an arrow key, reach the same frames.
 */
@Composable
internal fun SeekFilmstrip(
    storyboard: TrickplayStoryboard,
    frames: SeekFilmstripFrames,
    selected: Int,
    slots: Int,
    modifier: Modifier = Modifier,
) {
    val frameHeight = SeekFilmstripFrameWidth * storyboard.height / storyboard.width.coerceAtLeast(1)
    val half = slots / 2
    Column(
        modifier
            .clearAndSetSemantics {}
            .glass(
                shape = AppShapes.thumb,
                fill = Color(0xFF151821).copy(alpha = 0.82f),
                border = Color.White.copy(alpha = 0.22f),
            ).padding(SeekFilmstripPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            filmstripHeading(frames.stepMs),
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SeekFilmstripGap)) {
            for (offset in -half..half) {
                val index = selected + offset
                val present = index in 0 until frames.count
                val current = offset == 0
                Column(
                    Modifier.graphicsLayer { alpha = if (current) 1f else FILMSTRIP_NEIGHBOUR_ALPHA },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(SeekFilmstripFrameWidth, frameHeight)
                            .clip(AppShapes.thumb)
                            .then(if (present) Modifier.background(Color.Black) else Modifier)
                            .then(
                                if (current) {
                                    Modifier.border(1.5.dp, Color.White, AppShapes.thumb)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        if (present) {
                            TrickplayImage(
                                storyboard = storyboard,
                                frame = storyboard.frameAt(frames.startMs(index)),
                                description = "",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        if (present) formatTime(frames.startMs(index)) else "",
                        style = if (current) AppTypography.caption.strong else AppTypography.caption.regular,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** How much of themselves the frames beside the selected one show. */
private const val FILMSTRIP_NEIGHBOUR_ALPHA = 0.62f
