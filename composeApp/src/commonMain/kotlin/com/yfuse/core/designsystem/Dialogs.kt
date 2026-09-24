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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private val OverlayShape = AppShapes.sheet
private val OverlayMaxWidth = 560.dp

// Component sizes, not spacing: the close key, the loading orb beside a label, and the
// selected row's check badge with its glyph and ring. Gaps and insets come from [Dimens.space].
private val CloseKeySize = 28.dp
private val ButtonOrbSize = 16.dp
private val CheckBadgeSize = 20.dp
private val CheckGlyphSize = 12.dp
private val SelectionRingWidth = 2.dp

/** The close key's visible circle, centred in its 48dp target: the focus ring outlines what is seen. */
private object CloseKeyFocusShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val diameter = minOf(with(density) { CloseKeySize.toPx() }, size.width, size.height)
        val left = (size.width - diameter) / 2f
        val top = (size.height - diameter) / 2f
        return Outline.Rounded(
            RoundRect(left, top, left + diameter, top + diameter, CornerRadius(diameter / 2f)),
        )
    }
}

@Stable
class OverlayVisibility {
    /** Every open overlay. The dialog backdrop records the page while [any] is true. */
    var count by mutableStateOf(0)
        private set

    /**
     * Overlays drawn inside the page's own window — a full-screen [BackOverlay], the profile's
     * page stack. They compose below the shell's floating furniture, so the dock steps aside for
     * them. A [GlassDialog] is a window of its own above everything and does not count: hiding
     * the dock for one made every dialog on a root page a two-part motion, the bar sinking as the
     * panel rose and climbing back 260ms after it had gone.
     */
    var inWindow by mutableStateOf(0)
        private set

    val any: Boolean get() = count > 0

    /** Whether something in the page's window is covering the shell's floating furniture. */
    val coversShell: Boolean get() = inWindow > 0

    internal fun enter(inWindow: Boolean = true) {
        count++
        if (inWindow) this.inWindow++
    }

    internal fun exit(inWindow: Boolean = true) {
        count = (count - 1).coerceAtLeast(0)
        if (inWindow) this.inWindow = (this.inWindow - 1).coerceAtLeast(0)
    }
}

val LocalOverlayVisibility = staticCompositionLocalOf<OverlayVisibility?> { null }

/**
 * @param inWindow false for an overlay in a window of its own, which covers the dock without the
 *   dock's help. See [OverlayVisibility.inWindow].
 */
@Composable
fun ReportOverlayVisible(
    enabled: Boolean = true,
    inWindow: Boolean = true,
) {
    val visibility = LocalOverlayVisibility.current
    if (!enabled || visibility == null) return
    DisposableEffect(visibility, inWindow) {
        visibility.enter(inWindow)
        onDispose { visibility.exit(inWindow) }
    }
}

/** What a [DialogPresence] tells the dialog inside it. */
@Immutable
internal class DialogPresenceSignal(
    val visible: Boolean,
    val onExited: () -> Unit,
)

internal val LocalDialogPresence = compositionLocalOf<DialogPresenceSignal?> { null }

/**
 * Keeps a dialog composed through its exit when its owner closes it from outside.
 *
 * A dialog behind a plain `if` leaves the way it came only when the person closes it — the
 * scrim, back, 关闭. When the owner closes it — a store that finished saving, a selection
 * applied, a request that came back — the `if` turned false and panel and scrim vanished in one
 * frame. Put the [GlassDialog] inside this instead: while [value] is null it stays composed with
 * the last value, plays its exit, and then goes. A value that returns during the exit brings the
 * dialog back instead.
 *
 * [content] must build the dialog from the value it is handed, not from the owner's state — that
 * is already empty while the exit plays.
 */
@Composable
fun <T : Any> DialogPresence(
    value: T?,
    content: @Composable (T) -> Unit,
) {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained = value }
    val shown = value ?: retained ?: return
    val visible = value != null
    // Content that is not a GlassDialog never reports its exit; do not keep it forever.
    if (!visible) {
        LaunchedEffect(Unit) {
            delay(PRESENCE_RELEASE_AFTER_MS)
            retained = null
        }
    }
    val signal = remember(visible) { DialogPresenceSignal(visible) { retained = null } }
    CompositionLocalProvider(LocalDialogPresence provides signal) { content(shown) }
}

