package com.yfuse.core.designsystem

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The artwork-over-page hero shared by 影视详情页 and the TMDB info page.
 *
 * Both pages are the same layout — full-bleed backdrop, a wash blending it into the
 * page, and an information sheet lifted over its lower edge — and both were written out
 * by hand. They drifted: the detail page's wash was corrected to the design's four
 * stops while the TMDB page kept the old three, so the same screen swallowed most of its
 * artwork in one place and not the other. These helpers are the one copy.
 */

/** `rgba(18,22,32,…)` — the ink the wash darkens towards under the status bar. */
val HeroInk = Color(0xFF121620)

/**
 * Page colour under the artwork. The light theme is flat white; the dark theme carries a
 * touch of the artwork's own colour so the page does not read as a separate slab.
 */
fun heroSurface(accent: Color, isDark: Boolean): Color =
    if (isDark) accent.copy(alpha = 0.10f).compositeOver(Color(0xFF0B111C)) else Color.White

/**
 * `0deg {page} 3%, {page}55% 22%, rgba(18,22,32,.12) 62%, rgba(18,22,32,.42)`
 * (「影视详情页 优化」).
 *
 * The 22% stop is the one that matters. Running the page colour straight into the dark
 * stop — which is what the three-stop version did — keeps the wash above 50% opaque all
 * the way to mid-hero, so the artwork is only ever visible in its top third. Reaching
 * 55% by 22% confines the blend to the strip the information sheet actually sits over.
 */
fun heroScrim(surface: Color): Brush = scrim(
    0.03f to surface,
    0.22f to surface.copy(alpha = 0.55f),
    0.62f to HeroInk.copy(alpha = 0.12f),
    1f to HeroInk.copy(alpha = 0.42f),
)

/**
 * Blend band drawn behind the lifted sheet, over a fixed [height] of page.
 *
 * [start] holds the band off for that much first, leaving the sheet's top transparent. A
 * sheet lifted far enough that its own copy sits on the artwork needs that: the band ramps
 * towards the page colour, so text over the ramp has to be page ink, and text over the
 * artwork has to be artwork ink. Text that spans the ramp cannot be either. Starting the
 * band where the artwork ends keeps each piece of copy on one side of that line.
 */
fun heroPanelBrush(
    surface: Color,
    density: Density,
    height: Dp = 170.dp,
    start: Dp = 0.dp,
): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.30f to surface.copy(alpha = 0.42f),
        0.66f to surface.copy(alpha = 0.90f),
        1f to surface,
    ),
    startY = with(density) { start.toPx() },
    endY = with(density) { (start + height).toPx() },
)

/**
 * Pulls content up over the lower edge of the hero by [lift].
 *
 * `offset` cannot do this job inside a lazy list: it moves the drawing but leaves the
 * measured height behind, so the lift reappears as dead page hanging off the end of the
 * list. This shrinks the slot instead.
 */
fun Modifier.liftOverHero(lift: Dp): Modifier = layout { measurable, constraints ->
    val liftPx = lift.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - liftPx).coerceAtLeast(0)) {
        placeable.place(0, -liftPx)
    }
}

/**
 * True once the page — rather than the artwork — owns the top edge, which is what
 * decides whether the status bar needs dark icons.
 *
 * [heroHeight] must be the hero's real height. Passing a literal that happens to match
 * is how the media library ended up flipping its status bar at the wrong scroll offset
 * after its hero was resized.
 */
@Composable
fun rememberScrolledPastHero(
    listState: LazyListState,
    heroHeight: Dp,
    switchInset: Dp = 56.dp,
): State<Boolean> {
    val density = LocalDensity.current
    return remember(listState, heroHeight, switchInset, density) {
        val switchOffset = with(density) { (heroHeight - switchInset).roundToPx() }
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset >= switchOffset
        }
    }
}
