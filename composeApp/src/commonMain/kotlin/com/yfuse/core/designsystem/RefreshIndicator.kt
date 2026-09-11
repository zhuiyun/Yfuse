package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ThemeIcon as Icon

/** Same glass/orb language as inline loading; the pull distance is consumed by the layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshIndicator(
    state: PullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown by remember(state, refreshing) { derivedStateOf { refreshing || state.distanceFraction > 0f } }
    if (!shown) return
    val palette = LocalPalette.current
    val reduced = LocalAccessibilityOptions.current.reduceMotion
    Box(
        modifier
            .statusBarsPadding()
            .graphicsLayer {
                val fraction = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
                translationY = (-48f + 64f * fraction).dp.toPx()
                alpha = fraction
                scaleX = if (reduced) 1f else 0.8f + 0.2f * fraction
                scaleY = scaleX
            }.size(40.dp)
            .glass(GlassShapes.chip, palette.card, palette.border)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        InlineLoadingContent(loading = refreshing, slotSize = 20.dp, color = LocalAccentColors.current.accent) {
            Icon(
                AppIcons.Refresh,
                contentDescription = "下拉刷新",
                tint = LocalAccentColors.current.accent,
                modifier =
                    Modifier.size(20.dp).graphicsLayer {
                        rotationZ = if (reduced) 0f else state.distanceFraction.coerceIn(0f, 1f) * 180f
                    },
            )
        }
    }
}
