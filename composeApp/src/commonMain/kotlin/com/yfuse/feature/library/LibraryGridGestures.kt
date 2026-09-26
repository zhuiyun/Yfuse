package com.yfuse.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 捏合换密度 — how many posters a row of the library grid holds, and the pinch that changes it.
 *
 * While the fingers move the grid is laid out at a whole number of columns and scaled to the
 * number in between that the fingers ask for, about where they went down; crossing halfway to the
 * next count re-lays it out, scrolled so the poster that was under the fingers still is. Letting go
 * settles on the nearest whole count. [columns] null is the adaptive layout the grid has always had,
 * until the person picks a density.
 */
@Stable
internal class GridDensityState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    initialColumns: Int?,
) {
    var columns by mutableStateOf(initialColumns)
        private set

    /** The pinch or the − / + step is under way, or settling. */
    var zooming by mutableStateOf(false)
        private set

    /** Where the zoom is centred, in the grid's own pixels. */
    var origin by mutableStateOf(Offset.Zero)
        private set

    private var target by mutableFloatStateOf(0f)
    private var startColumns = 3f
    private var range = GRID_MIN_COLUMNS..GRID_DENSE_COLUMNS
    private var anchor: GridAnchor? = null
    private var job: Job? = null

    // Layout facts, refreshed by the screen. The width is read by the − / + buttons' composition.
    var availableWidth by mutableFloatStateOf(0f)
    var spacingPx = 0f
    var minTilePx = 0f
    var captionPx = 0f
    var still = false

    /** A settled density, from a pinch ([pinched]) or the buttons: remember it, retire the tip. */
    var onSettled: (columns: Int, pinched: Boolean) -> Unit = { _, _ -> }

    /** The columns the grid is laid out at now. */
    val shownColumns: Int get() = columns ?: adaptiveGridColumns(availableWidth, minTilePx, spacingPx)

    val columnRange: IntRange get() = gridColumnRange(adaptiveGridColumns(availableWidth, minTilePx, spacingPx))

    /** How much the laid-out grid is drawn larger or smaller than itself right now. */
    fun scale(): Float = if (zooming && target > 0f) pinchScale(shownColumns, target) else 1f

    fun transformOrigin(
        width: Float,
        height: Float,
    ): TransformOrigin =
        if (width > 0f && height > 0f) {
            TransformOrigin((origin.x / width).coerceIn(0f, 1f), (origin.y / height).coerceIn(0f, 1f))
        } else {
            TransformOrigin.Center
        }

    fun beginPinch(centroid: Offset) {
        job?.cancel()
        range = columnRange
        startColumns = shownColumns.toFloat()
        target = startColumns
        origin = centroid
        anchor = anchorAt(centroid)
        zooming = true
    }

    /** The fingers have spread to [zoom] times their span when the pinch began. */
    fun pinchTo(zoom: Float) {
        if (!zooming) return
        target = pinchedColumns(startColumns, zoom, range)
        val next = settledColumns(target, range)
        if (next != shownColumns) relayout(next)
    }

    fun endPinch() {
        if (!zooming) return
        settle(settledColumns(target, range), pinched = true)
    }

    /** The title bar's − / +: one column more or fewer, zoomed from the top of what is on screen. */
    fun step(delta: Int) {
        if (zooming) return
        range = columnRange
        val current = shownColumns
        val next = (current + delta).coerceIn(range)
        if (next == current) return
        // Held by the poster at the top middle rather than the row's first: asking the grid for
        // the position it already has would leave every tile to spring to its new place on top
        // of the zoom, where a real move of the scroll position lays them out in one go.
        origin = Offset(availableWidth / 2f, 0f)
        anchor = anchorAt(origin)
        target = current.toFloat()
        zooming = true
        relayout(next)
        settle(next, pinched = false)
    }

    private fun settle(
        final: Int,
        pinched: Boolean,
    ) {
        if (final != shownColumns) relayout(final)
        job?.cancel()
        job =
            scope.launch {
                if (!still) {
                    val motion = Animatable(target)
                    motion.animateTo(final.toFloat(), Motion.settle()) { target = value }
                }
                target = final.toFloat()
                zooming = false
                anchor = null
                onSettled(final, pinched)
            }
    }

    /** Lays the grid out at [next] columns, scrolled so the anchor poster stays under the fingers. */
    private fun relayout(next: Int) {
        val held = anchor
        columns = next
        if (held == null) return
        val width = gridTileWidth(availableWidth, next, spacingPx)
        val height = width * GRID_POSTER_ASPECT + if (gridShowsTitles(next)) captionPx else 0f
        val top = anchoredRowTop(held.focusY, held.fraction, height)
        gridState.requestScrollToItem(held.index, -top.roundToInt())
    }

    /** The poster under [point], or the nearest one to it, and how far down it the point is. */
    private fun anchorAt(point: Offset): GridAnchor? {
        val items = gridState.layoutInfo.visibleItemsInfo
        val item = items.firstOrNull { it.contains(point) } ?: items.minByOrNull { it.distanceTo(point) } ?: return null
        val height = item.size.height.toFloat()
        if (height <= 0f) return null
        val tile = item.size.width.toFloat()
        if (gridShowsTitles(shownColumns)) captionPx = max(0f, height - tile * GRID_POSTER_ASPECT)
        return GridAnchor(
            item.index,
            focusY = point.y,
            fraction = ((point.y - item.offset.y) / height).coerceIn(0f, 1f),
        )
    }
}

