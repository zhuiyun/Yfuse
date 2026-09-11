package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Keeps artwork on screen until a replacement engine has produced a verified video frame.
 *
 * [message] is a reader rather than a string because the copy it produces is derived from the
 * live timeline — buffer seconds, recovery state, which episode is being joined. Built at the
 * call site it would be rebuilt on every position tick whether or not this overlay is even up;
 * invoked here it is only read where it is drawn.
 */
@Composable
internal fun PlaybackContinuityOverlay(
    artworkUrls: List<String?>,
    title: String,
    visible: Boolean,
    message: () -> String,
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
        enter = fadeIn(tween(if (reduceMotion) 0 else Motion.CONTINUITY_ENTER)),
        exit = fadeOut(tween(if (reduceMotion) 0 else Motion.CONTINUITY_EXIT)),
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
                enter = fadeIn(tween(if (reduceMotion) 0 else Motion.STATE_HANDOFF, easing = Motion.Curve)),
                exit = fadeOut(tween(if (reduceMotion) 0 else Motion.STATE_HANDOFF, easing = Motion.Curve)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
            ) {
                Row(
                    Modifier
                        .glass(
                            shape = AppShapes.pill,
                            fill = Color.Black.copy(alpha = 0.58f),
                            border = Color.White.copy(alpha = 0.20f),
                        ).padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbProgress(size = 15.dp, color = Color.White)
                    Text(message(), style = AppTypography.caption.medium, color = Color.White)
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
        enter = fadeIn(tween(if (reduceMotion) 0 else Motion.OLED_PROTECTION)),
        exit = fadeOut(tween(if (reduceMotion) 0 else Motion.OLED_PROTECTION)),
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
 */
@Composable
internal fun PlaybackStatusChip(
    visible: Boolean,
    message: () -> String,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (reduceMotion) 0 else Motion.CONTINUITY_ENTER)),
        exit = fadeOut(tween(if (reduceMotion) 0 else Motion.CONTINUITY_EXIT)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .glass(
                    shape = AppShapes.pill,
                    fill = Color.Black.copy(alpha = 0.64f),
                    border = Color.White.copy(alpha = 0.20f),
                ).padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbProgress(size = 14.dp, color = Color.White)
            Text(message(), style = AppTypography.caption.medium, color = Color.White)
        }
    }
}

private const val CONTINUITY_STATUS_DELAY_MS = 550L

private const val CONTINUITY_ARTWORK_DIM = 0.34f
