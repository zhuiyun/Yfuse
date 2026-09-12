@file:OptIn(ExperimentalLayoutApi::class)

package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private val ScrimColor = Color(0xFF0A0E16)
private val OverlayShape = GlassShapes.sheet
private val OverlayMaxWidth = 560.dp

@Stable
class OverlayVisibility {
    var count by mutableStateOf(0)
        private set

    val any: Boolean get() = count > 0

    internal fun enter() {
        count++
    }

    internal fun exit() {
        count = (count - 1).coerceAtLeast(0)
    }
}

val LocalOverlayVisibility = staticCompositionLocalOf<OverlayVisibility?> { null }

@Composable
fun ReportOverlayVisible(enabled: Boolean = true) {
    val visibility = LocalOverlayVisibility.current
    if (!enabled || visibility == null) return
    DisposableEffect(visibility) {
        visibility.enter()
        onDispose { visibility.exit() }
    }
}

/** The shared modal material used outside player chrome. */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    liquidButtons: Boolean = true,
    contentPadding: Dp = 18.dp,
    alignment: Alignment = Alignment.Center,
    windowPadding: PaddingValues = PaddingValues(horizontal = 26.dp, vertical = 20.dp),
    shape: Shape = OverlayShape,
    dismissEnabled: Boolean = true,
    maxWidth: Dp = OverlayMaxWidth,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    val parentMotionHost = LocalDialogMotionHost.current
    var leaving by remember { mutableStateOf(false) }
    var afterExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val requestDismiss = remember { { if (canDismiss) leaving = true } }
    val complete =
        remember {
            { action: () -> Unit ->
                if (!leaving) {
                    afterExit = action
                    leaving = true
                }
            }
        }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = properties,
    ) {
        ReportOverlayVisible()
        val palette = LocalPalette.current
        val selectedAnimation = LocalDialogAnimation.current
        val animation = remember { selectedAnimation }
        val modalMotionHost =
            remember {
                DialogMotionHost().apply {
                    touch = parentMotionHost.touch
                    poster = parentMotionHost.poster
                }
            }
        val progress =
            rememberOverlayTransition(leaving = leaving, animation = animation) { (afterExit ?: onDismiss)() }
        // Durations answer to the system flag too — [rememberOverlayTransition] reads
        // `reduceMotion` — but a way of closing the dialog answers only to the person. 减弱动态效果
        // turns the gesture off because the panel would have to jump home with no settle;
        // 「移除动画」 on the device asks for shorter transitions, not for fewer ways out.
        val dragOff = LocalAccessibilityOptions.current.reduceMotionByUser
        // Pushing a dialog away is a gesture, not a style. It used to be gated on 磁吸归位 being
        // the selected animation, which made the one way of dismissing that needs no target at all
        // a property of a decorative preference: 42 of the 43 styles could only be closed by the
        // scrim or a button. Every style gets the gesture now — a scrollable panel hands it over
        // through nested scroll, one with nothing to scroll is dragged directly (see below) —
        // and 磁吸归位 keeps what is actually its own, the visible handle and its settle visuals.
        val drag =
            rememberDialogDragState(
                enabled = {
                    !dragOff &&
                        canDismiss &&
                        !leaving &&
                        progress() >= 1f
                },
                dismiss = requestDismiss,
            )
        val panelDrag = rememberDraggableState { drag.move(it) }
        LaunchedEffect(dragOff, leaving) {
            if (dragOff) {
                drag.reset()
            } else if (leaving) {
                drag.stopSettling()
            }
        }
        val contentMotion = remember(animation, progress) { DialogContentMotion(animation, progress) }
        val lightMoving by remember(progress) { derivedStateOf { progress() > 0f && progress() < 1f } }
        val simpleLightStyle =
            animation in
                listOf(
                    DialogAnimation.Lift,
                    DialogAnimation.Axis,
                    DialogAnimation.Slide,
                    DialogAnimation.Touch,
                    DialogAnimation.Spring,
                    DialogAnimation.Sheen,
                )
        val phaseLights = rememberPhaseLightCount(lightMoving && simpleLightStyle)
        CompositionLocalProvider(
            LocalOverlayDismiss provides requestDismiss,
            LocalOverlayLiquidButtons provides liquidButtons,
            LocalMutedGlass provides true,
            LocalOverlayComplete provides complete,
            LocalDialogContentMotion provides contentMotion,
            LocalDialogMotionHost provides modalMotionHost,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .trackDialogOrigin(LocalDialogMotionHost.current)
                    .pointerInput(requestDismiss) { detectTapGestures { requestDismiss() } },
                contentAlignment = alignment,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(
                                ScrimColor,
                                alpha =
                                    (if (palette.isDark) 0.28f else 0.16f) * progress().coerceIn(0f, 1f),
                            )
                        },
                )
                val panelScrollState = rememberScrollState()
                Column(
                    Modifier
                        .safeDrawingPadding()
                        // Only the padding, never [imeNestedScroll]: that one drives the keyboard
                        // from leftover scroll, and once a panel is at its top every upward pull
                        // is leftover. The dismiss drag only takes the downward half, so on a panel
                        // with nothing to type into a swipe up summoned an empty keyboard.
                        .imePadding()
                        .padding(windowPadding)
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        // At rest the drag contributes nothing — zero offset, no stretch — so every
                        // style keeps its authored enter and exit geometry untouched, and only a
                        // finger on the panel translates it.
                        .dialogMotion(
                            animation,
                            drag = drag.takeIf { !dragOff },
                            progress = progress,
                        ).shadow(Shadows.sheet, shape)
                        .mutedGlassPanel(shape)
                        .dialogInteriorMotion(animation, progress)
                        .phaseLightEdge(progress, phaseLights)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier)
                        // Nothing to scroll is nothing to hand over: a panel that never scrolls has
                        // no nested-scroll source at all, so the panel itself is the drag surface.
                        // Outside [contentPadding], so the gesture starts anywhere on the glass; a
                        // scrollable child inside it still wins the gesture and feeds the same
                        // state through [nestedScroll] below.
                        .then(
                            if (scrollable || dragOff) {
                                Modifier
                            } else {
                                Modifier.draggable(
                                    state = panelDrag,
                                    orientation = Orientation.Vertical,
                                    enabled = canDismiss && !leaving,
                                    onDragStopped = { drag.release(it) },
                                )
                            },
                        ).padding(contentPadding)
                        // Before [verticalScroll], so a panel that has nothing left to scroll hands
                        // the remaining downward drag to the dialog instead of swallowing it.
                        .then(if (dragOff) Modifier else Modifier.nestedScroll(drag))
                        .then(
                            if (scrollable) {
                                Modifier.verticalScroll(panelScrollState)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (animation ==
                        DialogAnimation.MagneticDrag
                    ) {
                        DialogDragHandle(drag, !dragOff && canDismiss && !leaving)
                    }
                    content()
                }
            }
        }
    }
}

