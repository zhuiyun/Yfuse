package com.yfuse.core.designsystem

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The hero reel's own clock: one page forward every [dwellMillis] while nothing holds it.
 *
 * 首页 and 库 each used to run a private copy of this loop. It stops for 减弱动态效果, for a
 * hidden route and — which neither copy did — for a screen reader: TalkBack's touch
 * exploration sends no press, so the reel turned the page out from under the spoken cursor
 * every six seconds (WCAG 2.2.2). [held] carries the caller's own reasons: a finger on the
 * reel, a drag, a menu over it, the hero scrolled away, focus inside it.
 *
 * [restartKey] restarts the dwell, so a page the person just chose gets its full time.
 */
@Composable
fun CarouselAutoAdvance(
    pagerState: PagerState,
    pageCount: Int,
    held: Boolean,
    restartKey: Any? = null,
    dwellMillis: Int = Motion.CAROUSEL_DWELL,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
    val screenReader = rememberScreenReaderActive()
    LaunchedEffect(pagerState, pageCount, held, restartKey, reduceMotion, routeVisible, screenReader) {
        if (held || !routeVisible || reduceMotion || screenReader || pageCount <= 1) return@LaunchedEffect
        advanceCarousel(
            dwellMillis = dwellMillis.toLong(),
            scrolling = { pagerState.isScrollInProgress },
        ) {
            pagerState.animateScrollToPage(
                page = pagerState.currentPage + 1,
                animationSpec = Motion.tween(Motion.CAROUSEL),
            )
        }
    }
}

/**
 * The clock itself: [advance] every [dwellMillis], skipped while something else is [scrolling].
 *
 * Any other scroll of the pager cancels the turn under way — a finger, or the reel re-seated on a
 * list that changed under it. That costs one turn, not the clock: none of this effect's keys
 * change then, so a loop that let the interruption through stayed stopped until the reel was
 * touched or shown again. Only the cancellation of the clock's own coroutine ends it.
 */
internal suspend fun advanceCarousel(
    dwellMillis: Long,
    scrolling: () -> Boolean,
    advance: suspend () -> Unit,
) {
    while (true) {
        delay(dwellMillis)
        if (scrolling()) continue
        try {
            advance()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }
}
