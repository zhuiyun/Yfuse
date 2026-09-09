package com.yfuse.feature.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.SKELETON_PHASE_STEP_MS
import com.yfuse.core.designsystem.SkeletonBlock
import com.yfuse.core.designsystem.skeletonFill
import com.yfuse.core.designsystem.skeletonSweep

/**
 * The page before its detail arrives, in the shape of the page it becomes.
 *
 * The hero is not a flat grey block: it carries a slow bloom of the page's own accent and
 * pearl tints, so the top of the screen already has the atmosphere the artwork will bring.
 * Every block below breathes on the shared skeleton pulse, phased top to bottom, and one
 * diagonal sweep crosses the whole page — the same loading language as the search and
 * library skeletons.
 */
@Composable
internal fun DetailSkeleton(heroHeight: Dp) {
    Column(Modifier.fillMaxSize().skeletonSweep()) {
        // A loading placeholder can disappear before Compose's shared-transition overlay has
        // received its first bounds. Making that short-lived node a shared element leaves the
        // overlay trying to draw a detached node and crashes with "current bounds not set yet".
        // The real hero below remains shared once the detail has loaded.
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .heroBloom(),
        )
        Column(
            Modifier
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(
                    Modifier.width(96.dp).height(142.dp),
                    shape = GlassShapes.poster,
                )
                Column(
                    Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(
                        Modifier.fillMaxWidth(0.72f).height(18.dp),
                        shape = GlassShapes.thumb,
                        phaseMs = SKELETON_PHASE_STEP_MS,
                    )
                    SkeletonBlock(
                        Modifier.fillMaxWidth(0.46f).height(11.dp),
                        shape = GlassShapes.thumb,
                        phaseMs = SKELETON_PHASE_STEP_MS * 2,
                    )
                    SkeletonBlock(
                        Modifier.width(64.dp).height(11.dp),
                        shape = GlassShapes.thumb,
                        phaseMs = SKELETON_PHASE_STEP_MS * 3,
                    )
                }
            }
            SkeletonBlock(
                Modifier.fillMaxWidth().height(48.dp),
                shape = GlassShapes.card,
                phaseMs = SKELETON_PHASE_STEP_MS * 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { index ->
                    SkeletonBlock(
                        Modifier.weight(1f).height(36.dp),
                        shape = GlassShapes.chip,
                        phaseMs = SKELETON_PHASE_STEP_MS * (3 + index),
                    )
                }
            }
            SkeletonBlock(
                Modifier.fillMaxWidth().height(12.dp),
                shape = GlassShapes.thumb,
                phaseMs = SKELETON_PHASE_STEP_MS * 5,
            )
            SkeletonBlock(
                Modifier.fillMaxWidth(0.86f).height(12.dp),
                shape = GlassShapes.thumb,
                phaseMs = SKELETON_PHASE_STEP_MS * 6,
            )
        }
    }
}

/**
 * Three soft tints drifting slowly over the placeholder hero. Radial gradients rather than
 * a blurred layer, so it costs nothing on devices without RenderEffect and looks the same on
 * all of them. Reduced motion holds the midpoint of the drift.
 */
@Composable
private fun Modifier.heroBloom(): Modifier {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val transition = rememberInfiniteTransition(label = "detailBloom")
    val animatedDrift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BLOOM_DRIFT_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "detailBloomDrift",
    )
    val drift = if (reduceMotion) 0.5f else animatedDrift
    val strength = if (palette.isDark) 1f else 0.6f
    val fill = skeletonFill()
    return drawBehind {
        drawRect(fill)
        val w = size.width
        val h = size.height
        val radius = w * 0.62f
        drawBloom(
            color = accent.copy(alpha = 0.26f * strength),
            center = Offset(w * (0.62f + 0.12f * drift), h * (0.55f - 0.1f * drift)),
            radius = radius,
        )
        drawBloom(
            color = BloomRose.copy(alpha = 0.16f * strength),
            center = Offset(w * (0.22f + 0.08f * drift), h * (0.32f + 0.14f * drift)),
            radius = radius * 0.9f,
        )
        drawBloom(
            color = BloomAmber.copy(alpha = 0.10f * strength),
            center = Offset(w * (0.5f - 0.1f * drift), h * (0.05f + 0.1f * drift)),
            radius = radius * 0.7f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBloom(
    color: Color,
    center: Offset,
    radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(0f to color, 1f to Color.Transparent, center = center, radius = radius),
        radius = radius,
        center = center,
    )
}

private val BloomRose = Color(0xFFE5A4EE)
private val BloomAmber = Color(0xFFD9852F)
private const val BLOOM_DRIFT_MS = 6_000
