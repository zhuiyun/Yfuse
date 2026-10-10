package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * How much narrower than its frame an upright picture may be before it is fitted rather than
 * cropped. A 9:16 短剧 poster in a 2:3 tile is past it, and so is an upright still, or a poster
 * standing in for a missing still, in a 16:9 card; a 3:4 poster is not, and a landscape picture
 * always crops.
 */
private const val NARROW_ARTWORK_FIT_FACTOR = 1.15f

/** Whether a picture of [imageAspect] (width / height) is fitted in a frame of [frameAspect]. */
internal fun fitsNarrowArtwork(
    imageAspect: Float,
    frameAspect: Float,
): Boolean =
    imageAspect > 0f &&
        frameAspect > 0f &&
        imageAspect < 1f &&
        imageAspect * NARROW_ARTWORK_FIT_FACTOR < frameAspect

/**
 * The soft fill behind a fitted picture is the picture itself decoded this small and stretched:
 * the filtering blurs it for free, where a blur effect per tile would cost every frame of a
 * scrolling grid.
 */
private const val SOFT_FILL_PX = 24
private val SoftFillScrim = Color.Black.copy(alpha = 0.28f)

/**
 * Picture shapes already seen, by URL, so a tile recycled into a grid is fitted from its first
 * frame instead of cropping until the load reports back. Read and written on the main thread.
 */
private object KnownArtworkAspects {
    private const val CAPACITY = 512
    private val aspects = LinkedHashMap<String, Float>()

    operator fun get(url: String): Float? = aspects[url]

