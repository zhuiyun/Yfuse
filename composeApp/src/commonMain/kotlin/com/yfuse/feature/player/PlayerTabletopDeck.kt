package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassSlider
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.pressableTarget
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Foldable tabletop lower-half control deck: when a book/clamshell foldable is half-opened
 * horizontally, the upper half displays unobstructed video while this deck occupies the resting
 * lower half with large, thumb-friendly transport controls.
 */
@Composable
internal fun PlayerTabletopDeck(
    title: String,
    playback: State<PlaybackState>,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    casting: Boolean = false,
    speedUnavailableReason: String? = null,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    subtitleLabel: String? = null,
    onCycleSubtitle: (() -> Unit)? = null,
) {
    val state = playback.value
    val accent = LocalAccentColors.current.accent
    val haptics = LocalHaptics.current
    val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D12))
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .pressableTarget(CircleShape, onClickLabel = "关闭播放", onClick = onBack)
                    .size(36.dp)
                    .solidGlass(CircleShape, Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.ChevronLeft, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "正在播放" },
                    style = AppTypography.body.strong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (casting) "悬停模式 · 投屏控制" else "悬停模式 · 下屏独立控制",
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.56f),
                )
            }
            if (onCycleSubtitle != null) {
                Text(
                    subtitleLabel ?: "字幕",
                    style = AppTypography.caption.strong,
                    color = if (subtitleLabel != null && subtitleLabel != "字幕关") accent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .clip(AppShapes.pill)
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), AppShapes.pill)
                            .pressable(haptic = HapticSignal.Select, focusShape = AppShapes.pill, onClick = onCycleSubtitle)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                speedLabel(state.speed),
                style = AppTypography.caption.strong,
                color =
                    when {
                        speedUnavailableReason != null -> Color.White.copy(alpha = 0.42f)
                        state.speed != 1f -> accent
                        else -> Color.White
                    },
                modifier =
                    Modifier
                        .clip(AppShapes.pill)
                        .background(Color.White.copy(alpha = if (speedUnavailableReason != null) 0.05f else 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), AppShapes.pill)
                        .pressable(haptic = HapticSignal.Select, focusShape = AppShapes.pill) {
                            val idx = speeds.indexOfFirst { kotlin.math.abs(it - state.speed) < 0.05f }
                            onSpeed(speeds[(idx + 1) % speeds.size])
                        }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (errorMessage != null && onRetry != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.card)
                    .background(Color(0x33EF4444))
                    .border(1.dp, Color(0x66EF4444), AppShapes.card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    errorMessage,
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "重试",
                    style = AppTypography.caption.strong,
                    color = Color.White,
                    modifier =
                        Modifier
                            .clip(AppShapes.pill)
                            .background(accent)
                            .pressable(haptic = HapticSignal.Confirm, focusShape = AppShapes.pill, onClick = onRetry)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fraction =
                if (state.durationMs > 0L) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            GlassSlider(
                value = fraction,
                onValueChange = { next ->
                    if (state.durationMs > 0L) onSeek((next * state.durationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.positionMs.asClock(), style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.72f))
                Text(state.durationMs.asClock(), style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.52f))
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabletopTransportKey(
                label = "上一集",
                icon = AppIcons.Previous,
                enabled = state.hasPrevious,
                onClick = {
                    haptics.play(HapticSignal.Tap)
                    onPrevious()
                },
            )
            TabletopTransportKey(
                label = "-15s",
                icon = AppIcons.Rewind,
                enabled = state.durationMs > 0L,
                onClick = {
                    haptics.play(HapticSignal.Tick)
                    onSeek((state.positionMs - 15_000L).coerceAtLeast(0L))
                },
            )
            Box(
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .pressable(haptic = HapticSignal.Confirm, focusShape = CircleShape, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.playing) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (state.playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            TabletopTransportKey(
                label = "+15s",
                icon = AppIcons.Forward,
                enabled = state.durationMs > 0L,
                onClick = {
                    haptics.play(HapticSignal.Tick)
                    onSeek((state.positionMs + 15_000L).coerceAtMost(state.durationMs))
                },
            )
            TabletopTransportKey(
                label = "下一集",
                icon = AppIcons.Next,
                enabled = state.hasNext,
                onClick = {
                    haptics.play(HapticSignal.Tap)
                    onNext()
                },
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun TabletopTransportKey(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.04f))
                .pressable(enabled = enabled, focusShape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.30f),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            label,
            style = AppTypography.caption.regular,
            color = Color.White.copy(alpha = if (enabled) 0.72f else 0.30f),
        )
    }
}
