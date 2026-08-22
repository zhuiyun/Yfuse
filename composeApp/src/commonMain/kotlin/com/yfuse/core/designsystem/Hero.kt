package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
private val HeroPlayBorder = Color.White.copy(alpha = 0.30f)
private val HeroPlayInk = Color.White.copy(alpha = 0.94f)
private val HeroToolSelectedFill = Color.White.copy(alpha = 0.20f)

/**
 * Artwork-safe action row shared by the 首页 and 媒体库 reels.
 *
 * The play key follows the artwork accent but is pulled toward [HeroInk], so white copy keeps
 * its contrast even for a pale poster. Secondary tools remain restrained dark glass. Keeping
 * both screens on this one dock prevents the 首页 and 媒体库 hero materials from drifting.
 */
@Composable
fun HeroActionDock(
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean? = null,
    playActionLabel: String = "播放影片",
    favoriteActionLabel: String = if (favorite == true) "取消收藏" else "加入收藏",
) {
    val actionAccent = LocalAccentColors.current.accent
    val playFill = lerp(actionAccent, HeroInk, 0.28f).copy(alpha = 0.80f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(46.dp)
                .pressable(
                    focusShape = AppShapes.pill,
                    onClickLabel = playActionLabel,
                    onClick = onPlay,
                ).shadow(GlassLift.control, AppShapes.pill)
                .liquidGlass(
                    shape = AppShapes.pill,
                    fill = playFill,
                    border = HeroPlayBorder,
                    over = HeroInk,
                    sheen = 0.66f,
                ).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Play,
                contentDescription = null,
                tint = HeroPlayInk,
                modifier = Modifier.size(17.dp),
            )
            Text("继续播放", style = AppTypography.body.strong, color = HeroPlayInk, maxLines = 1)
        }
        HeroFavoriteButton(
            icon = if (favorite == true) AppIcons.HeartFilled else AppIcons.Heart,
            description = favoriteActionLabel,
            onClick = onFavorite,
            active = favorite,
        )
    }
}

@Composable
private fun HeroFavoriteButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean? = null,
) {
    val shape = AppShapes.pill
    Row(
        Modifier
            .height(46.dp)
            .pressable(
                haptic = HapticSignal.Confirm.takeIf { active != null },
                role = if (active == null) Role.Button else Role.Checkbox,
                focusShape = shape,
                onClickLabel = description,
                onClick = onClick,
            ).then(
                if (active == null) {
                    Modifier
                } else {
                    Modifier.semantics { toggleableState = ToggleableState(active) }
                },
            ).shadow(GlassLift.control, shape)
            .liquidGlass(
                shape = shape,
                fill = if (active == true) HeroToolSelectedFill else HeroDockFill,
                border = HeroDockBorder,
                over = HeroInk,
                sheen = 0.58f,
            ).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active == null) {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(17.dp))
        } else {
            BurstIcon(
                icon = icon,
                active = active,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.88f),
                burstColor = Color.White,
                iconSize = 17.dp,
            )
        }
        Text(
            text = if (active == true) "已收藏" else "收藏",
            style = AppTypography.body.strong,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
        )
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

private data class ArtworkHsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

/**
 * The page is a tone of the artwork, not a translucent dark swatch laid over white.
 *
 * Lifting RGB channels toward white drains hue and is the reason dark posters used to converge
 * on the same cement grey. Work in HSL instead: keep the poster hue, clamp chroma to a quiet but
 * visible band, and move only the surface lightness. This is deliberately the same transform for
 * the detail body and the 首页 / 媒体库 grounds, so a hero always dissolves into the exact colour
 * that continues below it.
 */
private fun artworkPageSurface(
    accent: Color,
    isDark: Boolean,
): Color {
    val source = accent.toArtworkHsl()
    val fallbackHue = Brand.Primary.toArtworkHsl().hue
    val hue = if (source.saturation >= 0.015f) source.hue else fallbackHue
    val saturation =
        if (isDark) {
            source.saturation.coerceIn(0.12f, 0.28f)
        } else {
            source.saturation.coerceIn(0.09f, 0.18f)
        }
    val lightness =
        if (isDark) {
            (0.15f + (source.lightness - 0.50f) * 0.04f).coerceIn(0.12f, 0.18f)
        } else {
            (0.86f + (source.lightness - 0.50f) * 0.04f).coerceIn(0.82f, 0.88f)
        }
    return colorFromArtworkHsl(hue, saturation, lightness)
}

