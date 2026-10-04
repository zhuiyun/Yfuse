package com.yfuse.feature.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.shared.R
import kotlin.math.roundToInt

/** A variant's background and foreground drawn as adaptive-icon vectors; null for the bitmap ones. */
private fun AppIconVariant.vectorLayers(): Pair<Int, Int>? =
    when (this) {
        AppIconVariant.Prism -> R.drawable.ic_prism_background to R.drawable.ic_prism_foreground
        AppIconVariant.WaterOverFire ->
            R.drawable.ic_water_over_fire_background to R.drawable.ic_water_over_fire_foreground
        AppIconVariant.Overprint -> R.drawable.ic_overprint_background to R.drawable.ic_overprint_foreground
        AppIconVariant.Danmaku -> R.drawable.ic_danmaku_background to R.drawable.ic_danmaku_foreground
        AppIconVariant.LiquidGlass -> R.drawable.ic_liquid_glass_background to R.drawable.ic_liquid_glass_foreground
        else -> null
    }

@Composable
internal actual fun AppIconPreview(
    variant: AppIconVariant,
    modifier: Modifier,
) {
    val layers = variant.vectorLayers()
    if (layers != null) {
        VectorIconPreview(background = layers.first, foreground = layers.second, modifier = modifier)
        return
    }
    val background =
        when (variant) {
            AppIconVariant.Graphite -> Color(0xFF1B2333)
            AppIconVariant.AuroraDark -> Color(0xFF000008)
            AppIconVariant.AuroraLight -> Color(0xFFF4F8FF)
            else -> Color(0xFFFCFBFC)
        }
    val artwork =
        when (variant) {
            AppIconVariant.CloudPlayer -> R.drawable.cloud_player_logo
            AppIconVariant.AuroraDark -> R.drawable.yfuse_aurora_dark
            AppIconVariant.AuroraLight -> R.drawable.yfuse_aurora_light
            else -> R.drawable.yfuse_mark
        }
    Box(
        modifier
            .clip(GlassShapes.appIcon)
            .background(background)
            .padding(
                when (variant) {
                    AppIconVariant.AuroraDark, AppIconVariant.AuroraLight -> 0.dp
                    AppIconVariant.CloudPlayer -> 4.dp
                    else -> 6.dp
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(artwork),
            contentDescription = null,
            modifier =
                Modifier.fillMaxSize().then(
                    if (variant == AppIconVariant.AuroraDark || variant == AppIconVariant.AuroraLight) {
                        // Match the launcher's visible crop; the 1254px artwork is centered at (634, 597).
                        Modifier.graphicsLayer {
                            scaleX = 1.44f
                            scaleY = 1.44f
                            translationX = -size.width * 0.008f
                            translationY = size.height * 0.034f
                        }
                    } else {
                        Modifier
                    },
                ),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * A vector launcher icon as a launcher shows it. Both layers span the whole 108dp adaptive-icon
 * canvas, of which a launcher shows the middle 72dp, so they are laid out 108 / 72 larger than
 * the slot, centred and clipped to it; laid out rather than scaled, so the vectors draw sharp.
 */
@Composable
private fun VectorIconPreview(
    background: Int,
    foreground: Int,
    modifier: Modifier,
) {
    Box(modifier.clip(GlassShapes.appIcon)) {
        Image(painter = painterResource(background), contentDescription = null, modifier = LauncherCrop)
        Image(painter = painterResource(foreground), contentDescription = null, modifier = LauncherCrop)
    }
}

private val LauncherCrop =
    Modifier.layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = (constraints.maxWidth * 1.5f).roundToInt()
        val height = (constraints.maxHeight * 1.5f).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
        }
    }
