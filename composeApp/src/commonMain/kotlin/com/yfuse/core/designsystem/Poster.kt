package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import kotlin.math.roundToInt

/** Large artwork may resolve cinematically, but should never hold the image soft for 550ms. */
private const val ArtworkRevealDurationMs = 400
private val ArtworkRevealBlur = 6.dp
private const val ArtworkRevealScaleFrom = 1.025f

/** Dense rails and grids only need a quick opacity hand-off from their placeholder. */
internal const val PosterFadeDurationMs = 180

/**
 * An image that is allowed a second (and third) guess.
 *
 * Artwork in this app comes from hosts that fail one URL at a time rather than all of
 * them: an Emby item whose backdrop is missing but whose poster is not, a TMDB size that
 * `image.tmdb.org` will not serve when `media.themoviedb.org` will. A bare `AsyncImage`
 * turns any of those into a permanently blank tile, which is what "部分图片不显示" looked
 * like from the outside. Give it every URL that would do and it walks the list on error.
 *
 * Nulls and blanks are dropped by the caller's convenience, so builders that return
 * `String?` can be listed inline.
 */
@Composable
fun FallbackImage(
    urls: List<String?>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * 图片渐进加载 §3.1. Off for artwork small enough that the blur is only cost — a 20dp
     * cast avatar has nothing to resolve into.
     */
    progressive: Boolean = true,
    /** Fade the drawable without adding the progressive blur and 1.05 scale. */
    alphaOnly: Boolean = false,
    /** Large artwork uses the default 400ms reveal; dense posters pass 180ms. */
    revealDurationMillis: Int = ArtworkRevealDurationMs,
    revealBlur: Dp = ArtworkRevealBlur,
    revealScaleFrom: Float = ArtworkRevealScaleFrom,
    /** Reports the fallback candidate whose drawable actually reached the screen. */
    onResolvedUrl: (String) -> Unit = {},
) {
    val candidates = remember(urls) { urls.filterNotNull().filter { it.isNotBlank() }.distinct() }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var loaded by remember(candidates, candidateIndex) { mutableStateOf(false) }
    var exhausted by remember(candidates) { mutableStateOf(candidates.isEmpty()) }

    /**
     * Whether this particular picture is allowed the entrance.
     *
     * The reveal exists to cover a wait. An image Coil already holds in memory has no wait
     * to cover, so playing it there is not polish — it is an effect inserted in front of
     * something that was ready to draw. It showed up worst in the grids: [loaded] restarts
     * at false every time a tile is recycled into composition, so scrolling back over
     * artwork already on screen a moment ago re-blurred every tile, every time.
     *
     * Set from the request's own data source, so the decision is per picture rather than a
     * guess about the page.
     */
    var instant by remember(candidates, candidateIndex) { mutableStateOf(false) }
    val animate = progressive && !LocalAccessibilityOptions.current.reduceMotion && !instant
    // 0 while the picture is still arriving, 1 once it has settled into place.
    val settle by animateFloatAsState(
        targetValue = if (loaded || !animate) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (animate) revealDurationMillis else 0,
                easing = Motion.Curve,
            ),
        label = "imageIn",
    )
    Box(modifier) {
        if (exhausted) {
            FailedImagePlaceholder(contentDescription)
        }
        if (!exhausted) {
            candidates.getOrNull(candidateIndex)?.let { candidate ->
                val requestIndex = candidateIndex
                key(candidate) {
                    AsyncImage(
                        model = candidate,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer {
                                // The placeholder underneath is the caller's — [Poster] tints its
                                // own well — because artwork colour is unknown before arrival.
                                val remaining = 1f - settle
                                val scale =
                                    if (alphaOnly) {
                                        1f
                                    } else {
                                        1f + (revealScaleFrom - 1f) * remaining
                                    }
                                scaleX = scale
                                scaleY = scale
                                alpha = settle
                                // Below API 31 renderEffect is ignored, so the load resolves as a
                                // scale-and-fade on those devices rather than not at all.
                                renderEffect =
                                    if (!alphaOnly && remaining > 0.01f) {
                                        val radius = revealBlur.toPx() * remaining
                                        BlurEffect(radius, radius)
                                    } else {
                                        null
                                    }
                            },
                        onSuccess = { success ->
                            // Order matters: [instant] has to be true before [loaded] flips, or
                            // the animation starts on this frame and the flag lands on the next.
                            if (candidateIndex == requestIndex) {
                                if (success.result.dataSource == DataSource.MEMORY_CACHE) instant = true
                                exhausted = false
                                loaded = true
                                onResolvedUrl(candidate)
                            }
                        },
                        onError = {
                            // A disposed request can finish after its replacement. Only advance
                            // when the callback still belongs to the visible URL.
                            if (candidateIndex == requestIndex) {
                                if (requestIndex < candidates.lastIndex) {
                                    candidateIndex = requestIndex + 1
                                } else {
                                    exhausted = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.FailedImagePlaceholder(description: String?) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.card2.copy(alpha = 0.42f))
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics {
                        contentDescription = "$description，图片无法加载"
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = imageFallbackMonogram(description),
            style = AppTypography.section.strong,
            color = palette.hint,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

internal fun imageFallbackMonogram(description: String?): String =
    description
        ?.trim()
        ?.firstOrNull()
        ?.uppercaseChar()
        ?.toString() ?: "—"

/**
 * `.poster` — a rounded, cropped artwork tile, optionally captioned by
 * `.poster-title` and underlined by the 继续观看 progress bar.
 */
@Composable
fun Poster(
    url: String?,
    fallbackUrl: String? = null,
    fallbackUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    title: String? = null,
    year: String? = null,
    shape: Shape = GlassShapes.poster,
    /** Community score rendered as a compact badge at the artwork's top-left. */
    rating: Double? = null,
    /** 0f..1f — draws the 3px `#5B7FD1` resume bar along the bottom edge. */
    progress: Float? = null,
    contentDescription: String? = title,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    sharedTransitionKey: MediaSharedElementKey? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val sharedController = LocalSharedMediaTransitionController.current
    val resolvedOnClick =
        onClick?.let { click ->
            {
                if (!reduceMotion && sharedTransitionKey != null) {
                    sharedController?.begin(sharedTransitionKey)
                }
                click()
            }
        }
    val candidates =
        remember(url, fallbackUrl, fallbackUrls) {
            (listOfNotNull(url, fallbackUrl) + fallbackUrls).filter { it.isNotBlank() }.distinct()
        }
    Box(
        modifier
            .clip(shape)
            // 占位主色渐变 §3.1. The artwork's own colour cannot be known before the
            // artwork arrives, so this is the palette's placeholder tone with a slight
            // vertical fall — enough that an unloaded tile reads as a surface rather than
            // a grey block, and enough for the blur to resolve *out of* something.
            .background(
                if (palette.isDark) {
                    cssLinearGradient(180f, 0f to Color(0xFF283040), 1f to Color(0xFF1D2430))
                } else {
                    cssLinearGradient(180f, 0f to Color(0xFFE4E9F1), 1f to Color(0xFFD3DAE5))
                },
            ).let {
                // 触摸反馈全应用统一走 [pressable]：压缩 0.97、无涟漪、跟随
                // 「减弱动态效果」。这里原来是裸 clickable，也就是 Material 涟漪，
                // 于是同一个海报组件在首页/媒体库点下去是涟漪、在详情页（外层套了
                // pressable）是缩放。长按现在也归 [pressable] 管，所以两条路径的
                // 反馈终于一致了 —— 之前带长按的海报走 combinedClickable，压根没有反馈。
                //
                // 海报是全 app 唯一开 tilt 的地方：它足够大，倾斜看得出来，而且这是
                // 用户唯一会盯着看的图像内容。
                when {
                    resolvedOnClick != null || onLongClick != null ->
                        it.pressable(
                            tilt = true,
                            focusShape = shape,
                            onLongClick = onLongClick,
                            onClick = { resolvedOnClick?.invoke() },
                        )
                    else -> it
                }
            },
    ) {
        FallbackImage(
            urls = candidates,
            contentDescription = contentDescription,
            modifier =
                Modifier
                    .sharedMediaArtwork(sharedTransitionKey)
                    .fillMaxSize(),
            alphaOnly = true,
            revealDurationMillis = PosterFadeDurationMs,
        )

        overlay()

        mediaRatingLabel(rating)?.let { label ->
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(GlassShapes.chip)
                    .background(Color.Black.copy(alpha = 0.64f))
                    .semantics { contentDescription = "评分 $label" }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.StarFilled,
                    contentDescription = null,
                    tint = Brand.Imdb,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    label,
                    style = AppTypography.caption.strong,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }

        if (title != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(if (year == null) 54.dp else 64.dp)
                    .background(
                        scrim(
                            0f to Color(0xFF080C14).copy(alpha = 0.82f),
                            0.55f to Color(0xFF080C14).copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 9.dp, end = 9.dp, bottom = 7.dp),
            ) {
                Text(
                    text = title,
                    style =
                        AppTypography.body.strong.copy(
                            lineHeight = 16.sp,
                            shadow =
                                Shadow(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    offset = Offset(0f, 1f),
                                    blurRadius = 4f,
                                ),
                        ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (year != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = year,
                        style = AppTypography.caption.regular,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
            }
        }

        progress?.takeIf { it > 0f }?.let { rawProgress ->
            val watched = rawProgress.coerceIn(0f, 1f)
            // Keep the total duration visible: without a rail, a short watched segment
            // reads like a decorative underline instead of resumable playback state.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.Black.copy(alpha = 0.42f)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(watched)
                    .height(4.dp)
                    .background(PrimaryGradient),
            )
        }
    }
}

/**
 * A poster with its identity outside the artwork. Keeping the copy below the
 * image makes titles readable without covering poster art and gives list/grid
 * cards one consistent treatment.
 */
@Composable
fun CaptionedPoster(
    url: String?,
    fallbackUrl: String? = null,
    fallbackUrls: List<String> = emptyList(),
    title: String,
    year: String?,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
    rating: Double? = null,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
    sharedTransitionKey: MediaSharedElementKey? = null,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val sharedController = LocalSharedMediaTransitionController.current
    val resolvedOnClick =
        onClick?.let { click ->
            {
                if (!reduceMotion && sharedTransitionKey != null) {
                    sharedController?.begin(sharedTransitionKey)
                }
                click()
            }
        }
    Column(
        // The press lands on the whole tile, caption included — scaling only the artwork
        // and leaving the title behind reads as the image slipping out from under it.
        modifier.let { base ->
            if (resolvedOnClick != null) base.pressable(onClick = resolvedOnClick) else base
        },
    ) {
        Poster(
            url = url,
            fallbackUrl = fallbackUrl,
            fallbackUrls = fallbackUrls,
            rating = rating,
            progress = progress,
            contentDescription = title,
            modifier = posterModifier,
            sharedTransitionKey = sharedTransitionKey,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = AppTypography.body.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        if (year != null) {
            Text(
                text = year,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Reserve the metadata line so cards align even when a year is absent. Matches
            // the year's own line box — `mr(10f)` resolves to the 11sp type floor, whose
            // default line height is 11 × 1.35.
            Spacer(Modifier.height(15.dp))
        }
    }
}

/** Stable one-decimal score label; invalid and absent ratings do not reserve badge space. */
internal fun mediaRatingLabel(rating: Double?): String? {
    val value = rating?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return ((value.coerceAtMost(10.0) * 10.0).roundToInt() / 10.0).toString()
}
