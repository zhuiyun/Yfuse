package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- 跟手返回 geometry
//
// Everything a page does while it is pulled back into the poster it came from, as plain
// functions of the gesture: the pull-down and the system's side swipe both end in a [ZoomCard],
// so the two gestures and the flight that follows them are one geometry, not three.

/** How far a pull travels to reach progress 1, as a share of the page's height. */
const val ZOOM_BACK_PULL_EXTENT = 0.45f

/** A release whose projected progress is past this goes back; short of it the page springs home. */
const val ZOOM_BACK_COMMIT = 0.3f

/** |dy| > 0.8 |dx| is a pull: a horizontal drag has to cover 1.25 times the vertical one. */
const val ZOOM_BACK_HORIZONTAL_BIAS = 1.25f

/** How much a full pull shrinks the page about the finger. */
internal const val ZOOM_BACK_PULL_SHRINK = 0.3f

/** How much a full side swipe shrinks the page about its centre — the platform's lighter touch. */
internal const val ZOOM_BACK_SIDE_SHRINK = 0.12f

/** How much of the finger's vertical travel a side swipe carries into the page. */
internal const val ZOOM_BACK_SIDE_FOLLOW = 0.12f

/** Where the page underneath starts from, and how dark it is while the page above is still whole. */
internal const val ZOOM_BACK_UNDERLAY_SCALE = 0.95f
internal const val ZOOM_BACK_UNDERLAY_DIM = 0.5f

/** The page's own content is gone by this share of the flight; the poster's artwork is in by then. */
internal const val ZOOM_BACK_CONTENT_FADE = 0.45f

/** How much further an ordinary back shrinks the card while it fades, when there is no poster to go to. */
internal const val ZOOM_BACK_FALLBACK_SHRINK = 0.1f

/** At least this much of the poster must be on screen for the page to fly into it. */
internal const val ZOOM_BACK_MIN_VISIBLE = 0.5f

/** A flick is credited, but never as more than this many flights a second. */
internal const val ZOOM_BACK_MAX_FLIGHT_VELOCITY = 12f

/** The card's corner radius at progress 1. */
val ZoomBackCorner = 34.dp

/** How far a full side swipe moves the page towards the finger. */
val ZoomBackSideShift = 14.dp

/** How far a finger travels before a drag's direction is decided. */
val ZoomBackSlop = 8.dp

/**
 * The page as a card: where it is, how big its content is drawn, how round it is and how much of
 * the page and of the poster shows. [bounds] is in the page's own coordinates; the content is
 * scaled uniformly by [contentScale] and centred on [bounds], and whatever falls outside is clipped.
 */
@Immutable
data class ZoomCard(
    val bounds: Rect,
    val contentScale: Float,
    val cornerRadius: Float,
    val contentAlpha: Float = 1f,
    val artAlpha: Float = 0f,
) {
    /** Where a layer scaled by [contentScale] about its top-left has to sit to centre the content on [bounds]. */
    fun translation(page: Size): Offset =
        Offset(
            bounds.center.x - contentScale * page.width / 2f,
            bounds.center.y - contentScale * page.height / 2f,
        )

    /** [bounds] in the unscaled coordinates of that layer: the part of the page the card shows. */
    fun localBounds(page: Size): Rect {
        val scale = contentScale.takeIf { it > 0f } ?: return Rect.Zero
        val moved = translation(page)
        return Rect(
            (bounds.left - moved.x) / scale,
            (bounds.top - moved.y) / scale,
            (bounds.right - moved.x) / scale,
            (bounds.bottom - moved.y) / scale,
        )
    }

    companion object {
        /** The page at rest, [page] sized, square-cornered, fully itself. */
        fun resting(page: Size): ZoomCard = ZoomCard(Rect(Offset.Zero, page), 1f, 0f)
    }
}

/** p = dy ÷ (0.45 × page height); a finger above where the pull began counts as no pull at all. */
fun zoomBackPullProgress(
    dy: Float,
    pageHeight: Float,
): Float = if (pageHeight > 0f && dy > 0f) dy / (ZOOM_BACK_PULL_EXTENT * pageHeight) else 0f

/**
 * The page under a pull: scaled by 1 − 0.3p about [pivot], where the finger went down, and moved by
 * [drag], so the point that was under the finger stays under it. Never pushed above its own top.
 */
fun zoomBackPullCard(
    page: Size,
    pivot: Offset,
    drag: Offset,
    progress: Float,
    cornerAtFullPx: Float,
): ZoomCard {
    val p = progress.coerceIn(0f, 1f)
    val scale = 1f - ZOOM_BACK_PULL_SHRINK * p
    val left = pivot.x * (1f - scale) + drag.x
    val top = pivot.y * (1f - scale) + max(drag.y, 0f)
    return ZoomCard(
        bounds = Rect(left, top, left + page.width * scale, top + page.height * scale),
        contentScale = scale,
        cornerRadius = cornerAtFullPx * p,
    )
}

/**
 * The page under the system's side swipe: scaled by 1 − 0.12p about its centre, moved [shiftAtFullPx] × p
 * [toward] the finger (+1 for a swipe from the left edge, −1 from the right) and following 12% of
 * the finger's vertical travel since the swipe began, [lift].
 */
fun zoomBackSideCard(
    page: Size,
    progress: Float,
    toward: Float,
    lift: Float,
    shiftAtFullPx: Float,
    cornerAtFullPx: Float,
): ZoomCard {
    val p = progress.coerceIn(0f, 1f)
    val scale = 1f - ZOOM_BACK_SIDE_SHRINK * p
    val width = page.width * scale
    val height = page.height * scale
    val left = (page.width - width) / 2f + toward * shiftAtFullPx * p
    val top = (page.height - height) / 2f + ZOOM_BACK_SIDE_FOLLOW * lift
    return ZoomCard(
        bounds = Rect(left, top, left + width, top + height),
        contentScale = scale,
        cornerRadius = cornerAtFullPx * p,
    )
}

