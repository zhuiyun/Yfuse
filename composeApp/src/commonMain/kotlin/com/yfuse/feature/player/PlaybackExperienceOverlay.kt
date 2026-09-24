package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDelayedBusy
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * One line of playback status in two parts.
 *
 * [announcement] is what the line means, in words that hold still — the only part a screen
 * reader is given, once as the line arrives and again when its meaning changes. [text] is what
 * is drawn, and may carry figures that move while the meaning does not: 「已缓冲 N 秒」, a
 * measured rate. Read out, those would be a new sentence every tick.
 */
@Immutable
internal data class PlaybackStatusLine(
    val announcement: String,
    val text: String = announcement,
)

/**
 * [line], except that a change of meaning has to hold for [Motion.BUSY_MIN_VISIBLE] before it
 * replaces the one on screen: a rate estimate hovering around the source's bitrate must not flip
 * the chip between two sentences every second. Within one meaning the figures keep updating.
 */
@Composable
private fun rememberSteadyStatus(line: PlaybackStatusLine): PlaybackStatusLine {
    var steady by remember { mutableStateOf(line.announcement) }
    // A plain holder, like the chrome's retained labels: the last line that meant what is shown.
    val lastSteady = remember { arrayOf(line) }
    LaunchedEffect(line.announcement) {
        if (line.announcement != steady) {
            delay(Motion.BUSY_MIN_VISIBLE.toLong())
            steady = line.announcement
        }
    }
    if (line.announcement == steady) lastSteady[0] = line
    return lastSteady[0]
}

/**
 * Keeps artwork on screen until a replacement engine has produced a verified video frame.
 *
 * [message] is a reader rather than a line because the copy it produces is derived from the
 * live timeline — buffer seconds, recovery state, which episode is being joined. Built at the
 * call site it would be rebuilt on every position tick whether or not this overlay is even up;
 * invoked here it is only read where it is drawn.
 */
@Composable
internal fun PlaybackContinuityOverlay(
    artworkUrls: List<String?>,
    title: String,
    visible: Boolean,
    message: () -> PlaybackStatusLine,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var statusVisible by remember(visible, title) { mutableStateOf(false) }
    LaunchedEffect(visible, title) {
        statusVisible = false
        if (visible) {
            delay(CONTINUITY_STATUS_DELAY_MS)
            statusVisible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.tween(if (reduceMotion) 0 else Motion.CONTINUITY_ENTER)),
        exit = fadeOut(Motion.tween(if (reduceMotion) 0 else Motion.CONTINUITY_EXIT)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (artworkUrls.any { !it.isNullOrBlank() }) {
                FallbackImage(
                    urls = artworkUrls,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    progressive = false,
                    alphaOnly = true,
                    // The artwork is held back from full brightness so the chip stays readable
                    // over it. Drawn into the picture's own node rather than stacked as a third
                    // full-screen Box, it is one fewer layer to measure, place and composite on
                    // an overlay that covers the whole screen during every handover.
                    modifier =
                        Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            drawRect(Color.Black, alpha = CONTINUITY_ARTWORK_DIM)
                        },
                )
            }
            // The chip is held back for [CONTINUITY_STATUS_DELAY_MS] so a fast handover never
            // flashes an explanation nobody needed. Once that wait is over it has earned a fade
            // of its own — appearing instantly over a still frame reads as a glitch in the frame.
            AnimatedVisibility(
                visible = statusVisible,
                enter = fadeIn(Motion.tween(if (reduceMotion) 0 else Motion.STATE_HANDOFF)),
                exit = fadeOut(Motion.tween(if (reduceMotion) 0 else Motion.STATE_HANDOFF)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
            ) {
                val line = rememberSteadyStatus(message())
                Row(
                    Modifier
                        .glass(
                            shape = AppShapes.pill,
                            fill = Color.Black.copy(alpha = 0.58f),
                            border = Color.White.copy(alpha = 0.20f),
                        ).statusSemantics(line)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbProgress(size = 15.dp, color = Color.White)
                    Text(line.text, style = AppTypography.caption.medium, color = Color.White)
                }
            }
        }
    }
}

/** Covers a long-paused HDR/static frame so an unattended OLED does not keep burning it in. */
@Composable
internal fun OledPauseProtectionOverlay(
    visible: Boolean,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.tween(if (reduceMotion) 0 else Motion.OLED_PROTECTION)),
        exit = fadeOut(Motion.tween(if (reduceMotion) 0 else Motion.OLED_PROTECTION)),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .pressable(
                    haptic = HapticSignal.Confirm,
                    onClickLabel = "继续播放",
                    onClick = onResume,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "已进入屏幕保护 · 点击继续",
                style = AppTypography.body.medium,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
    }
}

/**
 * Names a rebuffer/reconnect state without replacing the last good video frame.
 *
 * [message] is a reader for the same reason as [PlaybackContinuityOverlay]'s: 「已缓冲 N 秒」
 * changes twice a second, and formatting it at the call site put a fresh string on the heap
 * every tick of every playback, chip on screen or not.
 *
 * It waits as long as the transport key does before saying anything, so the short stall every
 * seek makes shows nothing in either place; a longer one shows both, together.
 */
@Composable
internal fun PlaybackStatusChip(
    visible: Boolean,
    message: () -> PlaybackStatusLine,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val shown = rememberDelayedBusy(visible, showAfterMillis = BUFFERING_INDICATOR_DELAY_MS.toInt())
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(Motion.tween(if (reduceMotion) 0 else Motion.CONTINUITY_ENTER)),
        exit = fadeOut(Motion.tween(if (reduceMotion) 0 else Motion.CONTINUITY_EXIT)),
        modifier = modifier,
    ) {
        val line = rememberSteadyStatus(message())
        Row(
            Modifier
                .glass(
                    shape = AppShapes.pill,
                    fill = Color.Black.copy(alpha = 0.64f),
                    border = Color.White.copy(alpha = 0.20f),
                ).statusSemantics(line)
                .padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbProgress(size = 14.dp, color = Color.White)
            // A new meaning crosses over the old one; new figures under the same meaning just update.
            AnimatedContent(
                targetState = line,
                contentKey = { it.announcement },
                transitionSpec = {
                    val duration = if (reduceMotion) 0 else Motion.STATE_HANDOFF
                    fadeIn(Motion.tween(duration)) togetherWith fadeOut(Motion.tween(duration)) using
                        Motion.sizeTransform(reduceMotion)
                },
                contentAlignment = Alignment.CenterStart,
                label = "status-chip-line",
            ) { current ->
                Text(current.text, style = AppTypography.caption.medium, color = Color.White)
            }
        }
    }
}

/**
 * Hands a screen reader the line's stable wording as a polite live region, and nothing else: the
 * figures in [PlaybackStatusLine.text] stay on screen and out of the spoken tree.
 */
private fun Modifier.statusSemantics(line: PlaybackStatusLine): Modifier =
    // The live region goes first: semantics to the right of a clear count as its descendants.
    liveStatus().clearAndSetSemantics { contentDescription = line.announcement }

private const val CONTINUITY_STATUS_DELAY_MS = 550L

private const val CONTINUITY_ARTWORK_DIM = 0.34f
