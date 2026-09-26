package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.util.currentEpochMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.yfuse.core.designsystem.ThemeText as Text

/** The device clock now: its offset from UTC and its 12/24-hour setting. */
internal expect fun playerWallClock(): PlayerWallClock

/**
 * 结束于 21:47, beside the duration it is counted from.
 *
 * [positionMs] is a reader, like the times beside the rail: a new sample arrives twice a second,
 * and only the minute this says ever changes. The zone and the 12/24-hour setting are read again
 * on the minute, which is also what moves a paused film's end on with the clock.
 */
@Composable
internal fun PlaybackEndsAt(
    positionMs: () -> Long,
    durationMs: Long,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    var clock by remember { mutableStateOf(playerWallClock()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(MINUTE_MS - currentEpochMillis() % MINUTE_MS)
            clock = playerWallClock()
        }
    }
    val latestPosition by rememberUpdatedState(positionMs)
    val latestDuration by rememberUpdatedState(durationMs)
    val latestSpeed by rememberUpdatedState(speed)
    val label by remember {
        derivedStateOf {
            playbackEndsAtLabel(
                positionMs = latestPosition(),
                durationMs = latestDuration,
                speed = latestSpeed,
                nowEpochMs = currentEpochMillis(),
                clock = clock,
            )
        }
    }
    label?.let {
        // A plain text node, so a screen reader reads it where it sits, after the duration.
        Text(
            it,
            style = AppTypography.caption.regular,
            color = Color.White.copy(alpha = 0.56f),
            maxLines = 1,
            modifier = modifier,
        )
    }
}

private const val MINUTE_MS = 60_000L
