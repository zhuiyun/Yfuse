package com.yfuse.feature.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalMotionTheme
import com.yfuse.core.designsystem.LocalSharedMediaTransitionController
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.MotionTheme

internal data class CinemaDetailArtwork(
    val key: MediaSharedElementKey,
    val urls: List<String>,
)

/** Choose the layout once per route, so a budget/theme change cannot detach a flying hero. */
@Composable
internal fun rememberCinemaDetailArtwork(
    serverId: String?,
    itemId: String,
): CinemaDetailArtwork? {
    val theme = LocalMotionTheme.current
    val continuous = rememberSaveable(itemId) { theme == MotionTheme.Cinema }
    if (!continuous) return null
    val controller = LocalSharedMediaTransitionController.current
    val key =
        remember(itemId, serverId) {
            controller?.activeKey?.takeIf { it.itemId == itemId && (serverId == null || serverId == it.serverId) }
                ?: MediaSharedElementKey(serverId, itemId)
        }
    return CinemaDetailArtwork(key, controller?.artworkUrls(key).orEmpty())
}

/** This node survives loading, content and error; the skeleton never owns a shared element. */
@Composable
internal fun CinemaDetailHero(
    artwork: CinemaDetailArtwork,
    urls: List<String?>,
    title: String,
    height: Dp,
    surface: Color,
    scroll: State<Float>,
    onResolvedUrl: (String) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(height).clearAndSetSemantics {}.graphicsLayer {
            val up = scroll.value.coerceAtLeast(0f)
            translationY = -up
            alpha = if (up >= size.height) 0f else 1f
        },
    ) {
        Hero(
            urls = urls,
            title = title,
            height = height,
            surfaceColor = surface,
            animationKey = "detail-hero-${artwork.key.itemId}",
            sharedKey = artwork.key,
            scroll = scroll,
            onResolvedUrl = onResolvedUrl,
            standInUrls = artwork.urls,
            continuous = true,
        )
    }
}

/** Keep the tapped image underneath until the actual backdrop has decoded and faded in. */
@Composable
internal fun CinemaHeroImage(
    urls: List<String?>,
    standInUrls: List<String>,
    title: String,
    modifier: Modifier,
    onResolvedUrl: (String) -> Unit,
) {
    var ready by remember(urls) { mutableStateOf(false) }
    val still = LocalAccessibilityOptions.current.reduceMotion
    val reveal by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = Motion.tween(if (still) 0 else Motion.POSTER_FADE),
        label = "cinemaHeroBackdrop",
    )
    Box(modifier) {
        if (reveal < 1f && standInUrls.isNotEmpty()) {
            FallbackImage(standInUrls, null, Modifier.fillMaxSize(), revealDurationMillis = 0)
        }
        if (urls.any { !it.isNullOrBlank() } || standInUrls.isEmpty()) {
            FallbackImage(
                urls,
                title,
                Modifier.fillMaxSize().graphicsLayer { alpha = if (standInUrls.isEmpty()) 1f else reveal },
                revealDurationMillis = 0,
                onResolvedUrl = {
                    ready = true
                    onResolvedUrl(it)
                },
            )
        }
    }
}
