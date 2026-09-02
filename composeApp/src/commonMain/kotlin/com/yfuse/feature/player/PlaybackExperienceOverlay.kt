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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import kotlinx.coroutines.delay

/** Keeps artwork on screen until a replacement engine has produced a verified video frame. */
@Composable
internal fun PlaybackContinuityOverlay(
    artworkUrls: List<String?>,
    title: String,
    visible: Boolean,
    message: String,
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
        enter = fadeIn(tween(if (reduceMotion) 0 else CONTINUITY_ENTER_MS)),
        exit = fadeOut(tween(if (reduceMotion) 0 else CONTINUITY_EXIT_MS)),
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)))
            if (statusVisible) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 70.dp)
                        .glass(
                            shape = AppShapes.pill,
                            fill = Color.Black.copy(alpha = 0.58f),
                            border = Color.White.copy(alpha = 0.20f),
                        ).padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(message, style = AppTypography.caption.medium, color = Color.White)
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
        enter = fadeIn(tween(if (reduceMotion) 0 else OLED_FADE_MS)),
        exit = fadeOut(tween(if (reduceMotion) 0 else OLED_FADE_MS)),
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

/** Names a rebuffer/reconnect state without replacing the last good video frame. */
@Composable
internal fun PlaybackStatusChip(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (reduceMotion) 0 else CONTINUITY_ENTER_MS)),
        exit = fadeOut(tween(if (reduceMotion) 0 else CONTINUITY_EXIT_MS)),
        modifier = modifier,
    ) {
        Row(
            Modifier.glass(
                shape = AppShapes.pill,
                fill = Color.Black.copy(alpha = 0.64f),
                border = Color.White.copy(alpha = 0.20f),
            ).padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(14.dp),
            )
            Text(message, style = AppTypography.caption.medium, color = Color.White)
        }
    }
}

private const val CONTINUITY_STATUS_DELAY_MS = 550L
private const val CONTINUITY_ENTER_MS = 140
private const val CONTINUITY_EXIT_MS = 320
private const val OLED_FADE_MS = 450