/** Longer than the slowest exit of any dialog style; only a non-dialog [DialogPresence] waits this. */
private const val PRESENCE_RELEASE_AFTER_MS = 1_500L

/** The shared modal material used outside player chrome. */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    liquidButtons: Boolean = true,
    contentPadding: Dp = Dimens.space.xl,
    alignment: Alignment = Alignment.Center,
    windowPadding: PaddingValues = PaddingValues(horizontal = Dimens.space.xxl, vertical = Dimens.space.xl),
    shape: Shape = OverlayShape,
    dismissEnabled: Boolean = true,
    maxWidth: Dp = OverlayMaxWidth,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    // False when the content opens with an edge-to-edge header that draws its own handle. 磁吸归位's
    // handle is a 28dp row above the content, so it would push that header down and leave a band
    // of bare glass - a second sheet peeking out above the first. The gesture is unaffected: a
    // panel that is not scrollable is itself the drag surface.
    dragHandle: Boolean = true,
    /**
     * Whether pulling the panel down may close it. Off for a form holding unsaved input: a
     * flick that lands a little too fast would otherwise throw away an address, a user name and
     * a password in one move.
     */
    dragToDismiss: Boolean = true,
    /**
     * Asked before the scrim, back or a drag starts the exit. Return false to keep the dialog
     * where it is — to ask 「放弃更改？」 first, say — instead of animating out and back in.
     */
    confirmDismiss: (() -> Boolean)? = null,
    /**
     * A fixed entrance for a panel whose shape already says how it arrives — a sheet pinned to
     * the bottom edge rises from it. Null follows the person's 弹窗动画 choice.
     */
    animation: DialogAnimation? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val parentMotionHost = LocalDialogMotionHost.current
    var leaving by remember { mutableStateOf(false) }
    var afterExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var exitsCompleted by remember { mutableIntStateOf(0) }
    // Set when the exit under way is the owner's (see [DialogPresence]) rather than the person's.
    var closedByOwner by remember { mutableStateOf(false) }
    val presence = LocalDialogPresence.current
    val currentPresence by rememberUpdatedState(presence)
    val ownerClosed = presence?.visible == false
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val canDrag by rememberUpdatedState(dragToDismiss)
    val confirm by rememberUpdatedState(confirmDismiss)
    val tryDismiss: () -> Boolean =
        remember {
            {
                if (!canDismiss || leaving || confirm?.invoke() == false) {
                    false
                } else {
                    leaving = true
                    true
                }
            }
        }
    val requestDismiss: () -> Unit = remember { { tryDismiss() } }
    val complete =
        remember {
            { action: () -> Unit ->
                if (!leaving) {
                    afterExit = action
                    leaving = true
                }
            }
        }

    LaunchedEffect(ownerClosed) {
        if (ownerClosed) {
            if (!leaving) {
                closedByOwner = true
                afterExit = null
                leaving = true
            }
        } else if (closedByOwner) {
            // The owner changed its mind mid-exit: come back from wherever the exit had got to.
            closedByOwner = false
            leaving = false
        }
    }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = properties,
    ) {
        // Its own window, above the dock: it does not need the dock to step aside.
        ReportOverlayVisible(inWindow = false)
        // The panel is on its way out; a tap meant for the page should reach the page.
        DialogTouchPassThrough(enabled = leaving)
        val palette = LocalPalette.current
        val material = LocalGlassMaterials.current.forTheme(palette.isDark).normalized(palette.isDark)
        val scrimColor = palette.scrim.copy(alpha = material.scrim)
        // The grey pane is not the page: `body` and `sub2` were measured against `background`
        // and landed at about 3:1 on the light dialog. Every dialog reads the recalibrated pair
        // from here, so the 48 call sites keep writing `palette.body` and get the right ink.
        val opaqueGlass = LocalAccessibilityOptions.current.reduceTransparency || !supportsBackdropBlur
        val dialogPalette =
            remember(palette, material, opaqueGlass) {
                val surfacePalette = material.contentPalette(palette, opaqueGlass)
                surfacePalette.copy(
                    body = surfacePalette.dialogBody,
                    sub = surfacePalette.dialogSub,
                    sub2 = surfacePalette.dialogSub2,
                    hint = surfacePalette.dialogHint,
                )
            }
        val selectedAnimation = LocalDialogAnimation.current
        val chosenAnimation = remember { animation ?: selectedAnimation }
        val modalMotionHost =
            remember {
                DialogMotionHost().apply {
                    touch = parentMotionHost.recentTouch
                    poster = parentMotionHost.poster
                }
            }
        val progress =
            rememberOverlayTransition(leaving = leaving, animation = chosenAnimation) {
                val owner = currentPresence
                if (owner != null && !owner.visible) {
                    // The owner has already let go; the exit was all that was left to do — apart
                    // from an action the person chose before the owner closed it.
                    afterExit?.invoke()
                    owner.onExited()
                } else {
                    (afterExit ?: onDismiss)()
                    exitsCompleted++
                }
            }
        val paneTitle = remember { mutableStateOf<String?>(null) }
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
                        canDrag &&
                        !leaving &&
                        progress() >= 1f
                },
                dismiss = requestDismiss,
                tryDismiss = tryDismiss,
            )
        // The owner is expected to take the dialog out of composition in [onDismiss]. One that
        // declines — it is still loading or saving, or its request failed — used to leave the
        // panel parked at progress 0: invisible, with its window still taking every touch and
        // back press, so the page underneath was dead until the app was killed. Still composed
        // after the exit means declined, so come back rather than strand the person.
        LaunchedEffect(exitsCompleted, ownerClosed) {
            if (exitsCompleted == 0) return@LaunchedEffect
            // Under a [DialogPresence] the owner accepts by emptying its value, and the dialog
            // stays composed only so it could finish leaving: let it go now.
            if (ownerClosed) {
                currentPresence?.onExited()
                return@LaunchedEffect
            }
            delay(DISMISS_DECLINED_AFTER_MS)
            afterExit = null
            closedByOwner = false
            drag.reset()
            leaving = false
        }
        val panelDrag = rememberDraggableState { drag.move(it) }
        LaunchedEffect(dragOff, leaving) {
            if (dragOff) {
                drag.reset()
            } else if (leaving) {
                drag.stopSettling()
            }
        }
        val contentMotion = remember(chosenAnimation, progress) { DialogContentMotion(chosenAnimation, progress) }
        val lightMoving by remember(progress) { derivedStateOf { progress() > 0f && progress() < 1f } }
        // The edge light rides a plate that moves as one piece; 磁吸归位 and 内容接力 move
        // their parts separately and would tear it.
        val simpleLightStyle =
            chosenAnimation == DialogAnimation.Lift ||
                chosenAnimation == DialogAnimation.Slide ||
                chosenAnimation == DialogAnimation.Touch
        val phaseLights = rememberPhaseLightCount(lightMoving && simpleLightStyle)
        val shownTitle = paneTitle.value
        CompositionLocalProvider(
            LocalPalette provides dialogPalette,
            LocalOverlayDismiss provides requestDismiss,
            LocalOverlayLiquidButtons provides liquidButtons,
            LocalMutedGlass provides true,
            LocalOverlayComplete provides complete,
            LocalDialogContentMotion provides contentMotion,
            LocalDialogMotionHost provides modalMotionHost,
            LocalDialogPaneTitle provides paneTitle,
            // A dialog opened from inside this one belongs to it, not to this one's owner.
            LocalDialogPresence provides null,
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
                            // The theme carries the user's per-mode glass scrim.
                            drawRect(scrimColor, alpha = progress().coerceIn(0f, 1f))
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
                        // TalkBack announces the pane by the title its [OverlayHeader] publishes.
                        .semantics { shownTitle?.let { this.paneTitle = it } }
                        // At rest the drag contributes nothing — zero offset, no stretch — so every
                        // style keeps its authored enter and exit geometry untouched, and only a
                        // finger on the panel translates it.
                        .dialogMotion(
                            chosenAnimation,
                            drag = drag.takeIf { !dragOff },
                            progress = progress,
                        ).shadow(Shadows.sheet, shape)
                        .mutedGlassPanel(shape)
                        .dialogInteriorMotion(chosenAnimation, progress)
                        .phaseLightEdge(progress, phaseLights)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier)
                        // Nothing to scroll is nothing to hand over: a panel that never scrolls has
                        // no nested-scroll source at all, so the panel itself is the drag surface.
                        // Outside [contentPadding], so the gesture starts anywhere on the glass; a
                        // scrollable child inside it still wins the gesture and feeds the same
                        // state through [nestedScroll] below.
                        .then(
                            if (scrollable || dragOff || !dragToDismiss) {
                                Modifier
                            } else {
                                Modifier.draggable(
                                    state = panelDrag,
                                    orientation = Orientation.Vertical,
                                    enabled = canDismiss && canDrag && !leaving,
                                    onDragStopped = { drag.release(it) },
                                )
                            },
                        ).padding(contentPadding)
                        // Before [verticalScroll], so a panel that has nothing left to scroll hands
                        // the remaining downward drag to the dialog instead of swallowing it.
                        .then(if (dragOff || !dragToDismiss) Modifier else Modifier.nestedScroll(drag))
                        .then(
                            if (scrollable) {
                                Modifier.verticalScroll(panelScrollState)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (dragHandle && chosenAnimation == DialogAnimation.MagneticDrag) {
                        DialogDragHandle(drag, !dragOff && canDismiss && canDrag && !leaving)
                    }
                    content()
                }
            }
        }
    }
}

