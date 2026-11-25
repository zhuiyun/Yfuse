package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared geometry for the 首页 and 媒体库 living-poster reels.
 *
 * The asymmetric pager padding is intentional: the settled poster owns most of the viewport,
 * while the next poster remains visible as a swipe affordance. Both root screens use these
 * values so a tab switch never changes the reel's silhouette.
 */
object LivingPosterDefaults {
    val LEADING_INSET = 18.dp
    val TRAILING_PEEK = 58.dp
    val PAGE_SPACING = 12.dp
    val CAPTION_BOTTOM = HeroCaptionClearance
    val INDICATOR_BOTTOM = 16.dp
}

private val CaptionFeather = 44.dp

/** Draw the detail sheet's surface blend over the reel's foot, below all caption controls. */
internal fun Modifier.livingPosterPageBlend(pageColor: State<Color>): Modifier =
    drawBehind {
        drawRect(heroPanelBrush(pageColor.value, this, height = size.height.toDp()))
    }

/** Keep caption clearance above the lower dissolve without painting a dark horizontal stripe. */
internal fun Modifier.livingPosterCaptionScrim(): Modifier =
    padding(bottom = LivingPosterDefaults.CAPTION_BOTTOM)

/** Compact carousel metadata shared by TMDB and Emby-backed titles. */
fun heroDurationLabel(minutes: Int?): String? =
    minutes
        ?.takeIf { it > 0 }
        ?.let {
            val hours = it / 60
            val remainder = it % 60
            when {
                hours == 0 -> "${remainder}分钟"
                remainder == 0 -> "${hours}小时"
                else -> "${hours}小时${remainder}分钟"
            }
        }

fun heroMediaTypeLabel(type: String): String =
    when (type.lowercase()) {
        "movie" -> "电影"
        "tv", "series" -> "剧集"
        "episode" -> "单集"
        else -> type.ifBlank { "视频" }
    }

/**
 * Shared reel height for 首页 and 媒体库.
 *
 * Keep the living poster cinematic, but expose a little more of the first content rail on
 * compact phones. Both tabs still resolve from the same token so switching tabs never makes
 * the carousel jump in height.
 *
 * The 480dp floor is for a phone held upright. A phone on its side is only 360–430dp tall, and
 * there the floor made the reel taller than the screen: its 播放 and 收藏 row sat under the
 * floating dock, or below the fold altogether. On a viewport that short the floor gives way to
 * [SHORT_VIEWPORT_HERO_SHARE] of it, which keeps that row above the dock.
 */
fun livingPosterHeroHeight(
    viewportHeight: Dp,
    wideLayout: Boolean,
): Dp {
    val floor = minOf(480.dp, viewportHeight * SHORT_VIEWPORT_HERO_SHARE)
    return if (wideLayout) {
        (viewportHeight * 0.67f).coerceIn(floor, 730.dp)
    } else {
        (viewportHeight * 0.72f).coerceIn(floor, 610.dp)
    }
}

/**
 * The most of a short viewport the reel may take. The caption's action row sits
 * [LivingPosterDefaults.CAPTION_BOTTOM] above the reel's foot, about as high as the floating
 * dock reaches; the tenth left below the reel is the margin between the two.
 */
private const val SHORT_VIEWPORT_HERO_SHARE = 0.9f

/**
 * A quiet, blurred echo of the settled artwork behind the inset poster.
 *
 * The page ground already carries the extracted colour. This layer adds image-specific light
 * and shadow without turning body copy below the reel into part of the photograph.
 */
@Composable
fun LivingPosterAmbient(
    urls: List<String?>,
    fadeHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (urls.none { !it.isNullOrBlank() }) return
    // Fade the blurred echo as well as the foreground artwork. Leaving this layer opaque made
    // its clipped lower edge visible after the main image had already dissolved into the page.
    Box(modifier.fadeIntoPage(fadeHeight)) {
        FallbackImage(
            urls = urls,
            contentDescription = null,
            progressive = false,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.20f
                        scaleX = 1.14f
                        scaleY = 1.14f
                        val radius = 34.dp.toPx()
                        renderEffect = BlurEffect(radius, radius)
                    },
        )
    }
}

/**
 * Apply the dissolve and frame-synchronized smootherstep color bridge inside the artwork plane,
 * behind the hero caption and controls (matching DetailHero's seamless transition).
 */
@Composable
internal fun LivingPosterArtworkPlane(
    artwork: LivingPosterArtwork,
    framed: Boolean,
    pageColor: State<Color>? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val frame =
        if (framed) {
            Modifier.clip(AppShapes.sheet).border(1.dp, Color.White.copy(alpha = 0.18f), AppShapes.sheet)
        } else {
            Modifier.clipToBounds()
        }
    Box(Modifier.fillMaxSize().then(frame)) {
        Box(Modifier.matchParentSize().fadeIntoPage(artwork.fadeHeight)) {
            content()
            Box(Modifier.matchParentSize().background(heroTopScrim()))
        }
        if (pageColor != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(artwork.fadeHeight)
                    .drawBehind {
                        drawRect(heroPanelBrush(pageColor.value, this, height = artwork.fadeHeight))
                    },
            )
        }
    }
}
