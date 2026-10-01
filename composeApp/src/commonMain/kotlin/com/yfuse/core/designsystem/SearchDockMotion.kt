package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where the dock's 搜索 key is, and the one morph a tap on it grants the search field it opens.
 *
 * Only numeric bounds are retained, never a view, context, image, or layout coordinates.
 */
internal class SearchMorphOrigin(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var bounds: Rect? = null
    private var pending: Rect? = null
    private var grantedAt: TimeMark? = null

    fun begin() {
        pending = bounds
        grantedAt = timeSource.markNow()
    }

    /**
     * The tap's origin, once, and only for [SEARCH_MORPH_GRANT] after it. A tap that landed on a
     * page with no field — a detail left open on 搜索's stack — used to leave its origin waiting,
     * and whichever field appeared next, however much later and wherever, flew in from the dock.
     */
    fun consume(): Rect? {
        val origin = pending
        val fresh = grantedAt?.let { it.elapsedNow() <= SEARCH_MORPH_GRANT } == true
        pending = null
        grantedAt = null
        return origin?.takeIf { fresh }
    }
}

/** The dock's: written by [searchDockSource], granted by a tap on 搜索, taken by [searchFieldArrival]. */
internal val SearchDockOrigin = SearchMorphOrigin()

/** Long enough for the search page to compose after the tap; nothing later is that tap's arrival. */
private val SEARCH_MORPH_GRANT = 1.seconds

internal fun Modifier.searchDockSource(): Modifier =
    onGloballyPositioned { SearchDockOrigin.bounds = it.boundsInWindow() }

/** A navigation click grants one morph. Query edits, pages and returning to the route do not. */
@Composable
internal fun Modifier.searchFieldArrival(): Modifier {
    val origin = remember { SearchDockOrigin.consume() }
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion && !calmMotion()
    var target by remember { mutableStateOf<Rect?>(null) }
    val progress = remember { Animatable(if (origin == null || !moving) 1f else 0f) }
    LaunchedEffect(target, moving) {
        if (!moving) {
            progress.snapTo(1f)
        } else if (target != null
        ) {
            // The page's own arrival length (Motion.TAB): at MODAL the field was still moving
            // 100ms after the search route around it had settled.
            progress.animateTo(
                1f,
                tween(Motion.TAB, easing = Motion.Curve),
            )
        }
    }
    return onGloballyPositioned {
        // Capture the untransformed bounds once. Later graphics-layer positions are not targets.
        if (target == null) target = it.boundsInWindow()
    }.graphicsLayer {
        val destination = target
        if (moving && origin != null && destination != null && destination.width > 0f && destination.height > 0f) {
            val p = progress.value
            val remaining = 1f - p
            translationX = (origin.center.x - destination.center.x) * remaining
            translationY = (origin.center.y - destination.center.y) * remaining
            scaleX = 1f + (origin.width / destination.width - 1f) * remaining
            scaleY = 1f + (origin.height / destination.height - 1f) * remaining
            alpha = 0.35f + 0.65f * p
        }
    }
}
