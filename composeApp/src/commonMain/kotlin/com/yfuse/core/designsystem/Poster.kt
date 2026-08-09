package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.decode.DataSource

@Composable
fun FallbackImage(
    urls: List<String?>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    progressive: Boolean = true,
) {
    val candidates = remember(urls) { urls.filterNotNull().filter { it.isNotBlank() }.distinct() }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var loaded by remember(candidates, candidateIndex) { mutableStateOf(false) }
    var instant by remember(candidates, candidateIndex) { mutableStateOf(false) }
    val animate = progressive && !LocalAccessibilityOptions.current.reduceMotion && !instant
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
                    val remaining = 1f - settle
                    val scale = 1f + (Motion.IMAGE_SCALE_FROM - 1f) * remaining
                    scaleX = scale
                    scaleY = scale
                    alpha = settle
                    if (remaining > 0.01f) {
                        val radius = Motion.imageBlur.toPx() * remaining
                        renderEffect = BlurEffect(radius, radius)
                    }
                },
                onSuccess = { success ->
                    if (success.result.dataSource == DataSource.MEMORY_CACHE) instant = true
                    loaded = true
                },
                onError = {
                    if (candidateIndex == requestIndex && requestIndex < candidates.lastIndex) {
                        candidateIndex = requestIndex + 1
                    }
                },
            )
        }
    }
}

@Composable
fun Poster(
    url: String?,
    fallbackUrl: String? = null,
    fallbackUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    title: String? = null,
    year: String? = null,
    shape: Shape = GlassShapes.poster,
    progress: Float? = null,
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
            .background(
                if (palette.isDark) {
                    cssLinearGradient(180f, 0f to Color(0xFF283040), 1f to Color(0xFF1D2430))
                } else {
                    cssLinearGradient(180f, 0f to Color(0xFFE4E9F1), 1f to Color(0xFFD3DAE5))
                },
            )
            .let {
                when {
                    onClick != null || onLongClick != null -> it.pressable(
                        pressedScale = 0.975f,
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

        progress?.takeIf { it > 0f }?.let { raw ->
            val value = raw.coerceIn(0f, 1f)
            // A full rail makes the amount watched readable before the eye finds the accent.
            // Previously only the filled 3dp segment existed, so a 15% resume point looked
            // like a decorative blue underline rather than playback state.
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
                    .fillMaxWidth(value)
                    .height(4.dp)
                    .background(PrimaryGradient),
            )
        }
    }
}

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
    val resumable = progress != null
    Column(
        modifier.let { base ->
            if (onClick != null) {
                base.pressable(pressedScale = 0.975f, onClick = onClick)
            } else {
                base
            }
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
        Spacer(Modifier.height(if (resumable) 8.dp else 7.dp))
        Text(
            text = title,
            style = sc(if (resumable) 12.5f else 12f, if (resumable) 700 else 600),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        if (year != null) {
            Text(
                text = year,
                style = mr(10f, if (resumable) 500 else 400),
                color = if (resumable) palette.sub else palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(15.dp))
        }
    }
}