internal val LocalOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }
private val LocalOverlayLiquidButtons = staticCompositionLocalOf { true }
internal val LocalOverlayComplete = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

@Composable
fun overlayDismiss(fallback: () -> Unit): () -> Unit = LocalOverlayDismiss.current ?: fallback

/** For actions which remove the modal: finish its exit before changing the owning state. */
@Composable
fun overlayAction(action: () -> Unit): () -> Unit {
    val complete = LocalOverlayComplete.current
    val currentAction by rememberUpdatedState(action)
    return remember(complete) { { if (complete == null) currentAction() else complete { currentAction() } } }
}

@Composable
internal fun rememberOverlayTransition(
    leaving: Boolean,
    animation: DialogAnimation = LocalDialogAnimation.current,
    onLeft: () -> Unit,
): () -> Float {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val finish by rememberUpdatedState(onLeft)
    // LaunchedEffect cancels an interrupted entrance. Exit also completes when dismissed
    // before the first frame, where animateFloatAsState's finishedListener may never run.
    LaunchedEffect(leaving, reduceMotion) {
        val target = if (leaving) 0f else 1f
        if (reduceMotion) {
            progress.snapTo(target)
        } else {
            progress.animateTo(
                target,
                if (leaving) {
                    tween(
                        overlayRemainingDurationMillis(animation.exitMillis, progress.value, target),
                        easing = Motion.Dialog.ExitCurve,
                    )
                } else {
                    tween(
                        overlayRemainingDurationMillis(animation.enterMillis, progress.value, target),
                        easing = Motion.Dialog.EnterCurve,
                    )
                },
            )
        }
        if (leaving) finish()
    }
    return remember(progress) { { progress.value } }
}

internal fun overlayRemainingDurationMillis(
    duration: Int,
    current: Float,
    target: Float,
): Int = (duration * abs(target - current).coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1)

internal fun overlayDurationMillis(
    leaving: Boolean,
    reduceMotion: Boolean,
    animation: DialogAnimation = DialogAnimation.Lift,
): Int =
    when {
        reduceMotion -> 0
        leaving -> animation.exitMillis
        else -> animation.enterMillis
    }

@Composable
fun OverlayHeader(
    title: String,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().dialogHeaderMotion().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.section.strong, color = palette.text, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClose != null) {
            val close = overlayDismiss(onClose)
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = palette.sub2,
                modifier =
                    Modifier
                        .pressable(onClick = close)
                        .touchTarget()
                        .size(28.dp)
                        .then(
                            if (LocalOverlayLiquidButtons.current) {
                                Modifier.liquidGlass(
                                    shape = CircleShape,
                                    fill = palette.card2,
                                    border = palette.border,
                                    over = palette.background,
                                    sheen = 0.62f,
                                )
                            } else {
                                Modifier.flatGlass(CircleShape, palette.card2, palette.border)
                            },
                        ).padding(8.dp),
            )
        }
    }
}