    operator fun set(
        url: String,
        aspect: Float,
    ) {
        aspects.remove(url)
        aspects[url] = aspect
        if (aspects.size > CAPACITY) aspects.remove(aspects.keys.first())
    }
}

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
     * cast avatar has nothing to resolve into. It only ever turned the blur off: the opacity
     * hand-off still runs, so a small picture no longer cuts in while its neighbours fade.
     */
    progressive: Boolean = true,
    /**
     * Fade the drawable without adding progressive blur or scale.
     *
     * On by default: a grid scrolling quickly had several tiles each holding a `BlurEffect`
     * layer in the same frame. Only the few large, single pictures — a page hero, the
     * detail poster — pass `false` and take the cinematic resolve.
     */
    alphaOnly: Boolean = true,
    /**
     * Dense artwork — every opacity-only picture — hands off in [Motion.POSTER_FADE]; the few
     * large pictures that take the blurred resolve keep [Motion.ARTWORK_REVEAL]. Thumbnails,
     * avatars and category tiles used to fall through to the 400ms hero timing by omission.
     */
    revealDurationMillis: Int = if (alphaOnly) Motion.POSTER_FADE else Motion.ARTWORK_REVEAL,
    revealBlur: Dp = ImageRevealMotion.ResolveBlur,
    revealScaleFrom: Float = ImageRevealMotion.RESOLVE_SCALE_FROM,
    /** Reports the fallback candidate whose drawable actually reached the screen. */
    onResolvedUrl: (String) -> Unit = {},
    /**
     * An upright picture much narrower than the frame is shown whole, fitted over a soft fill of
     * itself, instead of cropped to a band across its middle; see [fitsNarrowArtwork]. Only for
     * a frame that is not itself a portrait's: an avatar's circle would fit a portrait too.
     */
    fitNarrow: Boolean = false,
    /**
     * Jellyfin's BlurHash for the first of [urls]: the picture's colours and shapes, shown until
     * it arrives. Without one, a colour already worked out for the same artwork stands in when
     * there is one; see [rememberArtworkPlaceholder].
     */
    blurHash: String? = null,
) {
    val candidates = remember(urls) { urls.filterNotNull().filter { it.isNotBlank() }.distinct() }
    var frameAspect by remember { mutableFloatStateOf(0f) }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var loaded by remember(candidates, candidateIndex) { mutableStateOf(false) }
    var exhausted by remember(candidates) { mutableStateOf(candidates.isEmpty()) }
    var sweepReady by remember(candidates) { mutableStateOf(false) }
    LaunchedEffect(candidates, loaded, exhausted) {
        sweepReady = false
        if (!loaded && !exhausted) {
            delay(150L)
            sweepReady = true
        }
    }
    val placeholder = rememberArtworkPlaceholder(blurHash, candidates)
    // 静息 keeps the hand-off from the placeholder, not the resolve; see [ImageRevealMotion].
    val reveal =
        ImageRevealMotion.reveal(
            large = progressive && !alphaOnly,
            calm = calmMotion(),
            durationMillis = revealDurationMillis,
        )

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
    val settle by rememberImageRevealProgress(
        requestKey = candidates to candidateIndex,
        loaded = loaded,
        instant = instant,
        enabled = true,
        durationMillis = reveal.durationMillis,
    )
    Box(
        if (fitNarrow) {
            modifier.onSizeChanged { frameAspect = if (it.height > 0) it.width / it.height.toFloat() else 0f }
        } else {
            modifier
        },
    ) {
        if (exhausted) {
            FailedImagePlaceholder(contentDescription)
        }
        if (!exhausted) {
            placeholder?.let { standIn ->
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            // Hidden rather than composed away once the picture has taken over,
                            // where it would only be overdraw: the reveal's every frame changes a
                            // layer property here, not the page's drawing.
                            alpha = if (loaded && settle >= 1f) 0f else 1f
                        }.drawBehind { drawArtworkPlaceholder(standIn.value) },
                )
            }
            candidates.getOrNull(candidateIndex)?.let { candidate ->
                val requestIndex = candidateIndex
                // The candidate list resets loaded/exhausted above. Recreate the painter in
                // the same generation even if only a fallback URL changed; otherwise Coil
                // keeps its successful painter and never re-emits onSuccess, leaving alpha 0.
                key(candidates, candidate) {
                    var imageAspect by remember { mutableFloatStateOf(KnownArtworkAspects[candidate] ?: 0f) }
                    val fitted = fitNarrow && fitsNarrowArtwork(imageAspect, frameAspect)
                    if (fitted) SoftArtworkFill(candidate) { settle }
                    AsyncImage(
                        model = candidate,
                        contentDescription = contentDescription,
                        contentScale = if (fitted) ContentScale.Fit else contentScale,
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer {
                                // Underneath is the artwork's own placeholder when there is one,
                                // otherwise the caller's — [Poster] tints its own well.
                                val remaining = 1f - settle
                                val resolves = reveal.resolves
                                val scale =
                                    if (!resolves) {
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
                                    if (resolves && remaining > 0.01f) {
                                        val radius = revealBlur.toPx() * remaining
                                        artworkBlurCache.effect(radius)
                                    } else {
                                        null
                                    }
                            },
                        onSuccess = { success ->
                            if (fitNarrow) {
                                val size = success.painter.intrinsicSize
                                if (size.width > 0f && size.height > 0f) {
                                    imageAspect = size.width / size.height
                                    KnownArtworkAspects[candidate] = imageAspect
                                }
                            }
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
            if (!loaded && sweepReady) {
                Box(Modifier.matchParentSize().skeletonSweep())
            }
        }
    }
}

/** What a fitted picture sits on: itself, decoded tiny and stretched, a shade darker. */
@Composable
private fun SoftArtworkFill(
    url: String,
    alpha: () -> Float,
) {
    val context = LocalPlatformContext.current
    val request =
        remember(url, context) {
            ImageRequest
                .Builder(context)
                .data(url)
                .size(SOFT_FILL_PX)
                .build()
        }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha() }
                .drawWithContent {
                    drawContent()
                    drawRect(SoftFillScrim)
                },
    )
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
    shape: Shape = AppShapes.card,
    /** Community score rendered as a compact badge at the artwork's top-left. */
    rating: Double? = null,
    /** 0f..1f — draws the 3px `#5B7FD1` resume bar along the bottom edge. */
    progress: Float? = null,
    /** Jellyfin's BlurHash for [url]'s picture, shown until it arrives; see [FallbackImage]. */
    blurHash: String? = null,
    contentDescription: String? = title,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * The 浮起菜单 a long press lifts this poster into; see [liftable]. Takes the long press
     * over from [onLongClick], which is then ignored. The menu's card starts from this poster's
     * own artwork when it names none.
     */
    liftMenu: (() -> LiftMenu)? = null,
    sharedTransitionKey: MediaSharedElementKey? = null,
    /** See [FallbackImage]: an upright picture much narrower than the tile is fitted, not cropped. */
    fitNarrow: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val sharedController = LocalSharedMediaTransitionController.current
    val resolvedOnClick =
        onClick?.let { click ->
            {
                if (!reduceMotion && sharedTransitionKey != null) {
                    sharedController?.begin(sharedTransitionKey)
                }
                // No morph under 减弱动态效果, but 跟手返回 still goes back into this poster.
                if (reduceMotion && sharedTransitionKey != null) sharedController?.noteOrigin(sharedTransitionKey)
                click()
            }
        }
    val candidates =
        remember(url, fallbackUrl, fallbackUrls) {
            (listOfNotNull(url, fallbackUrl) + fallbackUrls).filter { it.isNotBlank() }.distinct()
        }
    // The skeleton's own ink, falling slightly towards the foot: a tile that arrives from a
    // skeleton keeps the colour it had instead of trading it for a second grey first.
    val placeholder = skeletonFill()
    val placeholderFall =
        remember(placeholder) {
            Brush.verticalGradient(
                listOf(placeholder, placeholder.copy(alpha = (placeholder.alpha * PLACEHOLDER_FALL).coerceAtMost(1f))),
            )
        }
    val lift = liftMenu?.let { build -> { build().withArtwork(candidates) } }
    Box(
        modifier
            .dialogPosterSource()
            // Outside the press, so the lift can take the stream over from the click; see [liftable].
            .liftable(menu = lift, onOpen = resolvedOnClick)
            .let {
                // 触摸反馈全应用统一走 [pressable]：压缩 0.97、无涟漪、跟随
                // 「减弱动态效果」。这里原来是裸 clickable，也就是 Material 涟漪，
                // 于是同一个海报组件在首页/媒体库点下去是涟漪、在详情页（外层套了
                // pressable）是缩放。长按现在也归 [pressable] 管，所以两条路径的
                // 反馈终于一致了 —— 之前带长按的海报走 combinedClickable，压根没有反馈。
                //
                // 海报是全 app 唯一开 tilt 的地方：它足够大，倾斜看得出来，而且这是
                // 用户唯一会盯着看的图像内容。Outside the clip, so the whole card leans: inside
                // it the artwork turned within a frame that stayed put and showed the
                // placeholder along every edge.
                val longClick = onLongClick.takeIf { lift == null }
                when {
                    resolvedOnClick != null || longClick != null ->
                        it.pressable(
                            tilt = true,
                            focusShape = shape,
                            onLongClick = longClick,
                            onClick = { resolvedOnClick?.invoke() },
                        )
                    else -> it
                }
            }.clip(shape)
            // 占位主色渐变 §3.1. The artwork's own colour cannot be known before the
            // artwork arrives; this is the loading ink with a slight vertical fall — enough
            // that an unloaded tile reads as a surface, and for the blur to resolve out of.
            .background(placeholderFall),
    ) {
        FallbackImage(
            urls = candidates,
            contentDescription = contentDescription,
            modifier =
                Modifier
                    .launchWaveImage()
                    .sharedMediaArtwork(sharedTransitionKey)
                    .playerArtworkSource(sharedTransitionKey, candidates)
                    .fillMaxSize(),
            alphaOnly = true,
            // Dense rails and grids only need a quick opacity hand-off from their placeholder.
            revealDurationMillis = Motion.POSTER_FADE,
            fitNarrow = fitNarrow,
            blurHash = blurHash,
        )

        overlay()

        mediaRatingLabel(rating)?.let { label ->
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(AppShapes.chip)
                    .background(Color.Black.copy(alpha = 0.64f))
                    .semantics { this.contentDescription = "评分 $label" }
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
            // A recycled poster starts at its own value, never the previous artwork's progress.
            key(candidates) {
                val watched = rawProgress.coerceIn(0f, 1f)
                val moving = !reduceMotion && LocalRouteVisible.current
                val animatedWatched =
                    animateFloatAsState(
                        targetValue = watched,
                        animationSpec = if (moving) tween(Motion.STANDARD, easing = Motion.Curve) else snap(),
                        label = "poster-watched",
                    )
                val origin = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
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
                        .fillMaxWidth()
                        .height(4.dp)
                        .graphicsLayer {
                            // Read in the layer: no per-frame composition or layout, including in grids.
                            scaleX = animatedWatched.value
                            transformOrigin = TransformOrigin(origin, 0.5f)
                        }.background(PrimaryGradient),
                )
            }
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
    /** As on [Poster]: Jellyfin's BlurHash for [url]'s picture. */
    blurHash: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** As on [Poster]: the whole tile takes the press, and the artwork is what lifts. */
    liftMenu: (() -> LiftMenu)? = null,
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
                // No morph under 减弱动态效果, but 跟手返回 still goes back into this poster.
                if (reduceMotion && sharedTransitionKey != null) sharedController?.noteOrigin(sharedTransitionKey)
                click()
            }
        }
    val lift =
        liftMenu?.let { build ->
            {
                val urls = (listOfNotNull(url, fallbackUrl) + fallbackUrls).filter { it.isNotBlank() }.distinct()
                build().withArtwork(urls)
            }
        }
    val artwork = remember { LiftAnchor() }
    val longClick = onLongClick.takeIf { lift == null }
    Column(
        // The press lands on the whole tile, caption included — scaling only the artwork
        // and leaving the title behind reads as the image slipping out from under it.
        modifier.liftable(menu = lift, anchor = artwork, onOpen = resolvedOnClick).let { base ->
            if (resolvedOnClick != null || longClick != null) {
                base.pressable(onLongClick = longClick, onClick = { resolvedOnClick?.invoke() })
            } else {
                base
            }
        },
    ) {
        Poster(
            url = url,
            fallbackUrl = fallbackUrl,
            fallbackUrls = fallbackUrls,
            rating = rating,
            progress = progress,
            blurHash = blurHash,
            contentDescription = title,
            modifier = posterModifier.liftAnchor(artwork.takeIf { lift != null }),
            sharedTransitionKey = sharedTransitionKey,
            // A 9:16 短剧 poster keeps the title and 全80集 printed at its top and foot.
            fitNarrow = true,
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

/** How much denser the placeholder is at the foot of a tile than at its head. */
private const val PLACEHOLDER_FALL = 1.35f

/**
 * What letting go on a lifted card does, for a card whose tap does something else — a 继续观看 card
 * resumes: open the title's page, noting the artwork as the place 跟手返回 goes back into, without
 * the shared-element morph. The artwork is hidden under the lifted card, so a morph would fly a
 * second copy of it out of the shelf while the card fades.
 */
@Composable
internal fun liftedCardOpen(
    key: MediaSharedElementKey?,
    onOpen: () -> Unit,
): () -> Unit {
    val controller = LocalSharedMediaTransitionController.current
    return {
        if (key != null) controller?.noteOrigin(key)
        onOpen()
    }
}
