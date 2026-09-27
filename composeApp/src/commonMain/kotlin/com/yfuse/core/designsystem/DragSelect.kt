package com.yfuse.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ the numbers (5.4)

/** A sweeping finger this close to the list's top or bottom edge scrolls the list. */
internal val DragSelectEdge = 44.dp

/** How fast the list scrolls, per second, with the finger at the very edge; slower further in. */
internal val DragSelectMaxScrollSpeed = 1_400.dp

/** A frame longer than this — a stall — scrolls no further than this one would. */
private const val DRAG_SELECT_MAX_FRAME_MS = 48L

/**
 * The selection a sweep makes: [base] with every key from [anchor] to [current] — indices into
 * [keys], in either order — added when [adding], taken out when not. Rows outside the sweep keep
 * what [base] gave them, so sweeping back over a row returns it to how it was before.
 */
fun <K> sweepSelection(
    keys: List<K>,
    base: Set<K>,
    anchor: Int,
    current: Int,
    adding: Boolean,
): Set<K> {
    if (anchor !in keys.indices) return base
    val end = current.coerceIn(keys.indices)
    val swept = keys.subList(minOf(anchor, end), maxOf(anchor, end) + 1)
    return if (adding) base + swept else base - swept.toSet()
}

/**
 * How fast, in pixels per second, a sweep held at [pointer] scrolls a list whose viewport runs
 * from [top] to [bottom]: up (negative) within [edge] of the top, down within [edge] of the
 * bottom, in proportion to how deep into that band the finger is — full [maxSpeed] at the edge
 * and beyond it — and not at all in between.
 */
fun dragSelectScrollSpeed(
    pointer: Float,
    top: Float,
    bottom: Float,
    edge: Float,
    maxSpeed: Float,
): Float {
    if (!pointer.isFinite() || edge <= 0f || bottom <= top || maxSpeed <= 0f) return 0f
    val intoTop = top + edge - pointer
    val intoBottom = pointer - (bottom - edge)
    return when {
        intoTop > 0f && intoTop >= intoBottom -> -maxSpeed * (intoTop / edge).coerceAtMost(1f)
        intoBottom > 0f -> maxSpeed * (intoBottom / edge).coerceAtMost(1f)
        else -> 0f
    }
}

/** One placed row a sweep can land on: its key and where it spans vertically. */
internal class DragSelectRowSpan<K>(
    val key: K,
    val top: Float,
    val bottom: Float,
)

/**
 * The row at [y]. With [exact], only a row the point is inside; otherwise the nearest one, so a
 * finger over the gap between two rows, or past the last one, still has a row to reach.
 */
internal fun <K> dragSelectRowAt(
    y: Float,
    rows: List<DragSelectRowSpan<K>>,
    exact: Boolean,
): K? {
    if (!y.isFinite()) return null
    val nearest =
        rows.minByOrNull { row ->
            when {
                y < row.top -> row.top - y
                y > row.bottom -> y - row.bottom
                else -> 0f
            }
        } ?: return null
    return if (!exact || (y >= nearest.top && y <= nearest.bottom)) nearest.key else null
}

/**
 * 长按拖选 for one list: the rows a sweep can reach ([dragSelectRow]) and whether one is under
 * way ([sweeping]). Create with [rememberDragSelectState], give the list [dragSelect].
 */
@Stable
class DragSelectState<K : Any> {
    /**
     * Each placed row's node and the key it shows now. Keyed by node rather than by key: a list
     * reuses a node for another row, and the node then reports its new key over its old one.
     */
    internal val rows = HashMap<LayoutCoordinates, K>()

    /** Whether a finger is sweeping now. */
    var sweeping by mutableStateOf(false)
        internal set

    internal fun keyAt(
        rootY: Float,
        exact: Boolean,
    ): K? {
        rows.keys.removeAll { !it.isAttached }
        val spans =
            rows.map { (coordinates, key) ->
                val bounds = coordinates.boundsInRoot()
                DragSelectRowSpan(key, bounds.top, bounds.bottom)
            }
        return dragSelectRowAt(rootY, spans, exact)
    }
}

@Composable
fun <K : Any> rememberDragSelectState(): DragSelectState<K> = remember { DragSelectState() }

