@file:Suppress("ktlint:standard:property-naming")

package com.yfuse.feature.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PressFeedback
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.liquidMotionEnabled
import com.yfuse.core.designsystem.liquidOutline
import com.yfuse.core.designsystem.playerHandoffKey
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberRouteVisibility
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.softActionSurface
import com.yfuse.core.designsystem.softSelectionSurface
import com.yfuse.core.designsystem.waitingPulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/**
 * The play key, and 从头 beside it while there is progress to resume.
 *
 * They are two keys of one material. While 从头 grows out of the play key, or flows back into it,
 * they are one liquid body drawn behind the row (see [DetailKeyLiquid]); each key keeps its own
 * place to be tapped the whole time, and draws its own body again once the liquid comes to rest.
 */
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
    // One source per key: each key presses and washes on its own.
    val playInteractions = remember { MutableInteractionSource() }
    val fromStartInteractions = remember { MutableInteractionSource() }
    val pressedWash = actionInk.copy(alpha = 0.08f)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val liquidMotion = liquidMotionEnabled()
    // Once per page, not every time the row scrolls back into view.
    var arrivalPlayed by rememberSaveable { mutableStateOf(false) }
    val liquid =
        remember {
            val arriving = liquidMotion && canPlayFromStart && !arrivalPlayed
            DetailKeyLiquid(armed = if (arriving) DetailKeyMove.Split else null)
        }
    // Two keys while there is progress, and while 从头 is still flowing back into the play key.
    var twoKeys by remember { mutableStateOf(canPlayFromStart) }
    val routeVisible = rememberRouteVisibility()
    val latestLiquidMotion by rememberUpdatedState(liquidMotion)
    val hadProgress = remember { booleanArrayOf(canPlayFromStart) }
    LaunchedEffect(canPlayFromStart) {
        val appeared = canPlayFromStart && !hadProgress[0]
        val cleared = !canPlayFromStart && hadProgress[0]
        hadProgress[0] = canPlayFromStart
        when {
            appeared -> {
                twoKeys = true
                if (latestLiquidMotion) {
                    liquid.clock.arm(DetailKeyMove.Split)
                    snapshotFlow { routeVisible.value }.first { it }
                    // Back from the player: once the label has turned into 继续播放.
                    delay(Motion.STATE_HANDOFF.toLong())
                    arrivalPlayed = true
                    liquid.clock.play(DetailKeyMove.Split, DetailKeyMove.Split.durationMs)
                }
            }
            cleared -> {
                val splitNotStarted = liquid.clock.move == DetailKeyMove.Split && liquid.clock.elapsed == 0f
                when {
                    splitNotStarted -> liquid.clock.rest()
                    latestLiquidMotion && twoKeys ->
                        liquid.clock.play(DetailKeyMove.Merge, DetailKeyMove.Merge.durationMs)
                }
                twoKeys = false
            }
            // Opened with progress already there: the key arrives whole, and splits once the page is in.
            liquid.clock.move == DetailKeyMove.Split -> {
                snapshotFlow { routeVisible.value }.first { it }
                delay(DETAIL_SPLIT_ARRIVAL_MS)
                arrivalPlayed = true
                liquid.clock.play(DetailKeyMove.Split, DetailKeyMove.Split.durationMs)
            }
        }
    }
    val drawing = liquid.drawing
    val layoutDensity = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val keyBody =
        if (drawing) {
            Modifier
        } else {
            Modifier
                .shadow(GlassLift.key, AppShapes.card)
                .clip(AppShapes.card)
                .background(actionKeyBrush(accent))
        }
    // The label changes back to 播放 once the drop is on its way in, and the resume time goes with it.
    val merging = liquidMotion && twoKeys && !canPlayFromStart
    val shownLabel by produceState(label, label) {
        if (merging) delay(MERGE_LABEL_DELAY_MS.toLong())
        value = label
    }
    val lastResume = remember { arrayOf(resumeTimeLabel) }
    if (resumeTimeLabel != null) lastResume[0] = resumeTimeLabel
    val shownResume = resumeTimeLabel ?: lastResume[0].takeIf { merging }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DetailPlayButtonHeight)
                .onSizeChanged { liquid.metrics = DetailKeyMetrics(it.width / layoutDensity.density) }
                // While 从头 grows out or flows back, the two keys are one body drawn here.
                .drawBehind {
                    val frame = liquid.frame() ?: return@drawBehind
                    val metrics = liquid.metrics ?: return@drawBehind
                    val segments =
                        liquidOutline(frame.bodies(), 0f, metrics.width, DETAIL_KEY_HALF_HEIGHT * 1.5f)
                    liquid.paths.update(
                        segments,
                        scale = if (rtl) -density else density,
                        axisY = size.height / 2f,
                        originX = if (rtl) size.width else 0f,
                    )
                    drawDetailKeys(liquid.paths, actionKeyBrush(accent))
                },
            horizontalArrangement = Arrangement.spacedBy(DETAIL_KEY_GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One confident primary key, with a translucent icon well inside the poster-coloured body.
            // The lifted shadow and contrast-selected ink keep it legible across bright and dark artwork.
            Row(
                Modifier
                    .weight(1f)
                    .height(DetailPlayButtonHeight)
                    // 玻璃舱 and 开幕 start from the key that was pressed.
                    .playerHandoffKey(corner = 16.dp, tint = accent, ink = actionInk)
                    .softActionSurface(playInteractions, enabled = !resolving)
                    .then(keyBody)
                    .waitingPulse(active = resolving, shape = AppShapes.card, color = actionInk)
                    .softSelectionSurface(
                        interactionSource = playInteractions,
                        shape = AppShapes.card,
                        pressedColor = pressedWash,
                        enabled = !resolving,
                    ).pressable(
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
                    AnimatedContent(
                        targetState = shownLabel,
                        transitionSpec = {
                            val swap = if (reduceMotion) 0 else Motion.STATE_HANDOFF
                            fadeIn(Motion.tween(swap)) togetherWith fadeOut(Motion.tween(swap)) using
                                Motion.sizeTransform(reduceMotion)
                        },
                        label = "detailPlayLabel",
                    ) { text ->
                        Text(
                            text,
                            style = AppTypography.body.strong,
                            color = actionInk,
                            maxLines = 1,
                        )
                    }
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
                // The resume time and the chevron ride the key's right edge while 从头 grows out of it.
                Row(
                    Modifier.graphicsLayer {
                        val frame = liquid.frame() ?: return@graphicsLayer
                        val metrics = liquid.metrics ?: return@graphicsLayer
                        val shift = (frame.keyEdge - metrics.splitEdge) * density
                        translationX = if (rtl) -shift else shift
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shownResume?.let { resumeTime ->
                        Text(
                            resumeTime,
                            style = AppTypography.caption.strong,
                            color = resumeTimeColor(accent),
                            maxLines = 1,
                            modifier =
                                Modifier
                                    .graphicsLayer { alpha = liquid.frame()?.resume ?: 1f }
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
            }
            if (twoKeys) {
                val fromStartEnabled = !resolving && canPlayFromStart
                Column(
                    Modifier
                        .width(DETAIL_FROM_START_WIDTH.dp)
                        .height(DetailPlayButtonHeight)
                        // A key of its own now, so 玻璃舱 and 开幕 start from it when it is the one pressed.
                        .playerHandoffKey(corner = DETAIL_KEY_CORNER.dp, tint = accent, ink = actionInk)
                        .softActionSurface(fromStartInteractions, enabled = fromStartEnabled)
                        .then(keyBody)
                        .softSelectionSurface(
                            interactionSource = fromStartInteractions,
                            shape = AppShapes.card,
                            pressedColor = pressedWash,
                            enabled = fromStartEnabled,
                        ).pressable(
                            enabled = fromStartEnabled,
                            pressedScale = 1f,
                            lightFeedback = false,
                            interactionSource = fromStartInteractions,
                            stateLayer = false,
                            onClickLabel = "从头播放",
                            onClick = onPlayFromStart,
                        )
                        // Shape first, content after: the label comes into focus once the drop has squared up.
                        .graphicsLayer {
                            val focus = liquid.frame()?.label ?: return@graphicsLayer
                            alpha = focus
                            scaleX = FROM_START_FOCUS_FROM + (1f - FROM_START_FOCUS_FROM) * focus
                            scaleY = scaleX
                            val blur = FROM_START_FOCUS_BLUR.toPx() * (1f - focus)
                            renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                        },
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

/** 「↻ 从头」 comes into focus from this scale and this blur. */
private const val FROM_START_FOCUS_FROM = 0.8f
private val FROM_START_FOCUS_BLUR = 4.dp

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
