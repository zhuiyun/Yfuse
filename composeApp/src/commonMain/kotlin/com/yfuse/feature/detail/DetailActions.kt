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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import com.yfuse.core.designsystem.glassButtonAlpha
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
 * The play key, and 从头 beside it while there is progress to resume; under them, the row of icon
 * keys ([DetailActionKeyRow]).
 *
 * They are two keys of one material. While 从头 grows out of the play key, or flows back into it,
 * they are one liquid body drawn behind the row (see [DetailKeyLiquid]); each key keeps its place in
 * the row the whole time, and draws its own body again once the liquid comes to rest. Until 从头's
 * label is in focus its place is the end of the one key on screen, and plays.
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
    /**
     * The icon keys under the play key, in the order given. Another action joins the row by being
     * one more entry — `keys = detailPageActionKeys(...) + trailerKey` — and nothing else changes.
     */
    keys: List<DetailActionKey> = emptyList(),
    /**
     * The keys' accent: the settled one, where [accent] blends in over 500ms. The play key follows
     * that blend in this scope; the keys need not repaint with it on every frame.
     */
    keyAccent: Color = accent,
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
    // Moves run on arm and runTo, which a change of mind interrupts where the move has got to: the
    // next one runs the same move back from there, as the dock does. A split cut short by the
    // progress going used to jump to a whole merge, and a merge cut short to a whole key.
    LaunchedEffect(canPlayFromStart) {
        val appeared = canPlayFromStart && !hadProgress[0]
        val cleared = !canPlayFromStart && hadProgress[0]
        hadProgress[0] = canPlayFromStart
        val clock = liquid.clock
        when {
            appeared -> {
                twoKeys = true
                when {
                    !latestLiquidMotion -> clock.rest()
                    // Back before 从头 had flowed in: out again the way it came.
                    clock.move == DetailKeyMove.Merge -> clock.runTo(0f)
                    clock.move == DetailKeyMove.Split -> clock.runTo(DetailKeyMove.Split.durationMs.toFloat())
                    else -> {
                        clock.arm(DetailKeyMove.Split)
                        snapshotFlow { routeVisible.value }.first { it }
                        // Back from the player: once the label has turned into 继续播放.
                        delay(Motion.STATE_HANDOFF.toLong())
                        arrivalPlayed = true
                        clock.runTo(DetailKeyMove.Split.durationMs.toFloat())
                    }
                }
            }
            cleared -> {
                val move = clock.move
                when {
                    // Without the liquid, or armed and not yet begun, there is nothing to run back.
                    !latestLiquidMotion || (move == DetailKeyMove.Split && clock.elapsed == 0f) -> clock.rest()
                    // Gone before 从头 had come out: back in the way it came.
                    move == DetailKeyMove.Split -> clock.runTo(0f)
                    move == DetailKeyMove.Merge -> clock.runTo(DetailKeyMove.Merge.durationMs.toFloat())
                    twoKeys -> {
                        clock.arm(DetailKeyMove.Merge)
                        clock.runTo(DetailKeyMove.Merge.durationMs.toFloat())
                    }
                }
                twoKeys = false
            }
            // Opened with progress already there: the key arrives whole, and splits once the page is in.
            clock.move == DetailKeyMove.Split -> {
                snapshotFlow { routeVisible.value }.first { it }
                delay(DETAIL_SPLIT_ARRIVAL_MS)
                arrivalPlayed = true
                clock.runTo(DetailKeyMove.Split.durationMs.toFloat())
            }
        }
    }
    // Until 从头 is a key the liquid draws one key across the row, and the end of it answers as that
    // key: a tap on the resume time there used to start over and throw the resume point away. Read
    // through derivedStateOf, so only the flip recomposes.
    val fromStartKey by remember(liquid) { derivedStateOf { fromStartIsKey(liquid.frame()) } }
    val oneKey = twoKeys && !fromStartKey
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
                // One key on screen, so 玻璃舱 and 开幕 start from all of it when its end is pressed.
                .then(
                    if (oneKey) {
                        Modifier.playerHandoffKey(corner = DETAIL_KEY_CORNER.dp, tint = accent, ink = actionInk)
                    } else {
                        Modifier
                    },
                ).onSizeChanged { liquid.metrics = DetailKeyMetrics(it.width / layoutDensity.density) }
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
                                    .graphicsLayer { alpha = liquid.resumeAlpha(progress = canPlayFromStart) }
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
                // While the row is one key this is that key's end, and plays as the rest of it does.
                val fromStartEnabled = !resolving && (canPlayFromStart || oneKey)
                Column(
                    Modifier
                        .width(DETAIL_FROM_START_WIDTH.dp)
                        .height(DetailPlayButtonHeight)
                        .then(
                            if (oneKey) {
                                // Not a key yet: nothing for a screen reader or a focus move to land on.
                                Modifier
                                    .semantics { hideFromAccessibility() }
                                    .focusProperties { canFocus = false }
                            } else {
                                // A key of its own now, so 玻璃舱 and 开幕 start from it when it is the one pressed.
                                Modifier.playerHandoffKey(corner = DETAIL_KEY_CORNER.dp, tint = accent, ink = actionInk)
                            },
                        ).softActionSurface(fromStartInteractions, enabled = fromStartEnabled)
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
                            onClick = if (oneKey) onPlay else onPlayFromStart,
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
        // Below the liquid's row rather than in it: the split and the merge are the play key's own.
        DetailActionKeyRow(keys, keyAccent)
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

/**
 * One key of [DetailActionKeyRow]: an icon over a short label.
 *
 * The row draws what it is handed, in order, so a key from elsewhere — 预告片 — joins it as one more
 * entry and nothing else changes.
 */
@Immutable
internal data class DetailActionKey(
    /** Stable within the row: what the row keys its tiles by, and what a test finds a key by. */
    val id: String,
    val icon: ImageVector,
    /** Under the icon. Short: five of these share a phone's width. */
    val label: String,
    val onClick: () -> Unit,
    /** What a screen reader calls the key, where that is more than [label] says. */
    val description: String = label,
    /** A switch, on or off in place: drawn in the accent while on, and felt when it changes. */
    val checked: Boolean? = null,
    /** Read after the name — 已收藏 / 未收藏, 已下载 — so the state is heard as well as seen. */
    val stateDescription: String? = null,
    /** The icon while [checked]; null keeps [icon]. */
    val checkedIcon: ImageVector? = null,
    /** A write is on its way: the key waits and takes no second tap. */
    val busy: Boolean = false,
    /** There but not usable yet — 下载 while 播放's file resolves. */
    val enabled: Boolean = true,
)

/**
 * 收藏, 稍后看, 已看 and 下载 under the play key: the actions people reach for most, which were up at
 * the top right or two taps into 更多. A row of equal tiles in [GlassActionButton]'s glass, each an
 * icon over its label, so a key joining or leaving reflows the row and never the page. It starts where
 * the artwork has all but dissolved into the page, so the palette's inks on the tiles' own glass read
 * in either theme; the title block above it is the part that sits on the picture.
 */
@Composable
internal fun DetailActionKeyRow(
    keys: List<DetailActionKey>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (keys.isEmpty()) return
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DETAIL_KEY_GAP.dp),
    ) {
        keys.forEach { action ->
            key(action.id) { DetailActionKeyTile(action, accent, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DetailActionKeyTile(
    action: DetailActionKey,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val stateColors = detailStateColors(accent, palette.background, palette.isDark)
    val on = action.checked == true
    // GlassActionButton's body and edge, so the row reads as the same family of keys.
    val fill =
        when {
            on -> stateColors.surface
            palette.isDark -> Color.White.copy(alpha = 0.075f)
            else -> Color.White.copy(alpha = 0.20f)
        }
    val edge =
        when {
            on -> stateColors.border
            palette.isDark -> Color.White.copy(alpha = 0.14f)
            else -> Color.White.copy(alpha = 0.22f)
        }
    val ink = if (on) stateColors.foreground else palette.body
    Column(
        modifier
            .heightIn(min = DetailActionKeyHeight)
            .graphicsLayer { alpha = glassButtonAlpha(action.enabled) }
            .pressable(
                enabled = action.enabled && !action.busy,
                pressedScale = PressFeedback.QUIET,
                lightFeedback = false,
                // A switch changes in place and navigates nowhere, so the tap is felt as well as seen.
                haptic = HapticSignal.Confirm.takeIf { action.checked != null },
                role = if (action.checked == null) Role.Button else Role.Checkbox,
                label = action.description,
                onClick = action.onClick,
            ).semantics {
                action.checked?.let { toggleableState = ToggleableState(it) }
                action.stateDescription?.let { stateDescription = it }
            }.shadow(GlassLift.control, AppShapes.card)
            .liquidGlass(shape = AppShapes.card, fill = fill, border = edge, sheen = 0.72f)
            .waitingPulse(active = action.busy, shape = AppShapes.card, color = if (on) ink else accent)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (on) {
                        stateColors.iconSurface
                    } else {
                        palette.text.copy(alpha = if (palette.isDark) 0.08f else 0.045f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (action.checked == null) {
                Icon(action.icon, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp))
            } else {
                BurstIcon(
                    icon = if (on) action.checkedIcon ?: action.icon else action.icon,
                    active = on,
                    contentDescription = null,
                    tint = ink,
                    burstColor = accent,
                    iconSize = 15.dp,
                )
            }
        }
        Text(
            action.label,
            style = if (on) AppTypography.caption.strong else AppTypography.caption.medium,
            color = if (on) stateColors.foreground else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The key already carries its name; the label is not read out a second time.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** The icon well, the label and their padding — and never under the 48dp a finger needs. */
private val DetailActionKeyHeight = 58.dp

// ---------------------------------------------------------------- sections

/** 分类 — the genres as chips, which is the only place they are listed in full. */
