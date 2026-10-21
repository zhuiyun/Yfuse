package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlin.math.roundToInt

internal const val DETAIL_BACK_MS = 280
internal const val DETAIL_BACK_SCALE = 0.96f
internal const val DETAIL_BACK_PARALLAX = 0.04f
internal val DetailBackCorner = 24.dp
internal val LocalDetailSideReturn = staticCompositionLocalOf { false }

/** The outgoing route still determines the return style after it has left the stack. */
internal class DetailReturnRoutes {
    private var keys = emptyList<String>()
    private var details = emptySet<String>()
    private var lastPopWasDetail by mutableStateOf(false)

    fun isDetailReturn(next: List<String>): Boolean =
        if (next == keys) {
            lastPopWasDetail
        } else {
            next.size < keys.size && keys.take(next.size) == next && keys.last() in details
        }

    fun update(
        next: List<String>,
        detailKeys: Set<String>,
    ) {
        lastPopWasDetail = isDetailReturn(next)
        keys = next.toList()
        details = detailKeys
    }
}

/** A single opaque surface; both system edges use the app's logical back direction. */
internal fun detailBackCard(
    from: ZoomCard,
    page: Size,
    toward: Float,
    fraction: Float,
    cornerPx: Float,
): ZoomCard {
    val targetSize = Size(page.width * DETAIL_BACK_SCALE, page.height * DETAIL_BACK_SCALE)
    val target =
        Rect(
            Offset(
                toward * page.width + (page.width - targetSize.width) / 2f,
                (page.height - targetSize.height) / 2f,
            ),
            targetSize,
        )
    return zoomBackFlightCard(from, target, page, fraction.coerceIn(0f, 1f), cornerPx, landing = false)
}

internal fun detailBackReleaseDuration(progress: Float): Int =
    (180 + 60 * (1f - progress.coerceIn(0f, 1f))).roundToInt()

/** Hermite continuation: carry release velocity into a bounded, monotone finish at rest. */
internal fun detailBackReleaseEasing(
    velocity: Float,
    durationMs: Int,
): Easing {
    val slope = (velocity * durationMs / 1_000f).takeIf { it.isFinite() }?.coerceIn(0f, 3f) ?: 0f
    return Easing { fraction ->
        val t = fraction.coerceIn(0f, 1f)
        t * t * (3f - 2f * t) + slope * t * (1f - t) * (1f - t)
    }
}

/** The same path for a toolbar/key return, or the platform fallback when no custom host exists. */
internal fun detailBackTransform(
    predictive: Boolean = false,
    embellished: Boolean = true,
): ContentTransform {
    val easing = if (predictive) LinearEasing else Motion.Curve
    val slide =
        slideOutHorizontally(tween(DETAIL_BACK_MS, easing = easing)) { (BACK_NAVIGATION_TOWARD * it).roundToInt() }
    return ContentTransform(
        targetContentEnter =
            slideInHorizontally(tween(DETAIL_BACK_MS, easing = easing)) {
                if (embellished) (-BACK_NAVIGATION_TOWARD * it * DETAIL_BACK_PARALLAX).roundToInt() else 0
            },
        initialContentExit =
            if (embellished) {
                slide + scaleOut(tween(DETAIL_BACK_MS, easing = easing), targetScale = DETAIL_BACK_SCALE)
            } else {
                slide
            },
        targetContentZIndex = -1f,
        sizeTransform = null,
    )
}

/** Custom gesture layers already own clipping; this supplies corners only for ordinary returns. */
@Composable
internal fun Modifier.detailBackCorners(
    enabled: Boolean,
    customGesture: () -> Boolean,
): Modifier {
    if (!enabled) return this
    val progress =
        LocalNavAnimatedContentScope.current.transition.animateFloat(
            transitionSpec = { tween(DETAIL_BACK_MS, easing = Motion.Curve) },
            label = "detailBackCorners",
        ) { if (it == EnterExitState.PostExit) 1f else 0f }
    return graphicsLayer {
        val fraction = if (customGesture()) 0f else progress.value
        shape = RoundedCornerShape(DetailBackCorner * fraction)
        clip = fraction > 0f
    }
}
