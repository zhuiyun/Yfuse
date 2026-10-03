package com.yfuse.core.designsystem

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the screen around the reel shares with it: the looping pager, whose settled page picks the
 * artwork and colour the rest of the page follows, and the scope a chosen dot scrolls in. The
 * screen keeps it, so the reel can come and go (scrolled out of a list) without losing its place.
 */
@Stable
class LivingPosterHeroState internal constructor(
    val pagerState: PagerState,
    internal val scope: CoroutineScope,
) {
    /** A finger on the reel: its clock waits for the finger to lift. */
    internal val touched = mutableStateOf(false)

    /**
     * Touching the reel restarts its clock rather than stopping it for good. The pause control
     * this replaces could only be undone by finding it again, so a single swipe left the hero
     * permanently still with a play glyph as the only clue why. A chosen dot bumps this, so the
     * page chosen gets its full dwell.
     */
    internal var interaction by mutableIntStateOf(0)

    /** Which of [itemCount] items the reel has settled on. */
    fun settledIndex(itemCount: Int): Int = loopingCarouselItemIndex(pagerState.settledPage, itemCount)
}

@Composable
fun rememberLivingPosterHeroState(itemIds: List<String>): LivingPosterHeroState {
    val pagerState = rememberLoopingCarouselState(itemIds)
    val scope = rememberCoroutineScope()
    return remember(pagerState, scope) { LivingPosterHeroState(pagerState, scope) }
}

/** One page of the reel, as the screen's slide draws it. */
class LivingPosterPage internal constructor(
    /**
     * The pager's own page. No two pages composed at once share it, where [index] repeats: a reel
     * of two shows the same item on both sides of the one it rests on.
     */
    val page: Int,
    /** Which of the screen's items the page shows. */
    val index: Int,
    /** Whether the reel has come to rest on this page. */
    val settled: Boolean,
    /** How far the page sits from the current one, signed, while the reel turns. */
    val offset: () -> Float,
    /** Fills the page and carries the reel's turn — scale, fade, the preview edge — for the slide to wear. */
    val modifier: Modifier,
)

/** How a slide crops and dissolves its artwork in a reel [width] by [height]. */
@Immutable
data class LivingPosterArtwork(
    val aspectRatio: Float,
    val fadeFraction: Float,
)

/** The artwork the reel's slides draw: the inset poster's when [showSidePreview], edge to edge otherwise. */
fun livingPosterArtwork(
    width: Dp,
    height: Dp,
    showSidePreview: Boolean,
): LivingPosterArtwork {
    val artworkWidth =
        if (showSidePreview) {
            (width - LivingPosterDefaults.LEADING_INSET - LivingPosterDefaults.TRAILING_PEEK)
                .coerceAtLeast(1.dp)
        } else {
            width
        }
    return LivingPosterArtwork(
        aspectRatio = artworkWidth.value / height.value.coerceAtLeast(1f),
        fadeFraction = (HeroPageFade.value / height.value.coerceAtLeast(1f)).coerceIn(0.02f, 1f),
    )
}

/**
 * The living-poster reel that 首页 and 媒体库 both open on: a looping pager of posters that turns on
 * its own, the dots under it, and the light it throws when it is dragged or comes to rest.
 *
 * The two screens used to assemble it each from the same parts. Where they still differ, it is a
 * parameter: what else holds the clock ([held]) — a finger, a drag and a 浮起菜单 always do; whether
 * the light waits for the reel to be on screen ([lit]); how many pages stay composed out of view
 * ([beyondViewportPageCount]); what stands in for an empty reel ([empty]); and what floats over
 * the pages ([overlay]). The screen draws each page itself with [slide], wearing the page's
 * [LivingPosterPage.modifier].
 */