/** The page underneath: 0.95 while the page above is whole, 1 once it has gone. */
fun zoomBackUnderlayScale(progress: Float): Float =
    ZOOM_BACK_UNDERLAY_SCALE + (1f - ZOOM_BACK_UNDERLAY_SCALE) * progress.coerceIn(0f, 1f)

/** The shade over the page underneath: 50% while the page above is whole, none once it has gone. */
fun zoomBackUnderlayDim(progress: Float): Float = ZOOM_BACK_UNDERLAY_DIM * (1f - progress.coerceIn(0f, 1f))

/**
 * Whether letting go of a pull sends the page back: [offset] and [velocity] along the pull, projected
 * 170 ms ahead, against [extent] — see [DragProgress.commits].
 */
fun zoomBackCommits(
    offset: Float,
    velocity: Float,
    extent: Float,
): Boolean = DragProgress(offset = offset, velocity = velocity, extent = extent).commits(ZOOM_BACK_COMMIT)

/** The content scale at which the page covers [target] completely, keeping its own proportions. */
fun zoomBackFillScale(
    target: Rect,
    page: Size,
): Float =
    if (page.width <= 0f || page.height <= 0f) {
        1f
    } else {
        max(target.width / page.width, target.height / page.height)
    }

/**
 * The card on its way from [from] to [to], [fraction] of the way (a spring may overshoot 1 a little).
 * [landing] is a flight into a poster: the page's content is gone by 45% and the poster's artwork is
 * in by then. Otherwise it is the card springing back to where it started, content and all.
 */
fun zoomBackFlightCard(
    from: ZoomCard,
    to: Rect,
    page: Size,
    fraction: Float,
    toCornerPx: Float,
    landing: Boolean,
): ZoomCard {
    val f = fraction
    val fade = (f / ZOOM_BACK_CONTENT_FADE).coerceIn(0f, 1f)
    return ZoomCard(
        bounds =
            Rect(
                lerpFloat(from.bounds.left, to.left, f),
                lerpFloat(from.bounds.top, to.top, f),
                lerpFloat(from.bounds.right, to.right, f),
                lerpFloat(from.bounds.bottom, to.bottom, f),
            ),
        contentScale = lerpFloat(from.contentScale, zoomBackFillScale(to, page), f).coerceAtLeast(0.01f),
        cornerRadius = lerpFloat(from.cornerRadius, toCornerPx, f).coerceAtLeast(0f),
        contentAlpha = if (landing) from.contentAlpha * (1f - fade) else from.contentAlpha,
        artAlpha = if (landing) fade else 0f,
    )
}

/**
 * An ordinary back from wherever the card was let go: it fades out over [fraction], shrinking a
 * little further about its own centre unless [shrink] is off (减弱动态效果 keeps only the fade).
 */
fun zoomBackFadeCard(
    from: ZoomCard,
    fraction: Float,
    shrink: Boolean,
): ZoomCard {
    val f = fraction.coerceIn(0f, 1f)
    val factor = if (shrink) 1f - ZOOM_BACK_FALLBACK_SHRINK * f else 1f
    val center = from.bounds.center
    val halfWidth = from.bounds.width * factor / 2f
    val halfHeight = from.bounds.height * factor / 2f
    return from.copy(
        bounds = Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight),
        contentScale = from.contentScale * factor,
        cornerRadius = from.cornerRadius * factor,
        contentAlpha = from.contentAlpha * (1f - f),
        artAlpha = 0f,
    )
}

/**
 * The flight's starting speed, in flights per second: the release velocity along the way from [from]
 * to [to], divided by that distance. A flick towards the poster gets there sooner; one away from it
 * carries on a moment before turning. Nothing to fly across means no speed at all.
 */
fun zoomBackFlightVelocity(
    velocity: Offset,
    from: Rect,
    to: Rect,
): Float {
    val way = to.center - from.center
    val squared = way.x * way.x + way.y * way.y
    if (squared < 1f || !velocity.x.isFinite() || !velocity.y.isFinite()) return 0f
    val along = (velocity.x * way.x + velocity.y * way.y) / squared
    return along.coerceIn(-ZOOM_BACK_MAX_FLIGHT_VELOCITY, ZOOM_BACK_MAX_FLIGHT_VELOCITY)
}

/**
 * Whether a poster laid out at [full] is on screen enough to fly into: at least half of it inside
 * both [shown] (what its scrolling parents let through) and [page].
 */
fun zoomBackTargetVisible(
    full: Rect,
    shown: Rect,
    page: Rect,
): Boolean {
    val area = full.width * full.height
    if (full.width <= 0f || full.height <= 0f || !area.isFinite()) return false
    val visible = intersectionArea(intersection(shown, page), full)
    return visible >= ZOOM_BACK_MIN_VISIBLE * area
}

private fun intersection(
    a: Rect,
    b: Rect,
): Rect = Rect(max(a.left, b.left), max(a.top, b.top), min(a.right, b.right), min(a.bottom, b.bottom))

private fun intersectionArea(
    a: Rect,
    b: Rect,
): Float {
    val overlap = intersection(a, b)
    return if (overlap.width <= 0f || overlap.height <= 0f) 0f else overlap.width * overlap.height
}

private fun lerpFloat(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction
