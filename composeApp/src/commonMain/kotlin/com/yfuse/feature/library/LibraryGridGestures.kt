package com.yfuse.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
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
 * next count re-lays it out, scrolled so the poster that was under the fingers still is, and every
 * poster flows from where it was drawn to its new place (see [GridReflow]) instead of the whole
 * screen jumping. Letting go settles on the nearest whole count, carrying the pinch's own speed.
 * [columns] null is the adaptive layout the grid has always had, until the person picks a density.
 */
@Stable
internal class GridDensityState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    initialColumns: Int?,
) {
    /** Every poster's way from the old layout to the new one, on one clock. */
    val reflow = GridReflow()

    // How fast the pinch is moving through the column counts, so letting go settles with it.
    private val pinchVelocity = VelocityTracker()

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

    fun beginPinch(
        centroid: Offset,
        timeMillis: Long,
    ) {
        job?.cancel()
        range = columnRange
        startColumns = shownColumns.toFloat()
        target = startColumns
        origin = centroid
        anchor = anchorAt(centroid)
        zooming = true
        pinchVelocity.resetTracking()
        pinchVelocity.addPosition(timeMillis, Offset(target, 0f))
    }

    /** The fingers have spread to [zoom] times their span when the pinch began, at [timeMillis]. */
    fun pinchTo(
        zoom: Float,
        timeMillis: Long,
    ) {
        if (!zooming) return
        target = pinchedColumns(startColumns, zoom, range)
        pinchVelocity.addPosition(timeMillis, Offset(target, 0f))
        val next = settledColumns(target, range)
        if (next != shownColumns) relayout(next)
    }

    fun endPinch() {
        if (!zooming) return
        // Columns a second, as the fingers left the glass.
        val speed = pinchVelocity.calculateVelocity().x.takeIf { it.isFinite() } ?: 0f
        settle(settledColumns(target, range), pinched = true, velocity = speed)
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

    /** Settles on [final] columns; [velocity] is the pinch's, in columns a second, carried into the spring. */
    private fun settle(
        final: Int,
        pinched: Boolean,
        velocity: Float = 0f,
    ) {
        if (final != shownColumns) relayout(final)
        job?.cancel()
        job =
            scope.launch {
                if (!still) {
                    val motion = Animatable(target)
                    motion.animateTo(final.toFloat(), Motion.settle(), initialVelocity = velocity) { target = value }
                }
                target = final.toFloat()
                zooming = false
                anchor = null
                onSettled(final, pinched)
            }
    }

    /**
     * Lays the grid out at [next] columns, scrolled so the anchor poster stays under the fingers.
     * Every poster is noted where it is drawn first, so the new layout can start from there.
     */
    private fun relayout(next: Int) {
        if (!still) reflow.capture(origin, scale())
        val held = anchor
        columns = next
        if (!still) reflow.run(scope)
        if (held == null) return
        val width = gridTileWidth(availableWidth, next, spacingPx)
        val height = width * GRID_POSTER_ASPECT + if (gridShowsTitles(next)) captionPx else 0f
        val top = anchoredRowTop(held.focusY, held.fraction, height)
        gridState.requestScrollToItem(held.index, -top.roundToInt())
    }

    /** A poster of the grid was placed at [coordinates]: see [GridReflow.placed]. */
    fun placed(
        item: GridReflowItem,
        coordinates: LayoutCoordinates,
    ) = reflow.placed(item, coordinates, origin, scale())

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

/**
 * 捏合换密度's continuous re-flow (MO5). A change of column count re-lays the grid out and scrolls
 * it so the poster under the fingers stays put — and a scroll to a position resets the grid's own
 * item animations, so every other poster jumped to its new place in one frame. Instead, just before
 * the switch each poster's drawn rect is noted by key; in the frame of the new layout each is drawn
 * back there, and all of them ease home on one clock. A poster new to the screen fades in on it.
 */
@Stable
internal class GridReflow {
    /** 0 in the frame the columns change, 1 at rest: every poster's way home. */
    var progress by mutableFloatStateOf(1f)
        private set

    /** The grid's own coordinates, inside the pinch's scale, that every poster is measured from. */
    var grid: LayoutCoordinates? = null

    private val items = HashMap<Any, GridReflowItem>()
    private var generation = 0
    private var job: Job? = null

    fun register(
        key: Any,
        item: GridReflowItem,
    ) {
        items[key] = item
    }

    fun unregister(
        key: Any,
        item: GridReflowItem,
    ) {
        if (items[key] === item) items.remove(key)
    }

    /**
     * Just before the columns change: where every poster is drawn now, the pinch's [scale] about
     * [origin] and any flow still under way included.
     */
    fun capture(
        origin: Offset,
        scale: Float,
    ) {
        job?.cancel()
        val grid = grid?.takeIf { it.isAttached }
        val pivot = grid?.let { pivotWithin(origin, it) } ?: origin
        val remaining = 1f - progress
        generation++
        items.values.forEach { item ->
            val placed = item.coordinates?.takeIf { it.isAttached }
            item.before =
                if (grid != null && placed != null) {
                    gridReflowVisual(
                        at = grid.localPositionOf(placed, Offset.Zero),
                        width = placed.size.width.toFloat(),
                        height = placed.size.height.toFloat(),
                        start = item.start,
                        remaining = remaining,
                        origin = pivot,
                        scale = scale,
                    )
                } else {
                    null
                }
            item.captured = generation
        }
        progress = 0f
    }

    /** Eases every poster home from where [capture] left it. */
    fun run(scope: CoroutineScope) {
        job?.cancel()
        job =
            scope.launch {
                animate(0f, 1f, animationSpec = Motion.settle()) { value, _ -> progress = value }
                progress = 1f
            }
    }

    /**
     * A poster was placed at [coordinates]. The first time after a switch it works out where to be
     * drawn from — its rect before the switch, seen through the new layout's [scale] — or, new to
     * the screen, that it fades in.
     */
    fun placed(
        item: GridReflowItem,
        coordinates: LayoutCoordinates,
        origin: Offset,
        scale: Float,
    ) {
        item.coordinates = coordinates
        if (item.resolved == generation) return
        item.resolved = generation
        val grid = grid?.takeIf { it.isAttached }
        val before = item.before?.takeIf { item.captured == generation }
        item.start =
            if (grid != null && before != null) {
                gridReflowStart(
                    before = before,
                    at = grid.localPositionOf(coordinates, Offset.Zero),
                    width = coordinates.size.width.toFloat(),
                    origin = pivotWithin(origin, grid),
                    scale = scale,
                )
            } else {
                GridReflowStart.Arriving
            }
    }

    /** The pinch's origin as the scale uses it: held inside the grid, like its transform origin. */
    private fun pivotWithin(
        origin: Offset,
        grid: LayoutCoordinates,
    ): Offset =
        Offset(
            origin.x.coerceIn(0f, grid.size.width.toFloat()),
            origin.y.coerceIn(0f, grid.size.height.toFloat()),
        )
}

/** One poster's part in a [GridReflow]: where it was, and where it is to be drawn from. */
internal class GridReflowItem {
    var coordinates: LayoutCoordinates? = null
    var before: Rect? = null
    var captured = 0
    var resolved = 0
    var start = GridReflowStart.Resting
}

/**
 * Where a poster is drawn from when a re-flow starts, relative to its new place: moved by [dx], [dy]
 * and scaled by [scale] about its top-left, eased away as the flow runs; or, [arriving], faded in.
 */
@Immutable
internal data class GridReflowStart(
    val dx: Float,
    val dy: Float,
    val scale: Float,
    val arriving: Boolean = false,
) {
    companion object {
        val Resting = GridReflowStart(0f, 0f, 1f)
        val Arriving = GridReflowStart(0f, 0f, 1f, arriving = true)
    }
}

/**
 * Where a poster laid out at [at], [width] by [height], is drawn: moved and scaled by [start] with
 * [remaining] of its flow still to run, then by the pinch's [scale] about [origin].
 */
internal fun gridReflowVisual(
    at: Offset,
    width: Float,
    height: Float,
    start: GridReflowStart,
    remaining: Float,
    origin: Offset,
    scale: Float,
): Rect {
    val r = remaining.coerceIn(0f, 1f)
    val itemScale = 1f + (start.scale - 1f) * r
    val left = origin.x + (at.x + start.dx * r - origin.x) * scale
    val top = origin.y + (at.y + start.dy * r - origin.y) * scale
    return Rect(left, top, left + width * itemScale * scale, top + height * itemScale * scale)
}

/**
 * Where a poster newly laid out at [at], [width] wide, has to start so that — through the new
 * layout's [scale] about [origin] — it is drawn exactly at [before], where the old layout drew it.
 */
internal fun gridReflowStart(
    before: Rect,
    at: Offset,
    width: Float,
    origin: Offset,
    scale: Float,
): GridReflowStart {
    if (scale <= 0f || width <= 0f || !scale.isFinite()) return GridReflowStart.Resting
    return GridReflowStart(
        dx = (before.left - origin.x) / scale + origin.x - at.x,
        dy = (before.top - origin.y) / scale + origin.y - at.y,
        scale = before.width / (width * scale),
    )
}

/**
 * A poster of the pinchable grid: drawn from where it was before the last change of columns,
 * easing home as [GridDensityState.reflow] runs. At rest it is left as it is, and costs nothing.
 */
@Composable
internal fun Modifier.gridReflowItem(
    state: GridDensityState,
    key: Any,
): Modifier {
    val reflow = state.reflow
    val item = remember(reflow, key) { GridReflowItem() }
    DisposableEffect(reflow, key, item) {
        reflow.register(key, item)
        onDispose { reflow.unregister(key, item) }
    }
    // A layer only while a pinch or its flow is under way: a scrolling grid pays for none.
    val flowing by remember(state, reflow) { derivedStateOf { state.zooming || reflow.progress < 1f } }
    return this
        .onPlaced { state.placed(item, it) }
        .then(
            if (flowing) {
                Modifier.graphicsLayer {
                    val remaining = 1f - reflow.progress
                    if (remaining <= 0f) return@graphicsLayer
                    val start = item.start
                    if (start.arriving) {
                        alpha = reflow.progress
                    } else {
                        val itemScale = 1f + (start.scale - 1f) * remaining
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = start.dx * remaining
                        translationY = start.dy * remaining
                        scaleX = itemScale
                        scaleY = itemScale
                    }
                }
            } else {
                Modifier
            },
        )
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
                val time = event.changes.maxOfOrNull { it.uptimeMillis } ?: 0L
                if (!pinching && event.changes.count { it.pressed } >= 2 && latestEnabled()) {
                    pinching = true
                    zoom = 1f
                    state.beginPinch(event.calculateCentroid(useCurrent = true), time)
                }
                if (pinching) {
                    zoom *= event.calculateZoom()
                    state.pinchTo(zoom, time)
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
