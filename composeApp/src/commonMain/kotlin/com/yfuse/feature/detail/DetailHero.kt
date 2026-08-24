package com.yfuse.feature.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DolbyBadge
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.fadeIntoPage
import com.yfuse.core.designsystem.heroTopScrim
import com.yfuse.core.designsystem.isSharedMediaArtworkActive
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaVersion

@Composable
internal fun rememberHeroScroll(
    listState: LazyListState,
    heroHeightPx: Float,
    /**
     * How far past the top the list is being dragged, negative-going.
     *
     * `firstVisibleItemScrollOffset` bottoms out at 0 and knows nothing about over-scroll, so
     * on its own it can never describe a pull downward — which is why the hero had no
     * rubber band to ignore in the first place. The over-drag comes from the nested-scroll
     * connection instead; see [rememberOverscrollPull].
     */
    pull: State<Float>,
): State<Float> =
    remember(listState, heroHeightPx, pull) {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> heroHeightPx
                pull.value > 0f -> -pull.value
                else -> listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }

/**
 * Distance the user is currently dragging the list past its top, in pixels.
 *
 * Taken from the scroll the list itself could not consume, damped so the artwork trails the
 * finger rather than matching it — the resistance is what says "this is as far as it goes"
 * without stopping the gesture dead. Released, it returns on the settle spring so the
 * artwork comes home with the same weight it went out with.
 */
@Composable
internal fun rememberOverscrollPull(reduceMotion: Boolean): Pair<State<Float>, NestedScrollConnection> {
    val raw = remember { mutableFloatStateOf(0f) }
    val connection =
        remember(reduceMotion) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (reduceMotion) return Offset.Zero
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    if (available.y <= 0f) return Offset.Zero
                    raw.floatValue += available.y * OVERSCROLL_DAMPING
                    // Not consumed: the list's own overscroll effect should still play, and
                    // claiming it here would fight the pull-to-refresh above it.
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (raw.floatValue != 0f) {
                        Animatable(raw.floatValue).animateTo(0f, Motion.settle<Float>()) {
                            raw.floatValue = value
                        }
                    }
                    return Velocity.Zero
                }
            }
        }
    return remember(raw, connection) { raw to connection }
}

/** How much of an over-drag the artwork actually takes. */
private const val OVERSCROLL_DAMPING = 0.5f

/** 0 while the artwork owns the top edge, 1 once the glass bar has taken over. */
@Composable
internal fun rememberTopBarProgress(
    listState: LazyListState,
    heroHeightPx: Float,
    density: Density,
): State<Float> =
    remember(listState, heroHeightPx, density) {
        val start = (heroHeightPx - with(density) { 188.dp.toPx() }).coerceAtLeast(1f)
        val span = with(density) { 96.dp.toPx() }
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                ((listState.firstVisibleItemScrollOffset - start) / span).coerceIn(0f, 1f)
            }
        }
    }

// ---------------------------------------------------------------- chrome

/**
 * Backdrop under [heroScrim]. It lags the list on scroll and grows on over-drag; the
 * back affordance lives in [DetailTopBar] so it survives past the artwork.
 */
@Composable
internal fun Hero(
    urls: List<String?>,
    title: String,
    height: Dp,
    surfaceColor: Color,
    animationKey: String,
    sharedKey: MediaSharedElementKey?,
    scroll: State<Float>,
    onResolvedUrl: (String) -> Unit,
) {
    // 详情页顶图 1.08 → 1, §3.1. The parallax below has always been here; the entrance
    // it belongs to was not, so the artwork simply appeared at rest.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val sharedEntrance = isSharedMediaArtworkActive(sharedKey)
    var entered by remember(animationKey) { mutableStateOf(sharedEntrance) }
    LaunchedEffect(animationKey) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (reduceMotion) 0 else Motion.EXPAND,
                easing = Motion.Curve,
            ),
        label = "heroEntrance",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            // FallbackImage is transparent until its first drawable is ready. Paint the same
            // surface used by the page and the hero scrim from frame one, so image loading
            // cannot expose a separate band between the artwork and the content below.
            .background(surfaceColor)
            .graphicsLayer {
                // Upward, a restrained parallax: the artwork lags the page so the two read
                // as separate planes.
                //
                // Downward, the artwork stretches to meet the finger. [scroll] goes negative
                // on over-drag, and the hero used to ignore that entirely — the page rubber-
                // banded away and left a gap of backdrop above a picture that stayed exactly
                // where it was. Growing it from the top edge instead is the oldest gesture on
                // the platform and the reason a large-artwork page feels attached to the
                // hand: pull, and the thing you are pulling gives.
                val over = (-scroll.value).coerceAtLeast(0f)
                if (over > 0f) {
                    val grow = 1f + over / size.height
                    scaleX = grow
                    scaleY = grow
                    // Anchored to the top edge so the stretch fills the gap the over-drag
                    // opened rather than pushing the artwork further down it.
                    transformOrigin = TransformOrigin(0.5f, 0f)
                } else {
                    translationY = scroll.value * 0.35f
                }
            },
    ) {
        // Clip only the artwork plane. The outer hero may still grow for pull-down overscroll,
        // while its 1.08 entrance can no longer bleed below the physical hero edge without
        // the scrim and flash through the sheet's transparent gradient start.
        Box(Modifier.fillMaxSize().clipToBounds()) {
            FallbackImage(
                urls = urls,
                contentDescription = title,
                // The hero already owns the scale entrance. Resolve readiness with alpha only
                // so a network/disk result does not arrive as a hard cut or add a second scale.
                progressive = true,
                alphaOnly = true,
                onResolvedUrl = onResolvedUrl,
                modifier =
                    Modifier
                        .sharedMediaArtwork(sharedKey)
                        .fillMaxSize()
                        .fadeIntoPage()
                        .graphicsLayer {
                            val scale =
                                1f +
                                    (Motion.DETAIL_HERO_SCALE_FROM - 1f) * (1f - entrance)
                            scaleX = scale
                            scaleY = scale
                        },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(heroTopScrim()),
            )
        }
    }
}