enum class OverlayButtonTone { Primary, Plain, Destructive }

/**
 * Overlay actions always keep a neutral glass body. Primary/destructive meaning is carried by
 * border and ink, never by a solid blue/red fill.
 */
@Composable
fun OverlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: OverlayButtonTone = OverlayButtonTone.Plain,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val emphasis =
        when (tone) {
            OverlayButtonTone.Primary -> GlassButtonEmphasis.Primary
            OverlayButtonTone.Destructive -> GlassButtonEmphasis.Destructive
            OverlayButtonTone.Plain -> GlassButtonEmphasis.Neutral
        }
    val visuals = resolveGlassButtonVisuals(emphasis, palette, accent)
    Row(
        modifier
            .dialogElementMotion(DialogElementRole.Action)
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = glassButtonAlpha(enabled) }
            .pressable(
                enabled = enabled && !loading,
                haptic = if (tone == OverlayButtonTone.Plain) null else HapticSignal.Confirm,
                focusShape = AppShapes.control,
                onClickLabel = label,
                onClick = onClick,
            ).liquidGlass(
                shape = AppShapes.control,
                fill = visuals.fill,
                border = visuals.border,
                over = palette.background,
                sheen = visuals.sheen,
            ).waitingPulse(
                active = loading,
                shape = AppShapes.control,
                color = if (emphasis == GlassButtonEmphasis.Neutral) accent.accent else visuals.content,
            ).padding(horizontal = 16.dp, vertical = 11.dp)
            .semantics {
                if (loading) stateDescription = "处理中"
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            OrbProgress(size = 16.dp, color = visuals.content)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = AppTypography.body.strong,
            color = visuals.content,
            maxLines = 1,
        )
    }
}

@Composable
fun OverlayButtonRow(
    dismissLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmTone: OverlayButtonTone = OverlayButtonTone.Primary,
    confirmEnabled: Boolean = true,
    confirming: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OverlayButton(dismissLabel, overlayDismiss(onDismiss), Modifier.weight(1f))
        OverlayButton(
            label = confirmLabel,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            tone = confirmTone,
            enabled = confirmEnabled,
            loading = confirming,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    liquidButtons: Boolean = true,
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onDismiss, liquidButtons = liquidButtons) {
        Text(title, style = AppTypography.section.strong, color = palette.text)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.body,
        )
        OverlayButtonRow(
            dismissLabel = dismissLabel,
            confirmLabel = confirmLabel,
            onDismiss = onDismiss,
            onConfirm = overlayAction(onConfirm),
            confirmTone =
                if (destructive) {
                    OverlayButtonTone.Destructive
                } else {
                    OverlayButtonTone.Primary
                },
        )
    }
}

val OverlayOptionSpacing: Dp = 8.dp

/** Selectable rows use the same liquid body as buttons; selection is a separate focus ring. */
@Composable
fun OverlayOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    destructive: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val fill = if (destructive) palette.errorContainer.copy(alpha = 0.52f) else palette.card2
    val materialBorder = if (selected || destructive) null else palette.border
    val ink =
        when {
            destructive -> palette.error
            selected -> accent.accent
            else -> palette.text
        }
    Row(
        modifier
            .dialogElementMotion(DialogElementRole.Option)
            .fillMaxWidth()
            .pressable(
                haptic = if (destructive) HapticSignal.Confirm else HapticSignal.Select,
                role = Role.RadioButton,
                focusShape = GlassShapes.chip,
                onClick = onClick,
            ).semantics { this.selected = selected }
            .heightIn(min = MinTouchTarget)
            .then(
                if (LocalOverlayLiquidButtons.current) {
                    Modifier.liquidGlass(
                        shape = GlassShapes.chip,
                        fill = fill,
                        border = materialBorder,
                        over = palette.background,
                        sheen = if (selected || destructive) 0.72f else 0.62f,
                    )
                } else {
                    Modifier.flatGlass(GlassShapes.chip, fill, materialBorder)
                },
            ).then(
                if (selected) {
                    Modifier.border(2.dp, accent.border, GlassShapes.chip)
                } else {
                    Modifier
                },
            ).padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style =
                    if (selected || destructive) {
                        AppTypography.body.strong
                    } else {
                        AppTypography.body.medium
                    },
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier.size(20.dp).lightOnChange(selected, LightEffect.Converge, emitWhen = selected && !destructive),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(accent.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = accent.onAccent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