/** Marks this row as one a sweep of [state]'s list can select, under [key]. */
fun <K : Any> Modifier.dragSelectRow(
    state: DragSelectState<K>,
    key: K,
): Modifier = onPlaced { state.rows[it] = key }

/**
 * 长按拖选 (5.4) on a lazy list, given to the list itself.
 *
 * A press held still on a row for the long-press timeout (400 ms by default, the person's own
 * choice where they have changed it) toggles that row — selecting it, or deselecting it when it
 * was already selected — with [HapticSignal.DragStart]. Without lifting, moving up or down makes
 * the same change to every row between the first one and the finger, one [HapticSignal.Tick]
 * each time the range grows or shrinks; moving back gives the rows left behind their earlier
 * state. Held within [DragSelectEdge] of the list's top or bottom, the finger scrolls the list,
 * faster the deeper it goes, and the sweep follows the rows that arrive.
 *
 * A press that moves first is a scroll or a swipe, and the list gets it as before. Once a sweep
 * has started it takes every later event on the way down, before the list and its rows, so
 * nothing scrolls under it and the row it started on does not also take a tap when it ends.
 *
 * [keys] are the list's selectable rows in order; each row carries [dragSelectRow] with its key.
 * [onSelectionChange] is handed the whole selection the sweep makes, each time it changes.
 */
@Composable
fun <K : Any> Modifier.dragSelect(
    state: DragSelectState<K>,
    listState: LazyListState,
    keys: List<K>,
    selection: Set<K>,
    onSelectionChange: (Set<K>) -> Unit,
    enabled: Boolean = true,
): Modifier {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val latestKeys by rememberUpdatedState(keys)
    val latestSelection by rememberUpdatedState(selection)
    val latestChange by rememberUpdatedState(onSelectionChange)
    val latestHaptics by rememberUpdatedState(haptics)
    // The list's own node, for turning a finger into the root coordinates the rows report in.
    val list = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onPlaced { list[0] = it }
        .pointerInput(state, listState, enabled) {
            if (!enabled) return@pointerInput
            val edge = DragSelectEdge.toPx()
            val maxSpeed = DragSelectMaxScrollSpeed.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val container = list[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val anchorKey =
                    state.keyAt(container.localToRoot(pressed.position).y, exact = true)
                        ?: return@awaitEachGesture
                if (anchorKey !in latestKeys) return@awaitEachGesture
                val base = latestSelection
                val adding = anchorKey !in base
                var reached = anchorKey
                var shown = if (adding) base + anchorKey else base - anchorKey
                latestChange(shown)
                latestHaptics.play(HapticSignal.DragStart)
                state.sweeping = true
                var pointerY = pressed.position.y

                fun sweepTo(y: Float) {
                    val keysNow = latestKeys
                    val anchor = keysNow.indexOf(anchorKey)
                    val key = state.keyAt(container.localToRoot(Offset(0f, y)).y, exact = false) ?: return
                    val index = keysNow.indexOf(key)
                    if (anchor < 0 || index < 0 || key == reached) return
                    reached = key
                    val next = sweepSelection(keysNow, base, anchor, index, adding)
                    if (next != shown) {
                        shown = next
                        latestChange(next)
                        latestHaptics.play(HapticSignal.Tick)
                    }
                }

                // Runs by the frame, so a finger held still at an edge keeps the list moving.
                val scroller =
                    scope.launch {
                        var last = withFrameMillis { it }
                        while (true) {
                            val now = withFrameMillis { it }
                            val seconds = (now - last).coerceIn(0L, DRAG_SELECT_MAX_FRAME_MS) / 1_000f
                            last = now
                            val speed =
                                dragSelectScrollSpeed(
                                    pointer = pointerY,
                                    top = 0f,
                                    bottom = container.size.height.toFloat(),
                                    edge = edge,
                                    maxSpeed = maxSpeed,
                                )
                            if (speed != 0f && container.isAttached) {
                                listState.scrollBy(speed * seconds)
                                sweepTo(pointerY)
                            }
                        }
                    }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == pressed.id } ?: break
                        if (!change.pressed) break
                        pointerY = change.position.y
                        sweepTo(pointerY)
                    }
                } finally {
                    scroller.cancel()
                    state.sweeping = false
                }
            }
        }
}