private fun Color.toArtworkHsl(): ArtworkHsl {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    if (delta <= 0.0001f) return ArtworkHsl(0f, 0f, lightness)

    val saturation =
        (delta / (1f - kotlin.math.abs(2f * lightness - 1f)).coerceAtLeast(0.0001f))
            .coerceIn(0f, 1f)
    val hueSix =
        when (maximum) {
            red -> ((green - blue) / delta) % 6f
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        }
    val hue = (((hueSix / 6f) % 1f) + 1f) % 1f
    return ArtworkHsl(hue, saturation, lightness)
}

private fun colorFromArtworkHsl(
    hue: Float,
    saturation: Float,
    lightness: Float,
): Color {
    if (saturation <= 0.0001f) return Color(lightness, lightness, lightness, 1f)
    val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val h = (hue * 6f).let { value -> ((value % 6f) + 6f) % 6f }
    val x = chroma * (1f - kotlin.math.abs((h % 2f) - 1f))
    val (r1, g1, b1) =
        when {
            h < 1f -> Triple(chroma, x, 0f)
            h < 2f -> Triple(x, chroma, 0f)
            h < 3f -> Triple(0f, chroma, x)
            h < 4f -> Triple(0f, x, chroma)
            h < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
    val m = lightness - chroma / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/** Page colour under detail artwork, with hue/chroma retained through brightness protection. */
fun heroSurface(
    accent: Color,
    isDark: Boolean,
): Color = artworkPageSurface(accent, isDark)

/**
 * The page's ground follows the artwork with the same transform as [heroSurface].
 *
 * This is intentionally not a second blend with `palette.background`: two independently
 * calculated endpoint colours are enough to leave a hairline between a hero and its page.
 */
@Composable
@ReadOnlyComposable
fun pageTint(accent: Color): Color {
    val palette = LocalPalette.current
    return artworkPageSurface(accent, palette.isDark)
}

/**
 * Detail hero wash. The image remains untouched through most of its height; only the lower
 * ~one-third hands off to the exact body surface. A separate top ink cap protects system/title
 * copy, with a clear middle between the two. There is no neutral-grey intermediate layer.
 */
fun heroScrim(
    surface: Color,
    bottomSurface: Color = surface,
): Brush =
    scrim(
        0f to bottomSurface,
        0.05f to surface,
        0.13f to surface.copy(alpha = 0.88f),
        0.21f to surface.copy(alpha = 0.52f),
        0.29f to surface.copy(alpha = 0.16f),
        0.34f to Color.Transparent,
        0.72f to Color.Transparent,
        0.86f to HeroInk.copy(alpha = 0.10f),
        1f to HeroInk.copy(alpha = 0.42f),
    )

/**
 * 首页 and 媒体库 use the same one-colour dissolve as the detail page. The final pixel is
 * literally [page], so the artwork and body cannot disagree at their join. The picture stays
 * clean until the bottom third instead of carrying a long grey fog over its lower half.
 */
fun heroReelScrim(page: Color): Brush =
    scrim(
        0f to page,
        0.05f to page,
        0.13f to page.copy(alpha = 0.88f),
        0.21f to page.copy(alpha = 0.52f),
        0.29f to page.copy(alpha = 0.16f),
        0.34f to Color.Transparent,
        0.72f to Color.Transparent,
        0.86f to HeroInk.copy(alpha = 0.10f),
        1f to HeroInk.copy(alpha = 0.42f),
    )

/**
 * Blend band drawn behind the lifted detail sheet. The band begins after the hero copy and
 * reaches the exact page surface over a short distance, avoiding the former broad fog bank.
 */
fun heroPanelBrush(
    surface: Color,
    density: Density,
    height: Dp = 140.dp,
    start: Dp = 0.dp,
): Brush =
    Brush.verticalGradient(
        colorStops =
            arrayOf(
                0f to Color.Transparent,
                0.42f to surface.copy(alpha = 0.30f),
                0.72f to surface.copy(alpha = 0.78f),
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