internal val LocalOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Where an [OverlayHeader] publishes its title, so the dialog can announce itself as a pane. */
internal val LocalDialogPaneTitle = staticCompositionLocalOf<MutableState<String?>?> { null }

/**
 * How long a dialog waits, after its exit, for its owner to remove it before concluding the
 * dismissal was declined and coming back. Owners remove it within a frame or two.
 */
private const val DISMISS_DECLINED_AFTER_MS = 250L

private val LocalOverlayLiquidButtons = staticCompositionLocalOf { true }
internal val LocalOverlayComplete = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

@Composable
fun overlayDismiss(fallback: () -> Unit): () -> Unit = LocalOverlayDismiss.current ?: fallback

/** For actions which remove the modal: finish its exit before changing the owning state. */
@Composable
fun overlayAction(action: () -> Unit): () -> Unit = rememberOverlayAction(action, beforeExit = false)

/**
 * Runs the action at once and lets the dialog's exit play alongside — for an action whose
 * result takes over the screen anyway (选源 → 播放 opens the player), where waiting out a
 * 240–300ms exit first is only latency. Hold the dialog in a [DialogPresence], or the owner
 * removing it cuts the exit short.
 */
@Composable
fun overlayActionBeforeExit(action: () -> Unit): () -> Unit = rememberOverlayAction(action, beforeExit = true)