/** A poster held in place across a re-layout: its index, where the fingers are, and how far down it they are. */
private class GridAnchor(
    val index: Int,
    val focusY: Float,
    val fraction: Float,
)

private fun LazyGridItemInfo.contains(point: Offset): Boolean =
    point.x >= offset.x &&
        point.x < offset.x + size.width &&
        point.y >= offset.y &&
        point.y < offset.y + size.height

private fun LazyGridItemInfo.distanceTo(point: Offset): Float =
    abs(point.x - (offset.x + size.width / 2f)) + abs(point.y - (offset.y + size.height / 2f))

/**
 * The pinch: a second finger turns whatever the first was doing — a scroll, a press on a poster
 * about to lift into its menu — into a pinch, and every event until the last finger is up belongs
 * to it (doc 8: 第二根手指落下即进入捏合，取消单指手势). [enabled] is asked when the second finger lands.
 */
@Composable
internal fun Modifier.gridPinch(
    state: GridDensityState,
    enabled: () -> Boolean,
): Modifier {
    val latestEnabled by rememberUpdatedState(enabled)
    return this.pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var zoom = 1f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!pinching && event.changes.count { it.pressed } >= 2 && latestEnabled()) {
                    pinching = true
                    zoom = 1f
                    state.beginPinch(event.calculateCentroid(useCurrent = true))
                }
                if (pinching) {
                    zoom *= event.calculateZoom()
                    state.pinchTo(zoom)
                    // Consumed on the way down, so the long press under the first finger, the
                    // poster's click and the grid's own scroll all let go.
                    event.changes.forEach { it.consume() }
                }
                if (event.changes.none { it.pressed }) break
            }
            if (pinching) state.endPinch()
        }
    }
}

/** The title bar's − / +: the same thing the pinch does, one column at a time. */
@Composable
internal fun GridDensityButtons(
    state: GridDensityState,
    modifier: Modifier = Modifier,
) {
    val range = state.columnRange
    val shown = state.shownColumns
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        GridDensityButton(glyph = "−", label = "缩小海报", enabled = shown < range.last) { state.step(1) }
        GridDensityButton(glyph = "+", label = "放大海报", enabled = shown > range.first) { state.step(-1) }
    }
}

@Composable
private fun GridDensityButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .pressable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .touchTarget()
            .size(30.dp)
            .glass(CircleShape)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = AppTypography.body.strong, color = palette.text, maxLines = 1)
    }
}

private const val DISABLED_ALPHA = 0.4f

/**
 * 快速滚动索引: the grid's sections down its right edge — letters for 名称, years, months added,
 * scores. A finger on the strip jumps the grid to the section under it, with a quick tick at each
 * new section and the section's name beside the finger. A shortcut only: the grid still scrolls,
 * and a screen reader is not shown this at all.
 */
@Composable
internal fun BoxScope.GridIndexStrip(
    sections: List<GridIndexSection>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val haptics = LocalHaptics.current
    val latestSections by rememberUpdatedState(sections)
    val latestJump by rememberUpdatedState(onJump)
    var active by remember { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier
            .align(Alignment.CenterEnd)
            .width(IndexStripWidth)
            .fillMaxHeight()
            .clearAndSetSemantics { },
    ) {
        val slots = max(1, (maxHeight / IndexStripRow).toInt())
        val stops = remember(sections.size, slots) { indexStripStops(sections.size, slots) }
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var pointer = down.id
                        var y = down.position.y
                        while (true) {
                            val index = indexSectionAt(y / size.height, latestSections.size)
                            fingerY = y
                            if (index >= 0 && index != active) {
                                active = index
                                haptics.play(HapticSignal.FrequentTick)
                                latestJump(latestSections[index].firstIndex)
                            }
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pointer = change.id
                            y = change.position.y
                        }
                        active = null
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            stops.forEach { stop ->
                Text(
                    sections[stop].label.short,
                    style = AppTypography.caption.strong,
                    color = if (active == stop) accent else palette.sub2,
                    maxLines = 1,
                )
            }
        }
    }
    active?.let { index ->
        sections.getOrNull(index)?.let { section ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            -(IndexStripWidth + IndexBubbleGap).roundToPx(),
                            fingerY.roundToInt() - IndexBubbleHalfHeight.roundToPx(),
                        )
                    }.glass(AppShapes.chip, palette.glassStrong, palette.tabbarBorder)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(section.label.long, style = AppTypography.section.strong, color = palette.text, maxLines = 1)
            }
        }
    }
}

private val IndexStripWidth = 26.dp
private val IndexStripRow = 16.dp
private val IndexBubbleGap = 8.dp
private val IndexBubbleHalfHeight = 18.dp
