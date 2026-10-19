package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CinemaMotion
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.cinemaMotion
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun Modifier.cinemaOpeningArtwork(
    identity: Any,
    visible: Boolean,
    allowed: Boolean,
): Modifier {
    val enabled = cinemaMotion() && allowed
    val progress = remember { Animatable(1f) }
    LaunchedEffect(identity, visible, enabled) {
        if (enabled && visible) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(CinemaMotion.PLAY, easing = Motion.Curve))
        } else {
            progress.snapTo(1f)
        }
    }
    if (!enabled) return this
    return graphicsLayer {
        val scale = if (enabled) 1f + (1f - progress.value) * 0.025f else 1f
        scaleX = scale
        scaleY = scale
    }
}

internal data class CinemaEpisodeTitle(
    val identity: Any,
    val title: String,
)

internal data class CinemaEpisodeChange(
    val from: CinemaEpisodeTitle,
    val to: CinemaEpisodeTitle,
)

/** Metadata refreshes, engine retries and same-title episodes must not be confused with each other. */
internal fun cinemaEpisodeChange(
    previous: CinemaEpisodeTitle?,
    next: CinemaEpisodeTitle?,
): CinemaEpisodeChange? =
    if (previous != null &&
        next != null &&
        previous.identity != next.identity
    ) {
        CinemaEpisodeChange(previous, next)
    } else {
        null
    }

/** Informational overlay only. It never gates preparation, input, readiness or queue selection. */
@Composable
internal fun CinemaEpisodeOverlay(
    identity: Any?,
    title: String,
    awaitingPicture: Boolean,
    suppressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled = cinemaMotion() && !suppressed
    val item = identity?.let { CinemaEpisodeTitle(it, title) }
    var previous by remember { mutableStateOf(item) }
    var change by remember { mutableStateOf<CinemaEpisodeChange?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(identity, enabled) {
        val next = cinemaEpisodeChange(previous, item)
        previous = item
        change = next.takeIf { enabled }
    }
    // Readiness or a terminal state cancels the animation and clears the card. Rebuffering
    // cannot replay it because the identity has already been observed.
    LaunchedEffect(awaitingPicture, enabled, change) {
        if (awaitingPicture && enabled && change != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(CinemaMotion.EPISODE, easing = Motion.Curve))
        } else {
            change = null
            progress.snapTo(1f)
        }
    }
    val shown = change
    if (!enabled || !awaitingPicture || shown == null) return
    Box(modifier.widthIn(max = 420.dp).fillMaxWidth().clearAndSetSemantics {}) {
        CinemaEpisodeCard(
            title = shown.from.title,
            label = "上一项",
            modifier =
                Modifier.graphicsLayer {
                    alpha = 1f - progress.value
                    translationY = -28.dp.toPx() * progress.value
                    rotationZ = -2f * progress.value
                },
        )
        CinemaEpisodeCard(
            title = shown.to.title,
            label = "正在切换",
            modifier =
                Modifier.graphicsLayer {
                    alpha = progress.value
                    translationY = 28.dp.toPx() * (1f - progress.value)
                    rotationZ = 2f * (1f - progress.value)
                },
        )
    }
}

@Composable
private fun CinemaEpisodeCard(
    title: String,
    label: String,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f), AppShapes.card)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = AppTypography.caption.regular, color = Color.White.copy(alpha = 0.7f))
            Text(
                title,
                style = AppTypography.body.medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
