@file:Suppress("ktlint:standard:property-naming")

package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow

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
    favorite: Boolean,
    watchLater: Boolean,
    watchLaterMutating: Boolean,
    /** Shown only when there is progress to discard. */
    canPlayFromStart: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onFavorite: () -> Unit,
    onWatchLater: () -> Unit,
) {
    val actionInk = primaryActionContentColor(accent)
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
                .shadow(GlassLift.key, GlassShapes.card)
                .clip(GlassShapes.card)
                .background(actionKeyBrush(accent))
                .border(
                    Dimens.hairline,
                    actionInk.copy(alpha = 0.26f),
                    GlassShapes.card,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(DetailPlayButtonHeight)
                    .pressable(enabled = !resolving, onClick = onPlay)
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(actionInk.copy(alpha = 0.16f))
                        .border(
                            Dimens.hairline,
                            actionInk.copy(alpha = 0.22f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (resolving) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            color = actionInk,
                            strokeWidth = 2.dp,
                        )
                    } else {
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
                                .clip(GlassShapes.thumb)
                                .background(actionInk.copy(alpha = 0.16f))
                                .border(
                                    Dimens.hairline,
                                    actionInk.copy(alpha = 0.14f),
                                    GlassShapes.thumb,
                                ).padding(horizontal = 7.dp, vertical = 3.dp),
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

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassActionButton(
                icon = if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = if (favorite) "已收藏" else "收藏",
                active = favorite,
                accent = accent,
                onClick = onFavorite,
                modifier = Modifier.weight(1f),
            )
            GlassActionButton(
                icon = if (watchLater) AppIcons.Check else AppIcons.Bookmark,
                label =
                    when {
                        watchLaterMutating -> "同步中"
                        watchLater -> "已加入"
                        else -> "稍后观看"
                    },
                active = watchLater,
                accent = accent,
                enabled = !watchLaterMutating,
                loading = watchLaterMutating,
                onClick = onWatchLater,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A layered secondary key: glass body, inset icon well and a visible selected state. */
@Composable
private fun GlassActionButton(
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
            .pressable(enabled = enabled, haptic = HapticSignal.Confirm, onClick = onClick)
            .shadow(GlassLift.control, GlassShapes.card)
            .liquidGlass(
                shape = GlassShapes.card,
                fill = fill,
                border = edge,
                sheen = 0.72f,
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
                ).border(
                    Dimens.hairline,
                    if (active) stateColors.border else palette.border.copy(alpha = 0.72f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = if (active) stateColors.foreground else palette.body,
                    strokeWidth = 1.8.dp,
                )
            } else {
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
