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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yfuse.R
import com.yfuse.core.designsystem.GlassShapes

@Composable
internal actual fun AppIconPreview(
    variant: AppIconVariant,
    modifier: Modifier,
) {
    val background =
        when (variant) {
            AppIconVariant.Default -> Color(0xFFFCFBFC)
            AppIconVariant.Graphite -> Color(0xFF1B2333)
            AppIconVariant.CloudPlayer -> Color(0xFFFCFBFC)
            AppIconVariant.AuroraDark -> Color(0xFF000008)
            AppIconVariant.AuroraLight -> Color(0xFFF4F8FF)
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
