package com.yfuse.core.designsystem

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

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
) {
    val candidates = remember(urls) { urls.filterNotNull().filter { it.isNotBlank() }.distinct() }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var loaded by remember(candidates, candidateIndex) { mutableStateOf(false) }
    val animate = progressive && !LocalAccessibilityOptions.current.reduceMotion
    // 0 while the picture is still arriving, 1 once it has settled into place.
    val settle by animateFloatAsState(
        targetValue = if (loaded || !animate) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (animate) Motion.IMAGE_IN else 0,
            easing = Motion.Curve,
        ),
        label = "imageIn",
    )
    candidates.getOrNull(candidateIndex)?.let { candidate ->
        val requestIndex = candidateIndex
        key(candidate) {
            AsyncImage(
                model = candidate,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier.graphicsLayer {
                    // 占位主色 → 12px 模糊放大 1.05 → 清晰归位. The placeholder underneath is
                    // the caller's — [Poster] tints its own well — because nothing can know
                    // the artwork's colour before the artwork has arrived.
                    val remaining = 1f - settle
                    val scale = 1f + (Motion.IMAGE_SCALE_FROM - 1f) * remaining
                    scaleX = scale
                    scaleY = scale
                    alpha = settle
                    // Below API 31 renderEffect is ignored, so the load resolves as a
                    // scale-and-fade on those devices rather than not at all.
                    if (remaining > 0.01f) {
                        val radius = Motion.imageBlur.toPx() * remaining
                        renderEffect = BlurEffect(radius, radius)
                    }
                },
                onSuccess = { loaded = true },
                onError = {
                    // A disposed request can finish after its replacement. Only
                    // advance when the callback still belongs to the visible URL.
                    if (candidateIndex == requestIndex && requestIndex < candidates.lastIndex) {
                        candidateIndex = requestIndex + 1
                    }
                },
            )
        }
    }
}

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
    /** 0f..1f — draws the 3px `#5B7FD1` resume bar along the bottom edge. */
    progress: Float? = null,
    /** Matching key used by list/hero and detail artwork for the route transition. */
    sharedKey: String? = null,
    contentDescription: String? = title,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    val candidates = remember(url, fallbackUrl, fallbackUrls) {
        (listOfNotNull(url, fallbackUrl) + fallbackUrls).filter { it.isNotBlank() }.distinct()
    }
    Box(
        modifier
            .sharedMediaElement(sharedKey)
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
            )
            .let {
                // 触摸反馈全应用统一走 [pressable]：压缩 0.97、无涟漪、跟随
                // 「减弱动态效果」。这里原来是裸 clickable，也就是 Material 涟漪，
                // 于是同一个海报组件在首页/媒体库点下去是涟漪、在详情页（外层套了
                // pressable）是缩放。长按现在也归 [pressable] 管，所以两条路径的
                // 反馈终于一致了 —— 之前带长按的海报走 combinedClickable，压根没有反馈。
                //
                // 海报是全 app 唯一开 tilt 的地方：它足够大，倾斜看得出来，而且这是
                // 用户唯一会盯着看的图像内容。
                when {
                    onClick != null || onLongClick != null -> it.pressable(
                        tilt = true,
                        onLongClick = onLongClick,
                        onClick = { onClick?.invoke() },
                    )
                    else -> it
                }
            },
    ) {
        FallbackImage(
            urls = candidates,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )

        overlay()

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
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 9.dp, end = 9.dp, bottom = 7.dp),
            ) {
                Text(
                    text = title,
                    style = sc(11f, 600, lineHeight = 11f * 1.25f).copy(
                        shadow = Shadow(
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
                        style = mr(9.5f, 400),
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
            }
        }

        if (progress != null && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(Brand.PrimaryGradBottom),
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
    progress: Float? = null,
    sharedKey: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(
        // The press lands on the whole tile, caption included — scaling only the artwork
        // and leaving the title behind reads as the image slipping out from under it.
        modifier.let { base ->
            if (onClick != null) base.pressable(onClick = onClick) else base
        },
    ) {
        Poster(
            url = url,
            fallbackUrl = fallbackUrl,
            fallbackUrls = fallbackUrls,
            progress = progress,
            sharedKey = sharedKey,
            contentDescription = title,
            modifier = posterModifier,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = sc(12f, 600),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        if (year != null) {
            Text(
                text = year,
                style = mr(10f, 400),
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Reserve the metadata line so cards align even when a year is absent.
            Spacer(Modifier.height(13.dp))
        }
    }
}
