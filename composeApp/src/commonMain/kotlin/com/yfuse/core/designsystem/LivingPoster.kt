package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** A little taller than the old full-bleed reel, because the poster now begins below the header. */
fun livingPosterHeroHeight(
    viewportHeight: Dp,
    wideLayout: Boolean,
): Dp =
    if (wideLayout) {
        (viewportHeight * 0.70f).coerceIn(500.dp, 760.dp)
    } else {
        (viewportHeight * 0.76f).coerceIn(500.dp, 640.dp)
    }

/**
 * A quiet, blurred echo of the settled artwork behind the inset poster.
 *
 * The page ground already carries the extracted colour. This layer adds image-specific light
 * and shadow without turning body copy below the reel into part of the photograph.
 */
@Composable
fun LivingPosterAmbient(
    urls: List<String?>,
    modifier: Modifier = Modifier,
) {
    if (urls.none { !it.isNullOrBlank() }) return
    // Fade the blurred echo as well as the foreground artwork. Leaving this layer opaque made
    // its clipped lower edge visible after the main image had already dissolved into the page.
    Box(modifier.fadeIntoPage()) {
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

/** The continuous-corner, luminous-edge frame shared by both reels. */
fun Modifier.livingPosterFrame(): Modifier =
    this
        .shadow(Shadows.hero, AppShapes.sheet)
        .clip(AppShapes.sheet)
        .background(HeroInk)
        .border(1.dp, Color.White.copy(alpha = 0.18f), AppShapes.sheet)
