package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
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
 * Legibility for hero copy that no longer sits on a scrim.
 *
 * A carousel that ends in the page's own colour cannot also be darkened at the bottom: the
 * two meet as a band of grey, with the picture ghosting through it, and no curve makes that
 * look deliberate. The dark band is gone — so the white title, which used to lean on it,
 * carries its own shadow instead. It costs nothing where the artwork is already dark and
 * saves the one case that used to be unreadable: a bright sky behind a white headline.
 */
val HeroTextShadow: Shadow =
    Shadow(
        color = Color(0xFF05070D).copy(alpha = 0.58f),
        offset = Offset(0f, 1.5f),
        blurRadius = 12f,
    )

private val HeroDockFill = HeroInk.copy(alpha = 0.58f)
private val HeroDockBorder = Color.White.copy(alpha = 0.22f)
private val HeroPlayFill = Color.White.copy(alpha = 0.92f)
private val HeroPlayInk = Color(0xFF111824)
private val HeroToolSelectedFill = Color.White.copy(alpha = 0.12f)

/**
 * Unified action dock shared by the 首页 and 媒体库 reels.
 *
 * One artwork-safe glass surface keeps the three related actions together. Play owns the
 * light key and a small brand-colour icon well; favorite and details stay quiet until used.
 * The dock follows the selected 毛玻璃/液态玻璃 material and its reduced-transparency fallback.
 */
@Composable
fun HeroActionDock(
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean? = null,
    playActionLabel: String = "播放影片",
    detailsActionLabel: String = "查看详情",
    favoriteActionLabel: String = if (favorite == true) "取消收藏" else "加入收藏",
) {
    val playWell = LocalAccentColors.current.accent.copy(alpha = 0.96f)
    Row(
        modifier
            .height(52.dp)
            .shadow(GlassLift.control, AppShapes.control)
            .liquidGlass(
                shape = AppShapes.control,
                fill = HeroDockFill,
                border = HeroDockBorder,
                over = HeroInk,
                sheen = 0.72f,
            ).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(44.dp)
                .pressable(onClickLabel = playActionLabel, onClick = onPlay)
                .background(HeroPlayFill, AppShapes.thumb)
                .padding(start = 7.dp, end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).background(playWell, AppShapes.thumb),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text("播放", style = AppTypography.body.strong, color = HeroPlayInk, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.width(Dimens.hairline).height(24.dp).background(Color.White.copy(alpha = 0.16f)))
        Spacer(Modifier.width(4.dp))
        HeroDockTool(
            icon = if (favorite == true) AppIcons.HeartFilled else AppIcons.Heart,
            description = favoriteActionLabel,
            onClick = onFavorite,
            active = favorite,
        )
        HeroDockTool(
            icon = AppIcons.Info,
            description = detailsActionLabel,
            onClick = onDetails,
        )
    }
}

@Composable
private fun HeroDockTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean? = null,
) {
    Box(
        Modifier
            .size(44.dp)
            .pressable(
                haptic = HapticSignal.Confirm.takeIf { active != null },
                role = if (active == null) Role.Button else Role.Checkbox,
                onClickLabel = description,
                onClick = onClick,
            ).then(
                if (active == null) {
                    Modifier
                } else {
                    Modifier.semantics { toggleableState = ToggleableState(active) }
                },
            ).background(
                color = if (active == true) HeroToolSelectedFill else Color.Transparent,
                shape = AppShapes.thumb,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active == null) {
            Icon(icon, description, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(18.dp))
        } else {
            BurstIcon(
                icon = icon,
                active = active,
                contentDescription = description,
                tint = Color.White.copy(alpha = 0.88f),
                burstColor = Color.White,
                iconSize = 18.dp,
            )
        }
    }
}

/**
 * Scrim for a hero whose lower edge dissolves into the page — dark at the top, where the
 * status bar and the floating header need it, and nothing at all below the midpoint.
 *
 * [topInk] is how heavy the top gets; the rest of the shape is fixed, because it is what
 * keeps the darkness clear of the dissolve.
 */
fun heroTopScrim(
    topInk: Float = 0.45f,
    midInk: Float = 0.10f,
): Brush =
    scrim(
        0f to Color.Transparent,
        0.52f to Color.Transparent,
        0.80f to HeroInk.copy(alpha = midInk),
        1f to HeroInk.copy(alpha = topInk),
    )

