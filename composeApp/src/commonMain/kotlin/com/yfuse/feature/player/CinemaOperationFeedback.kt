package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CinemaConnection
import com.yfuse.core.designsystem.CinemaMotion
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.cinemaChangeProgress
import com.yfuse.core.designsystem.ThemeText as Text

internal fun cinemaCastConnection(state: CastState): CinemaConnection =
    when {
        state.discovering || state.status == CastPlaybackStatus.Connecting -> CinemaConnection.Checking
        state.error != null ||
            state.status == CastPlaybackStatus.Disconnected ||
            state.status == CastPlaybackStatus.Error -> CinemaConnection.Failed
        state.hasActiveSession -> CinemaConnection.Connected
        else -> CinemaConnection.Idle
    }

@Composable
internal fun CinemaSpeedReadout(
    gear: Int,
    held: Boolean,
) {
    val progress = cinemaChangeProgress(gear, CinemaMotion.GEAR, enabled = held)
    Box(
        Modifier.width(100.dp).drawWithContent {
            drawContent()
            val phase = progress.value
            if (held && phase < 1f) {
                repeat(2) { index ->
                    val y = size.height * (0.25f + index * 0.5f)
                    val x = size.width * (0.6f + phase * 0.28f)
                    drawLine(
                        Color.White.copy(alpha = (1f - phase) * 0.7f),
                        Offset(x, y),
                        Offset(x + 10.dp.toPx(), y),
                        1.dp.toPx(),
                        StrokeCap.Round,
                    )
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = gear,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (
                    (
                        slideInHorizontally(tween(CinemaMotion.GEAR, easing = Motion.Curve)) { it * direction / 2 } +
                            fadeIn(tween(CinemaMotion.GEAR, easing = Motion.Curve))
                    ) togetherWith
                        (
                            slideOutHorizontally(tween(CinemaMotion.GEAR, easing = Motion.Curve)) {
                                -it * direction / 2
                            } +
                                fadeOut(tween(CinemaMotion.GEAR, easing = Motion.Curve))
                        )
                ).using(null)
            },
            label = "cinema-speed-gear",
        ) { shown ->
            Text(
                speedBoostLabel(SPEED_BOOST_GEARS[shown.coerceIn(SPEED_BOOST_GEARS.indices)]),
                style = AppTypography.body.strong,
                color = Color.White,
            )
        }
    }
}