/**
 * Collapsing top bar. The back chip is always present — the artwork scrolls away but
 * the affordance does not — and the glass plate, title and play shortcut fade in as
 * the page takes over the top edge (§4.2 顶栏材质切换).
 */
@Composable
internal fun DetailTopBar(
    title: String,
    backdrop: BackdropState,
    progress: State<Float>,
    surfaceColor: Color,
    accent: Color,
    showPlay: Boolean,
    showMore: Boolean,
    solid: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    // 0.94 was very nearly opaque, and it had to be: with nothing blurred behind it, any
    // less and the poster underneath read straight through the title. Now that §8.1's blur
    // is actually under the plate, the fill can go back to being a fill — this bar was the
    // last chrome in the app still compensating for a missing material with alpha.
    val plateFill = surfaceColor.copy(alpha = 0.72f)
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress.value }
                .backdropBlur(backdrop, RectangleShape)
                .background(plateFill),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.BottomStart)
                .graphicsLayer { alpha = progress.value }
                .background(palette.border),
        )
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(TopBarHeight)
                .padding(horizontal = Dimens.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val p = progress.value
            // What these two sit on changes as the bar fills in, and that is what decides
            // whether they are dense glass or pale glass — so it travels with them.
            val behind = lerp(HeroInk, surfaceColor, p)
            Icon(
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = lerp(Color.White, palette.text, p),
                modifier =
                    Modifier
                        .pressable(onClick = onBack)
                        .touchTarget()
                        .size(38.dp)
                        .liquidGlass(
                            shape = CircleShape,
                            fill =
                                lerp(
                                    Color(0xFF11151F).copy(alpha = 0.28f),
                                    palette.card2,
                                    p,
                                ),
                            border =
                                lerp(
                                    Color.White.copy(alpha = 0.34f),
                                    palette.border,
                                    p,
                                ),
                            over = behind,
                            sheen = 0.7f,
                        ).padding(11.dp),
            )
            Text(
                title,
                style = AppTypography.section.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = progress.value },
            )
            if (showPlay) {
                Row(
                    Modifier
                        .graphicsLayer { alpha = progress.value }
                        .pressable(enabled = solid, onClick = onPlay)
                        .touchTarget()
                        .liquidGlass(
                            shape = GlassShapes.chip,
                            fill = accent.copy(alpha = 0.14f),
                            border = accent.copy(alpha = 0.30f),
                            // It only ever appears once the bar's own plate is opaque.
                            over = surfaceColor,
                            sheen = 0.7f,
                        ).padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(10.dp))
                    Text("播放", style = AppTypography.body.strong, color = accent)
                }
            }
            if (showMore) {
                // Unlike the title and the play shortcut this does not fade in with scroll:
                // it is the only route to 下载 / 标记已看 / 一起看, so it has to be reachable
                // from the top of the page as well as the bottom.
                Icon(
                    AppIcons.More,
                    contentDescription = "更多操作",
                    tint = lerp(Color.White, palette.text, p),
                    modifier =
                        Modifier
                            .pressable(onClick = onMore)
                            .touchTarget()
                            .size(38.dp)
                            .liquidGlass(
                                shape = CircleShape,
                                fill =
                                    lerp(
                                        Color(0xFF11151F).copy(alpha = 0.28f),
                                        palette.card2,
                                        p,
                                    ),
                                border =
                                    lerp(
                                        Color.White.copy(alpha = 0.34f),
                                        palette.border,
                                        p,
                                    ),
                                over = behind,
                                sheen = 0.7f,
                            ).padding(11.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- information sheet

/**
 * 片名 — centred on the artwork's lower edge, with no plate under it.
 *
 * What this replaces: a poster thumbnail and a glass card lifted 16dp over the backdrop.
 * Between them they covered the lower quarter of artwork that is only 40% of the screen
 * tall, to restate a poster the user had just tapped and a title the top bar already
 * carries. Dropping the plate gave the backdrop its height back; riding on the artwork
 * rather than under it means the block costs the picture nothing at all — the artwork now
 * continues beneath 播放 and ends after the primary action.
 *
 * Which is also why the inks here are fixed rather than from the palette: this copy is on
 * a photograph in both themes. The caller keeps the artwork clean behind it — see the
 * `start` on [heroPanelBrush] — so nothing here ever sits half on artwork and half on
 * page, where no single ink would work.
 */
@Composable
internal fun TitleBlock(
    detail: MediaDetail,
    title: String,
    accent: Color,
    version: MediaVersion?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            title,
            style = AppTypography.display.strong,
            color = ArtworkInk,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val facts =
            listOfNotNull(
                detail.year?.toString(),
                detail.runtimeMinutes?.let(::runtimeLabel),
            )
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                facts.joinToString(" · "),
                style = AppTypography.body.regular,
                color = ArtworkInkSub,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        }
        if (detail.communityRating != null || detail.officialRating != null) {
            Spacer(Modifier.height(9.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                detail.communityRating?.let {
                    RatingFigure((it * 10).toInt() / 10.0, accent)
                }
                detail.officialRating?.let { CertificationBadge(it) }
            }
        }
        detail.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() }?.let { genre ->
            Spacer(Modifier.height(8.dp))
            Text(genre, style = AppTypography.body.medium, color = ArtworkInkFaint, maxLines = 1)
        }
        // Only what this copy actually carries. A page that always claims Dolby says
        // nothing; here the badge is the answer to "is this the good file", which on a
        // title the library holds twice is the question being asked.
        val dolbyVision = version?.isDolbyVision == true
        val dolbyAtmos = version?.hasDolbyAtmos == true
        if (dolbyVision || dolbyAtmos) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (dolbyVision) DolbyBadge("VISION", ArtworkInk)
                if (dolbyAtmos) DolbyBadge("ATMOS", ArtworkInk)
            }
        }
    }
}