@Composable
private fun rememberOverlayAction(
    action: () -> Unit,
    beforeExit: Boolean,
): () -> Unit {
    val complete = LocalOverlayComplete.current
    val currentAction by rememberUpdatedState(action)
    return remember(complete, beforeExit) {
        {
            when {
                complete == null -> currentAction()
                beforeExit -> {
                    currentAction()
                    complete {}
                }
                else -> complete { currentAction() }
            }
        }
    }
}

@Composable
internal fun rememberOverlayTransition(
    leaving: Boolean,
    animation: DialogAnimation = LocalDialogAnimation.current,
    onLeft: () -> Unit,
): () -> Float {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // 静息 keeps the dialog's fade and short rise, at 60% of the length.
    val scale = if (calmMotion()) CALM_DURATION_SCALE else 1f
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
                        overlayRemainingDurationMillis(scaled(animation.exitMillis, scale), progress.value, target),
                        easing = Motion.Dialog.ExitCurve,
                    )
                } else {
                    tween(
                        overlayRemainingDurationMillis(scaled(animation.enterMillis, scale), progress.value, target),
                        easing = Motion.Dialog.EnterCurve,
                    )
                },
            )
        }
        if (leaving) finish()
    }
    return remember(progress) { { progress.value } }
}

private fun scaled(
    millis: Int,
    scale: Float,
): Int = (millis * scale).roundToInt()

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
    val paneTitle = LocalDialogPaneTitle.current
    if (paneTitle != null) SideEffect { paneTitle.value = title }
    Row(
        Modifier.fillMaxWidth().dialogHeaderMotion().padding(bottom = Dimens.cardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTypography.section.strong,
                color = palette.text,
                maxLines = 1,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Dimens.space.xs))
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
                        .pressable(focusShape = CloseKeyFocusShape, onClick = close)
                        .touchTarget()
                        .size(CloseKeySize)
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
                        ).padding(Dimens.space.sm),
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
            .defaultMinSize(minHeight = MinTouchTarget)
            .graphicsLayer { alpha = glassButtonAlpha(enabled) }
            .pressable(
                enabled = enabled && !loading,
                // A tap, not Confirm: the press is not the outcome, and 登录 or 删除 that then
                // fails had already buzzed 「成功」.
                haptic = if (tone == OverlayButtonTone.Plain) null else HapticSignal.Tap,
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
            ).padding(horizontal = Dimens.space.lg, vertical = Dimens.space.md)
            .semantics {
                if (loading) stateDescription = "处理中"
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            OrbProgress(size = ButtonOrbSize, color = visuals.content)
            Spacer(Modifier.width(Dimens.space.sm))
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
        Modifier.fillMaxWidth().padding(top = Dimens.space.lg),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
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
        Spacer(Modifier.height(Dimens.space.sm))
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

val OverlayOptionSpacing: Dp = Dimens.space.sm

/** Selectable rows use the same liquid body as buttons; selection is a separate focus ring. */
@Composable
fun OverlayOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    destructive: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    /**
     * [Role.RadioButton] for a choice among options. A row that *does* something — 关闭, 保存,
     * 刷新 — is a [Role.Button]; see [OverlayActionRow]. Announcing those as radio buttons made
     * TalkBack read 「关闭，单选按钮，未选中」.
     */
    role: Role = Role.RadioButton,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val choice = role != Role.Button
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
                haptic = if (destructive) HapticSignal.Tap else HapticSignal.Select,
                role = role,
                focusShape = AppShapes.chip,
                onClick = onClick,
            ).then(if (choice) Modifier.semantics { this.selected = selected } else Modifier)
            .heightIn(min = MinTouchTarget)
            .then(
                if (LocalOverlayLiquidButtons.current) {
                    Modifier.liquidGlass(
                        shape = AppShapes.chip,
                        fill = fill,
                        border = materialBorder,
                        over = palette.background,
                        sheen = if (selected || destructive) 0.72f else 0.62f,
                    )
                } else {
                    Modifier.flatGlass(AppShapes.chip, fill, materialBorder)
                },
            ).then(
                if (selected) {
                    Modifier.border(SelectionRingWidth, accent.border, AppShapes.chip)
                } else {
                    Modifier
                },
            ).padding(horizontal = Dimens.cardGap, vertical = Dimens.space.md),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingContent?.invoke()
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
                Spacer(Modifier.height(Dimens.space.xs))
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
            Modifier
                .size(CheckBadgeSize)
                .lightOnChange(selected, LightEffect.Converge, emitWhen = selected && !destructive),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier.size(CheckBadgeSize).clip(CircleShape).background(accent.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = accent.onAccent,
                        modifier = Modifier.size(CheckGlyphSize),
                    )
                }
            }
        }
    }
}

/**
 * A row that does something rather than choosing something — 关闭, 保存, 刷新元数据. Same body
 * as [OverlayOptionRow]; announced as a button and never as 「未选中」.
 */
@Composable
fun OverlayActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    destructive: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    OverlayOptionRow(
        label = label,
        selected = false,
        onClick = onClick,
        modifier = modifier,
        description = description,
        destructive = destructive,
        leadingContent = leadingContent,
        role = Role.Button,
    )
}