/**
 * Page colour under the artwork — the poster's own colour, washed into the page.
 *
 * The light theme used to be flat white, which made the one screen that is *about* a
 * single piece of artwork the one screen that took no colour from it: the hero faded into
 * a slab that could have belonged to any title. Both themes now carry the harmonized
 * artwork accent (see [harmonizeArtworkAccent], which has already pulled it into a
 * restrained luminance band before it reaches here).
 *
 * The guards are the point. 次文字 and 提示文字 were measured against a page of a
 * particular brightness, and a wash is allowed to colour that page, not to darken it out
 * from under its own text — so a light surface is lifted back towards white until it is
 * as bright as the standard page, and a dark one is pushed back towards the dark page
 * until it is as deep.
 */
fun heroSurface(
    accent: Color,
    isDark: Boolean,
): Color =
    if (isDark) {
        accent
            .copy(alpha = 0.34f)
            .compositeOver(Color(0xFF0B111C))
            .darkenedTo(DARK_HERO_SURFACE_LUMINANCE, Color(0xFF0B111C))
            .chromaBoosted(DARK_HERO_SURFACE_CHROMA)
    } else {
        accent
            .copy(alpha = 0.34f)
            .compositeOver(Color.White)
            .lightenedTo(LIGHT_HERO_SURFACE_LUMINANCE, Color.White)
            .chromaBoosted(LIGHT_HERO_SURFACE_CHROMA)
    }

/** As bright as [LightPalette]'s own `background`, where its greys were measured. */
private const val LIGHT_HERO_SURFACE_LUMINANCE = 0.87f

/** No lighter than the tint the dark detail page already carried. */
private const val DARK_HERO_SURFACE_LUMINANCE = 0.032f

/**
 * How much of the artwork's colour survives the brightness guards.
 *
 * Lifting a colour towards white is also draining it: guard first, then push the chroma
 * back out from the grey of the same brightness, and the page reads as *this poster's*
 * colour rather than as a hint of one. Chroma is symmetric about the channel mean, so it
 * moves the hue back into view without moving the brightness the guards just fixed.
 */
private const val LIGHT_HERO_SURFACE_CHROMA = 2.6f
private const val DARK_HERO_SURFACE_CHROMA = 2.2f

private fun Color.lightenedTo(
    minimum: Float,
    towards: Color,
): Color {
    var result = this
    repeat(10) {
        if (result.luminance() >= minimum) return result
        result = lerp(result, towards, 0.14f)
    }
    return result
}

private fun Color.darkenedTo(
    maximum: Float,
    towards: Color,
): Color {
    var result = this
    repeat(10) {
        if (result.luminance() <= maximum) return result
        result = lerp(result, towards, 0.14f)
    }
    return result
}

