package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Continuous liquid-glass capsule grouping 2+ related toolbar icon actions (e.g. cast + more,
 * search + filter, video tools) into one unified rim instead of fragmented circles.
 */
@Composable
fun GlassActionCluster(
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
    horizontalPadding: Dp = 4.dp,
    spacing: Dp = 2.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val fill =
        if (onArtwork) {
            HeroInk.copy(alpha = 0.34f)
        } else {
            palette.glassStrong
        }
    val border =
        if (onArtwork) {
            Color.White.copy(alpha = 0.28f)
        } else {
            palette.tabbarBorder
        }
    val over = if (onArtwork) HeroInk else palette.background
    Row(
        modifier =
            modifier
                .shadow(GlassLift.control, CircleShape)
                .liquidGlass(
                    shape = CircleShape,
                    fill = fill,
                    border = border,
                    over = over,
                    sheen = 0.75f,
                ).padding(horizontal = horizontalPadding, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