@Composable
fun LivingPosterHeroCarousel(
    state: LivingPosterHeroState,
    pageCount: Int,
    showSidePreview: Boolean,
    held: Boolean,
    /** The settled artwork, blurred behind the inset poster when [showSidePreview]. */
    ambientUrls: List<String?>,
    modifier: Modifier = Modifier,
    lit: Boolean = true,
    beyondViewportPageCount: Int = 1,
    empty: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    slide: @Composable (LivingPosterPage) -> Unit,
) {
    val pagerState = state.pagerState
    val dragging by pagerState.interactionSource.collectIsDraggedAsState()
    // `enabled` gates whether [rememberLightFeedback] even builds its state (see its own
    // `available` check), not just whether it may emit — so passing the carousel's visibility
    // there rebuilt the state from scratch on every scroll start/stop. It stays alive
    // permanently and [lit] only gates the `.emit(...)` calls below, the same way
    // [rememberLightFeedback] itself already treats route visibility as a post-build gate.
    val light = rememberLightFeedback(enhancedOnly = true)
    LaunchedEffect(dragging, light, lit) {
        if (dragging && lit) light.emit(LightEffect.Dust)
    }
    // Every level gets the settle: a page that has just left sweeps a gathering light along
    // the side it left from. Dust on the edges stays an 增强 detail.
    val sweep = rememberLightFeedback()
    LaunchedEffect(pagerState, sweep, lit) {
        var previous = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            // Tracking keeps running while unlit so a page change during that time is not
            // mistaken for one later, once lit again; only the sweep itself is gated.
            if (page != previous && lit) {
                sweep.emit(LightEffect.Converge, fractionX = if (page > previous) 0.04f else 0.96f)
            }
            previous = page
        }
    }
    LivingPosterHeroClock(state, pageCount, held = held || dragging || state.touched.value)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    Box(
        modifier
            .carouselTouchPause(state.touched)
            .lightFeedback(light)
            .lightFeedback(sweep),
    ) {
        // Full-bleed phone artwork must dissolve straight into the real page. Drawing a
        // second, blurred copy behind it made that copy show through the fade as a saturated
        // horizontal band and also decoded the first image twice. Wide layouts still need
        // the ambient layer behind their inset poster, so it shares the same dissolve.
        if (showSidePreview) {
            LivingPosterAmbient(
                urls = ambientUrls,
                modifier = Modifier.fillMaxSize().fadeIntoPage(),
            )
        }
        if (pageCount == 0) {
            empty()
        } else {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .loopingCarouselSemantics(pagerState.currentPage, pageCount),
                contentPadding =
                    if (showSidePreview) {
                        PaddingValues(
                            start = LivingPosterDefaults.LEADING_INSET,
                            end = LivingPosterDefaults.TRAILING_PEEK,
                        )
                    } else {
                        PaddingValues(0.dp)
                    },
                pageSpacing = if (showSidePreview) LivingPosterDefaults.PAGE_SPACING else 0.dp,
                beyondViewportPageCount = beyondViewportPageCount,
                key = { page -> page },
            ) { page ->
                slide(
                    LivingPosterPage(
                        page = page,
                        index = loopingCarouselItemIndex(page, pageCount),
                        settled = page == pagerState.settledPage,
                        offset = { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val visual =
                                        carouselPageVisual(
                                            signedPageOffset =
                                                (pagerState.currentPage - page) +
                                                    pagerState.currentPageOffsetFraction,
                                            reduceMotion = reduceMotion,
                                            preservePreviewEdge = showSidePreview,
                                        )
                                    scaleX = visual.scale
                                    scaleY = visual.scale
                                    alpha = visual.alpha
                                    translationX = size.width * visual.parallaxFraction
                                },
                    ),
                )
            }
        }

        overlay()

        if (pageCount > 1) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = if (showSidePreview) LivingPosterDefaults.LEADING_INSET else 0.dp,
                        end = if (showSidePreview) LivingPosterDefaults.TRAILING_PEEK else 0.dp,
                        bottom = LivingPosterDefaults.INDICATOR_BOTTOM,
                    ),
            ) {
                HeroPageIndicator(
                    pageCount = pageCount,
                    selectedPage = loopingCarouselItemIndex(pagerState.currentPage, pageCount),
                    pageOffsetProvider = { pagerState.currentPageOffsetFraction },
                    onPageSelected = { targetIndex ->
                        if (targetIndex != loopingCarouselItemIndex(pagerState.currentPage, pageCount)) {
                            light.emit(LightEffect.Dust)
                        }
                        state.interaction++
                        state.scope.launch {
                            val targetPage =
                                loopingCarouselTargetPage(
                                    currentPage = pagerState.currentPage,
                                    targetIndex = targetIndex,
                                    itemCount = pageCount,
                                )
                            if (reduceMotion) {
                                pagerState.scrollToPage(targetPage)
                            } else {
                                pagerState.animateScrollToPage(
                                    page = targetPage,
                                    animationSpec = tween(Motion.EMPHASIZED, easing = Motion.Curve),
                                )
                            }
                        }
                    },
                    onArtwork = false,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * The reel's clock, held as well while a 浮起菜单 is up. The menu blurs the page it lifts off, and
 * a reel turning underneath had that blur worked out again on every frame of the turn. The lift
 * is read here, in a scope of its own, so opening one does not recompose the reel or the page
 * around it.
 */
@Composable
private fun LivingPosterHeroClock(
    state: LivingPosterHeroState,
    pageCount: Int,
    held: Boolean,
) {
    val lifted = LocalLiftMenu.current?.isOpen == true
    CarouselAutoAdvance(
        pagerState = state.pagerState,
        pageCount = pageCount,
        held = held || lifted,
        restartKey = state.interaction,
    )
}
