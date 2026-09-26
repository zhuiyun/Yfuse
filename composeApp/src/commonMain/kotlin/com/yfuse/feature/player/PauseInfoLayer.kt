package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.model.PlaybackChapter
import com.yfuse.core.util.currentEpochMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 暂停信息层 — what a settled pause is in the middle of: the chapter, the first names in the cast,
 * and when the rest ends at the current speed.
 *
 * Quiet on purpose: a slow fade, no movement, no live region. While it is up, the first touch
 * anywhere on the picture and Back only put it away — they do not also bring up the chrome or leave
 * the player — and the 继续播放 key, drawn above it, still resumes. Under 减弱动态效果 and 静息 the
 * fade is short and plain.
 *
 * [modifier] places the card; the touch catcher covers the whole parent.
 */
@Composable
internal fun PauseInfoLayer(
    shown: Boolean,
    playback: State<PlaybackState>,
    chapters: List<PlaybackChapter>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Names the player already has; the line is left out when there are none. */
    castNames: List<String> = emptyList(),
) {
    val info = if (shown) rememberPauseInfo(playback, chapters, castNames) else null
    val active = shown && info != null
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    PlatformBackHandler(enabled = active, onBack = onDismiss)
    if (active) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        latestOnDismiss()
                    }
                },
        )
    }
    // Kept for the fade-out, so the card does not empty itself on the way out.
    val lastInfo = remember { arrayOfNulls<PauseInfo>(1) }
    info?.let { lastInfo[0] = it }
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    AnimatedVisibility(
        visible = active,
        modifier = modifier,
        enter = fadeIn(Motion.tween(if (still) Motion.REDUCED_FADE else Motion.AMBIENT)),
        exit = fadeOut(Motion.tween(if (still) Motion.REDUCED_FADE else Motion.QUICK)),
        label = "pause-info",
    ) {
        lastInfo[0]?.let { PauseInfoCard(it) }
    }
}

@Composable
private fun PauseInfoCard(info: PauseInfo) {
    Column(
        Modifier
            .widthIn(max = PauseInfoMaxWidth)
            .glass(
                shape = GlassShapes.card,
                fill = Color.Black.copy(alpha = 0.44f),
                border = Color.White.copy(alpha = 0.14f),
            ).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        info.chapter?.let {
            Text(
                it,
                style = AppTypography.body.strong,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        info.cast?.let {
            Text(
                it,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        info.endsAt?.let {
            Text(
                it,
                style = AppTypography.caption.regular,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}

/**
 * The layer's content at the playhead, with 结束于 read against the wall clock again on the minute —
 * a paused film's end moves on with the clock even though the picture does not.
 */
@Composable
private fun rememberPauseInfo(
    playback: State<PlaybackState>,
    chapters: List<PlaybackChapter>,
    castNames: List<String>,
): PauseInfo? {
    var clock by remember { mutableStateOf(playerWallClock()) }
    var nowEpochMs by remember { mutableLongStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(PAUSE_INFO_MINUTE_MS - currentEpochMillis() % PAUSE_INFO_MINUTE_MS)
            nowEpochMs = currentEpochMillis()
            clock = playerWallClock()
        }
    }
    val live = playback.value
    return pauseInfo(
        chapterName = fileChapterNameAt(chapters, live.positionMs),
        castNames = castNames,
        endsAt =
            playbackEndsAtLabel(
                positionMs = live.positionMs,
                durationMs = live.durationMs,
                speed = live.speed,
                nowEpochMs = nowEpochMs,
                clock = clock,
            ),
    )
}

private val PauseInfoMaxWidth = 280.dp

private const val PAUSE_INFO_MINUTE_MS = 60_000L