private fun Color.chromaBoosted(factor: Float): Color {
    val mean = (red + green + blue) / 3f
    return Color(
        red = (mean + (red - mean) * factor).coerceIn(0f, 1f),
        green = (mean + (green - mean) * factor).coerceIn(0f, 1f),
        blue = (mean + (blue - mean) * factor).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

/**
 * The page's ground, washed toward the artwork the hero is currently showing.
 *
 * A hero used to dissolve into `palette.background` — a fixed grey — so the colour stopped
 * dead at the bottom of the carousel and everything below it belonged to a different picture.
 * Tinting the whole page instead makes the artwork the room the content sits in, and because
 * the accent handed in is already animated, the room changes with the slide.
 *
 * Deliberately a small fraction. This colour ends up behind body copy, chips and cards whose
 * inks were measured against the flat palette; far enough toward the artwork to be felt, not
 * far enough to start deciding contrast.
 */
@Composable
@ReadOnlyComposable
fun pageTint(accent: Color): Color {
    val palette = LocalPalette.current
    return lerp(palette.background, accent, if (palette.isDark) 0.16f else 0.11f)
}

/**
 * `0deg {page} 3%, {page}55% 22%, rgba(18,22,32,.12) 62%, rgba(18,22,32,.42)`
 * (「影视详情页 优化」).
 *
 * The 22% stop is the one that matters. Running the page colour straight into the dark
 * stop — which is what the three-stop version did — keeps the wash above 50% opaque all
 * the way to mid-hero, so the artwork is only ever visible in its top third. Reaching
 * 55% by 22% confines the blend to the strip the information sheet actually sits over.
 */
fun heroScrim(
    surface: Color,
    bottomSurface: Color = surface,
): Brush =
    scrim(
        0.03f to bottomSurface,
        0.22f to surface.copy(alpha = 0.55f),
        0.62f to HeroInk.copy(alpha = 0.12f),
        1f to HeroInk.copy(alpha = 0.42f),
    )

/**
 * How a reel ends — 首页 and 媒体库's carousels, which dissolve into the page rather than
 * hand over to a sheet.
 *
 * They used to borrow [heroScrim] from 影视详情页, and the two pages want opposite things
 * from it. The detail hero has an information sheet lifted over its lower edge, so its
 * wash is deliberately heavy and reaches 55% by 22% of the height — there is a card about
 * to cover that strip. A carousel has nothing over it, so the same wash read as fog laid
 * on the picture from mid-height down. Worse, it faded through *two* colours: the artwork's
 * lightened [heroSurface] in the middle of the ramp and the page's own [pageTint] at the
 * very bottom, which put a grey seam between the picture and the page it was supposedly
 * melting into.
 *
 * One colour and one ramp, therefore. [page] is the ground the carousel is sitting on, so
 * the last band is that colour exactly and the join is invisible by construction rather
 * than by matching. The picture stays untouched for its top two thirds, thins out over the
 * bottom third, and is fully page by the time the pagination dots sit in it.
 */
fun heroReelScrim(page: Color): Brush =
    scrim(
        0f to page,
        // A solid hem. The dots live here, in page ink on page colour.
        0.06f to page,
        0.14f to page.copy(alpha = 0.82f),
        0.22f to page.copy(alpha = 0.42f),
        0.30f to page.copy(alpha = 0.12f),
        // Clear of the artwork well before the caption's top line, so white copy is never
        // asked to sit on a pale wash.
        0.40f to Color.Transparent,
        0.68f to HeroInk.copy(alpha = 0.10f),
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
): Brush =
    Brush.verticalGradient(
        colorStops =
            arrayOf(
                0f to Color.Transparent,
                0.30f to surface.copy(alpha = 0.42f),
                0.66f to surface.copy(alpha = 0.90f),
                1f to surface,
            ),
        startY = with(density) { start.toPx() },
        endY = with(density) { (start + height).toPx() },
    )

/**
 * How much of a carousel's lower edge is spent dissolving into the page.
 *
 * Shared by 首页 and 库 so the two reels end the same way, and so each screen can hold its
 * caption and its dots clear of the same band.
 *
 * It was 76dp while the artwork still had a dark scrim under it, where a long dissolve
 * would only have meant a longer grey band. With the scrim gone the constraint is the
 * opposite one: a short melt on a 390dp hero reads as a smudge along the bottom edge
 * rather than as the picture settling into the page, so the band is now most of the space
 * below the caption.
 */
val HeroPageFade: Dp = 124.dp

/**
 * Where hero copy has to stop.
 *
 * Slightly *inside* the band rather than clear of it: coverage at the band's top edge is
 * a few percent, which is nothing to read through, and insisting on the full clearance
 * costs a caption of vertical room it does not have on a short phone.
 */
val HeroCaptionClearance: Dp = HeroPageFade - 12.dp

/**
 * Dissolves the bottom [height] of this element into whatever is drawn behind it.
 *
 * Painting the page colour over the artwork would have been the obvious way to end a
 * carousel, and it is the one that cannot work: the page is not a colour but an ambient
 * gradient, so a band of [Palette.background] meets it at a seam that moves with the
 * scroll. Removing the artwork's own alpha instead lets the real page through, which is
 * the same colour as the page by construction rather than by matching.
 *
 * Apply it to the artwork layer only. Anything that must stay legible — a caption, the
 * pagination — belongs outside this node, over the band rather than inside it.
 */
fun Modifier.fadeIntoPage(height: Dp = HeroPageFade): Modifier =
    this
        // The mask is a subtractive blend, and it can only subtract from pixels that are in a
        // layer of their own. Without this it would punch through the whole page beneath.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = height.toPx().coerceAtMost(size.height)
            if (fade <= 0f) return@drawWithContent
            val top = size.height - fade
            drawRect(
                brush =
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to Color.Transparent,
                                // An S-curve rather than a ramp: slow to start, so the picture stays
                                // itself for most of the band, and fully resolved before the last
                                // pixel, so there is never a faint edge where the page begins.
                                0.34f to Color.Black.copy(alpha = 0.16f),
                                0.68f to Color.Black.copy(alpha = 0.68f),
                                0.92f to Color.Black.copy(alpha = 0.98f),
                                1f to Color.Black,
                            ),
                        startY = top,
                        endY = size.height,
                    ),
                topLeft = Offset(0f, top),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstOut,
            )
        }

/**
 * Pulls content up over the lower edge of the hero by [lift].
 *
 * `offset` cannot do this job inside a lazy list: it moves the drawing but leaves the
 * measured height behind, so the lift reappears as dead page hanging off the end of the
 * list. This shrinks the slot instead.
 */
fun Modifier.liftOverHero(lift: Dp): Modifier =
    layout { measurable, constraints ->
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
