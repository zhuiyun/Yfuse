package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
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

/**
 * The last pull strength the layer drew, held outside the snapshot system on purpose: it is written
 * from the draw phase, where a state write would invalidate the very frame that made it.
 */
private class PullStrength {
    var value = 0f
}

/** Same glass/orb language as inline loading; the pull distance is consumed by the layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshIndicator(
    state: PullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown by remember(state, refreshing) { derivedStateOf { refreshing || state.distanceFraction > 0f } }
    val palette = LocalPalette.current
    val reduced = LocalAccessibilityOptions.current.reduceMotion
    // The indicator was added to and removed from composition outright. Pulling was smooth because
    // the distance drove the layer, but finishing a refresh was not: the gesture that had been
    // driving it is long gone, so the orb was at full strength in one frame and absent in the next.
    val presence =
        animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = Motion.settle(reduced),
            label = "refreshIndicatorPresence",
        )
    // Composed until its own fade has finished, rather than for exactly as long as [shown]. Derived
    // from the same inputs rather than from [shown] itself — that one is rebuilt whenever
    // `refreshing` changes, and reading a previous one here would leave this stuck on a stale
    // answer — and read as a boolean, so the fade in between costs no recomposition.
    val present by remember(state, refreshing, presence) {
        derivedStateOf { refreshing || state.distanceFraction > 0f || presence.value > 0f }
    }
    if (!present) return
    // Where the pull had got to when it stopped driving, so the exit has something to fade from.
    val reached = remember(state) { PullStrength() }
    Box(
        modifier
            .statusBarsPadding()
            .graphicsLayer {
                val pull = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
                if (pull > 0f) reached.value = pull
                val fraction = (if (pull > 0f) pull else reached.value) * presence.value.coerceIn(0f, 1f)
                translationY = (-48f + 64f * fraction).dp.toPx()
                alpha = fraction
                scaleX = if (reduced) 1f else 0.8f + 0.2f * fraction
                scaleY = scaleX
            }.size(40.dp)
            .lightOnChange(refreshing, if (refreshing) LightEffect.Node else LightEffect.Edge)
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
