package com.yfuse.tv.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

// 焦点固定位 — the Apple TV and Google TV way of scrolling: focus rests at one place on the
// screen and the content slides underneath it, instead of focus walking to the edge and the row
// catching up a card at a time. Every scrolling container on the television asks the spec
// provided here how far to move when something inside it takes focus.

/** Where focus rests, and the arithmetic behind it — plain numbers, so it is tested without a screen. */
internal object TvFocusPivot {
    /** A card row: the focused card's leading edge a third of the way in. */
    const val ROW_FRACTION = 1f / 3f

    /** A page, a list or a grid: the focused item's top three tenths of the way down. */
    const val PAGE_FRACTION = 0.3f

    /**
     * How far a container [containerSize] long scrolls, positive forward, to bring an item [size]
     * long whose leading edge is at [offset] to its resting place [fraction] of the way along.
     *
     * The container clamps what it is asked for at either end of its content, so the first cards
     * of a row and the last stay where the content puts them — no gap opens before the first or
     * after the last. An item too long for the room after the pivot lines its far edge up with the
     * container's instead, so it is never cut; one longer than the container itself moves the
     * least it can, as it did before, rather than hiding its top.
     */
    fun scrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
        fraction: Float,
    ): Float {
        if (containerSize <= 0f) return 0f
        if (size > containerSize) return leastScroll(offset, size, containerSize)
        val pivot = containerSize * fraction.coerceIn(0f, 1f)
        val target = if (containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }

    /**
     * Where a restore scrolls a lazy container to put its item straight at the pivot: the item
     * at [fraction] of a [viewport] whose content starts [beforeContentPadding] in. Scrolled to the
     * start instead, the item would glide on to the pivot as soon as it took focus.
     */
    fun restoreLead(
        viewport: Int,
        beforeContentPadding: Int,
        fraction: Float,
    ): Int = (viewport * fraction.coerceIn(0f, 1f) - beforeContentPadding).roundToInt().coerceAtLeast(0)

    /** [restoreLead] for a lazy container scrolling along [orientation]: rows take [ROW_FRACTION]. */
    fun restoreLead(
        orientation: Orientation,
        viewport: IntSize,
        beforeContentPadding: Int,
    ): Int =
        when (orientation) {
            Orientation.Horizontal -> restoreLead(viewport.width, beforeContentPadding, ROW_FRACTION)
            Orientation.Vertical -> restoreLead(viewport.height, beforeContentPadding, PAGE_FRACTION)
        }

    /** The platform's own rule, for an item longer than its container: align the nearer edge. */
    private fun leastScroll(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

/**
 * The spec itself. [animation] is [TvFocusMotion.spec]: the critically damped focus spring, or a
 * cut under 减少动画.
 *
 * Compose 1.12 still plays [scrollAnimationSpec] for a plain scrolling column, which is why it is
 * set; its lazy lists and grids wrap the spec for their sticky headers and glide on the default
 * spring, which has the same damping and stiffness (TvFocusPivotTest holds the two together). The
 * system's 移除动画 zeroes Compose's animation scale, and every container then jumps.
 */
internal data class TvPivotBringIntoViewSpec(
    val fraction: Float,
    private val animation: AnimationSpec<Float>,
) : BringIntoViewSpec {
    // Deprecated upstream in favour of the container's own animation, but still read by
    // non-lazy containers in this Compose version — see the class comment.
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = animation

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = TvFocusPivot.scrollDistance(offset, size, containerSize, fraction)
}

/** The television's root: every page, list and grid below pivots at [TvFocusPivot.PAGE_FRACTION]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideTvFocusPivot(
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val spec =
        remember(reduceMotion) {
            TvPivotBringIntoViewSpec(TvFocusPivot.PAGE_FRACTION, TvFocusMotion.spec(reduceMotion))
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * A card row: the focused card rests a third of the way in, on the page's clock. A row outside the
 * television's root — a preview, a test — keeps whatever spec it was given.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideTvRowPivot(content: @Composable () -> Unit) {
    val page = LocalBringIntoViewSpec.current
    val row =
        remember(page) {
            (page as? TvPivotBringIntoViewSpec)?.copy(fraction = TvFocusPivot.ROW_FRACTION) ?: page
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides row, content = content)
}

/**
 * Keeps a hero whole while focus moves about inside it: whatever inside it takes focus, the page is
 * asked to show all of it. Asked for its 播放 key alone, the page would pivot that key to three
 * tenths down on arrival and push the title and the 返回 above it off the top of the screen.
 */
internal fun Modifier.tvKeepWholeInView(): Modifier = this then TvWholeInViewElement

private object TvWholeInViewElement : ModifierNodeElement<TvWholeInViewNode>() {
    override fun create(): TvWholeInViewNode = TvWholeInViewNode()

    override fun update(node: TvWholeInViewNode) = Unit

    override fun hashCode(): Int = "tvKeepWholeInView".hashCode()

    override fun equals(other: Any?): Boolean = other === this
}

private class TvWholeInViewNode :
    Modifier.Node(),
    BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) {
        // Typed as the node, so this is the request for the node's own bounds, sent on up to the
        // scrolling parent, and not a call back into this override.
        val node: DelegatableNode = this
        node.bringIntoView()
    }
}