/** `1小时13分钟` — hours only when there are any, because "0小时13分钟" reads as a bug. */
private fun runtimeLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "${rest}分钟"
        rest == 0 -> "${hours}小时"
        else -> "${hours}小时${rest}分钟"
    }
}

/**
 * Body of the primary key, anchored to the product blue with a restrained amount of artwork
 * influence. Letting the poster own the entire fill made yellow/olive swatches look unrelated to
 * the cool glass page around them; keeping the artwork contribution small preserves continuity
 * without allowing one image to redesign the primary action.
 *
 * A poster's vibrant swatch lands anywhere on the lightness scale and this key carries white
 * copy, so the blended result is kept inside a luminance band where that copy stays legible.
 */
internal fun actionKeyBrush(accent: Color): Brush {
    val body = primaryActionColor(accent)
    return cssLinearGradient(135f, 0f to lerp(body, Color.White, 0.14f), 1f to body)
}

internal fun primaryActionColor(accent: Color): Color =
    lerp(
        Brand.Primary, // design-system: brand-identity
        accent.copy(alpha = 1f),
        PRIMARY_ACTION_ARTWORK_INFLUENCE,
    ).forWhiteInk()

private fun Color.forWhiteInk(): Color {
    val luminance = luminance()
    if (luminance <= MAX_ACTION_KEY_LUMINANCE) return this
    // Straight toward black keeps the hue and spends only lightness.
    val excess = ((luminance - MAX_ACTION_KEY_LUMINANCE) / luminance).coerceIn(0f, 1f)
    return lerp(this, Color.Black, excess)
}

private const val MAX_ACTION_KEY_LUMINANCE = 0.22f
private const val PRIMARY_ACTION_ARTWORK_INFLUENCE = 0.12f

/**
 * The title block's inks.
 *
 * It sits on the artwork now, not on the page, so it cannot take the palette: the page
 * ink is dark in the light theme and the artwork underneath is dark in both. This is the
 * same answer the library hero and the player's panel already reach — white copy over a
 * scrim, and the accent's light end rather than the spec's `#3D64C9`, which is a
 * light-theme ink and goes muddy on a photograph.
 */
private val ArtworkInk = Color.White
private val ArtworkInkSub = Color.White.copy(alpha = 0.94f)
private val ArtworkInkFaint = Color.White.copy(alpha = 0.84f)

/** `TMDB` in the secondary ink, the figure itself large and in the accent. */
@Composable
private fun RatingFigure(
    rating: Double,
    accent: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TMDB", style = AppTypography.body.strong, color = ArtworkInkSub)
        Text(rating.toString(), style = AppTypography.section.strong, color = lerp(accent, Color.White, 0.38f))
    }
}

/** 分级 is a classification, not a score — it gets a neutral outline, not a brand colour. */
@Composable
private fun CertificationBadge(label: String) {
    Text(
        label,
        style = AppTypography.caption.strong,
        color = ArtworkInkSub,
        modifier =
            Modifier
                .solidGlass(
                    shape = AppShapes.micro,
                    fill = Color.Transparent,
                    border = ArtworkInkSub.copy(alpha = 0.42f),
                ).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
