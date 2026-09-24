@file:Suppress("ktlint:standard:property-naming")

package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PressFeedback
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.playerHandoffKey
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.softActionSurface
import com.yfuse.core.designsystem.softSelectionSurface
import com.yfuse.core.designsystem.waitingPulse
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private const val EmbyTicksPerSecond = 10_000_000L

internal fun resumeTimeColor(accent: Color): Color = primaryActionContentColor(accent)

internal fun formatResumePosition(playPositionTicks: Long): String? {
    if (playPositionTicks <= 0L) return null
    val totalSeconds = playPositionTicks / EmbyTicksPerSecond
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
internal fun DetailActionDock(
    accent: Color,
    label: String,
    /** `S1 E4` / server identity; the resume clock is rendered separately at the right edge. */
    detailLine: String?,
    /** Resume position shown immediately to the left of the chevron. */
    resumeTimeLabel: String?,
    resolving: Boolean,
    /** Shown only when there is progress to discard. */
    canPlayFromStart: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
) {
    val actionInk = primaryActionContentColor(accent)
    // One source per half. Shared, a focused 从头 lit the ring on 播放 as well; the key still moves
    // and washes as one piece whichever half is pressed, by answering to both.
    val playInteractions = remember { MutableInteractionSource() }
    val fromStartInteractions = remember { MutableInteractionSource() }
    val pressedWash = actionInk.copy(alpha = 0.08f)
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // One confident primary key, with a translucent icon well inside the poster-coloured body.
        // The lifted shadow and contrast-selected ink keep it legible across bright and dark artwork.
        Row(
            Modifier
                .fillMaxWidth()
                .height(DetailPlayButtonHeight)
                // 玻璃舱 and 开幕 start from the key that was pressed.
                .playerHandoffKey(corner = 16.dp, tint = accent, ink = actionInk)
                .softActionSurface(playInteractions, enabled = !resolving)
                .softActionSurface(fromStartInteractions, enabled = !resolving)
                .shadow(GlassLift.key, AppShapes.card)
                .clip(AppShapes.card)
                .background(actionKeyBrush(accent))
                .waitingPulse(active = resolving, shape = AppShapes.card, color = actionInk)
                .softSelectionSurface(
                    interactionSource = playInteractions,
                    shape = AppShapes.card,
                    pressedColor = pressedWash,
                    enabled = !resolving,
                ).softSelectionSurface(
                    interactionSource = fromStartInteractions,
                    shape = AppShapes.card,
                    pressedColor = pressedWash,
                    enabled = !resolving,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(DetailPlayButtonHeight)
                    .pressable(
                        enabled = !resolving,
                        pressedScale = 1f,
                        lightFeedback = false,
                        interactionSource = playInteractions,
                        // The key's own surface paints the pressed wash; a second one would double it.
                        stateLayer = false,
                        onClick = onPlay,
                    ).padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(actionInk.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    InlineLoadingContent(loading = resolving, slotSize = 15.dp, color = actionInk) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = actionInk,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = AppTypography.body.strong,
                        color = actionInk,
                        maxLines = 1,
                    )
                    detailLine?.let {
                        Text(
                            it,
                            style = AppTypography.caption.medium,
                            color = actionInk.copy(alpha = 0.76f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                resumeTimeLabel?.let { resumeTime ->
                    Text(
                        resumeTime,
                        style = AppTypography.caption.strong,
                        color = resumeTimeColor(accent),
                        maxLines = 1,
                        modifier =
                            Modifier
                                .padding(start = 8.dp, end = 5.dp)
                                .clip(AppShapes.thumb)
                                .background(actionInk.copy(alpha = 0.16f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = actionInk.copy(alpha = 0.78f),
                    modifier = Modifier.size(14.dp),
                )
            }
            if (canPlayFromStart) {
                Box(
                    Modifier
                        .width(Dimens.hairline)
                        .height(30.dp)
                        .background(actionInk.copy(alpha = 0.26f)),
                )
                Column(
                    Modifier
                        .width(74.dp)
                        .height(DetailPlayButtonHeight)
                        .pressable(
                            enabled = !resolving,
                            pressedScale = 1f,
                            lightFeedback = false,
                            interactionSource = fromStartInteractions,
                            stateLayer = false,
                            onClickLabel = "从头播放",
                            onClick = onPlayFromStart,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        AppIcons.Refresh,
                        contentDescription = null,
                        tint = actionInk,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "从头",
                        style = AppTypography.caption.strong,
                        color = actionInk,
                    )
                }
            }
        }
    }
}

/** A layered secondary key: glass body, inset icon well and a visible selected state. */
@Composable
internal fun GlassActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val palette = LocalPalette.current
    val stateColors = detailStateColors(accent, palette.background, palette.isDark)
    val fill =
        when {
            active -> stateColors.surface
            palette.isDark -> Color.White.copy(alpha = 0.075f)
            else -> Color.White.copy(alpha = 0.20f)
        }
    val edge =
        when {
            active -> stateColors.border
            palette.isDark -> Color.White.copy(alpha = 0.14f)
            else -> Color.White.copy(alpha = 0.22f)
        }
    Row(
        modifier
            .height(46.dp)
            // 收藏 / 稍后观看 change state in place and navigate nowhere, so the tap needs
            // to be felt as well as seen.
            .pressable(
                enabled = enabled,
                pressedScale = PressFeedback.QUIET,
                lightFeedback = false,
                haptic = HapticSignal.Confirm,
                onClick = onClick,
            ).shadow(GlassLift.control, AppShapes.card)
            .liquidGlass(
                shape = AppShapes.card,
                fill = fill,
                border = edge,
                sheen = 0.72f,
            ).waitingPulse(
                active = loading,
                shape = AppShapes.card,
                color = if (active) stateColors.foreground else accent,
            ).padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        stateColors.iconSurface
                    } else {
                        palette.text.copy(alpha = if (palette.isDark) 0.08f else 0.045f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            InlineLoadingContent(
                loading = loading,
                slotSize = 16.dp,
                orbSize = 15.dp,
                color = if (active) stateColors.foreground else palette.body,
            ) {
                BurstIcon(
                    icon = icon,
                    active = active,
                    contentDescription = label,
                    tint = if (active) stateColors.foreground else palette.body,
                    burstColor = accent,
                    iconSize = 16.dp,
                )
            }
        }
        Text(
            label,
            style = if (active) AppTypography.body.strong else AppTypography.body.medium,
            color = if (active) stateColors.foreground else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------- sections

/** 分类 — the genres as chips, which is the only place they are listed in full. */
