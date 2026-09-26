package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.yfuse.core.designsystem.ThemeText as Text

private const val TOAST_MS = 2_600L
private const val MAX_TOASTS = 3

/** Characters a toast gets for its base time; each one past it adds [TOAST_MS_PER_CHAR]. */
private const val TOAST_BASE_CHARS = 12
private const val TOAST_MS_PER_CHAR = 80L
private const val TOAST_MAX_MS = 7_000L

/**
 * How long [message] stays up before the accessibility service's own adjustment. 2.6s was
 * every toast's time, and a 30-character notice that asks for something was gone before it
 * had been read.
 */
internal fun toastDurationMillis(message: String): Long =
    (TOAST_MS + (message.length - TOAST_BASE_CHARS).coerceAtLeast(0) * TOAST_MS_PER_CHAR).coerceAtMost(TOAST_MAX_MS)

/**
 * The bottom clearance the shell asks toasts to keep: the floating dock's, while the dock is
 * over the page. Null where nothing floats there, and a toast then clears the system bar alone.
 */
val LocalToastBottomInset = compositionLocalOf<Dp?> { null }

/**
 * The one thing a toast can offer besides being read: 撤销, as a rule.
 *
 * A toast with an action is how 先做，给 5 秒撤销 looks: the screen has already changed, the
 * server has not yet been told, and [onAction] puts things back. The producer commits when the
 * toast leaves — timed out, swiped away, or the app sent to the background — which is exactly
 * when `onDismiss` runs; that is the only signal it needs.
 */
@Immutable
class ToastAction(
    val label: String,
    val onAction: () -> Unit,
)

/**
 * How long an undo stays on offer. Fixed rather than scaled with the message: the countdown ring
 * says how long is left, and a window that moved with the wording could not be learned.
 */
const val TOAST_UNDO_WINDOW_MS = 5_000L

internal class ToastEntry(
    val message: String,
    val accent: Color?,
    val action: ToastAction? = null,
) {
    var visible by mutableStateOf(true)

    /** -1 or 1 once swiped away sideways, so it leaves the way it was thrown; 0 otherwise. */
    var thrown by mutableIntStateOf(0)
}

internal class ToastQueue {
    val entries = mutableStateListOf<ToastEntry>()
    private var latest: ToastEntry? = null

    fun post(
        message: String?,
        accent: Color? = null,
        action: ToastAction? = null,
    ) {
        if (message == null) {
            entries.forEach { it.visible = false }
            latest = null
            return
        }
        entries.removeAll { it.message == message }
        // An undo never outlives the next change: its producer commits what it was holding as soon
        // as something new is done (see [UndoWindow]), so its 撤销 would have nothing left to undo.
        entries.filter { it.visible && it.action != null }.forEach { it.visible = false }
        // The oldest leaves the way every toast leaves; it used to vanish in one frame. A burst
        // can outrun the exits, so anything past twice the stack still goes at once.
        while (entries.count { it.visible } >= MAX_TOASTS) entries.first { it.visible }.visible = false
        while (entries.size >= MAX_TOASTS * 2) entries.removeAt(0)
        val entry = ToastEntry(message, accent, action)
        latest = entry
        entries.add(entry)
    }

    fun dismiss(entry: ToastEntry): Boolean {
        val wasVisible = entry.visible
        entry.visible = false
        return wasVisible && latest === entry && entry in entries
    }
}

/**
 * Bounded, independently timed feedback. Only the latest notice may clear the producer's state.
 *
 * With an [action], the toast carries a button and a ring counting down [TOAST_UNDO_WINDOW_MS];
 * it stays up for the whole window (longer if the accessibility service asks for it), and it is
 * closed at once if the app leaves the foreground, so a deferred commit never hangs in the air.
 */
@Composable
fun BoxScope.ActionToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    action: ToastAction? = null,
) {
    val queue = remember { ToastQueue() }
    val entries = queue.entries
    val latestMessage by rememberUpdatedState(message)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestAction by rememberUpdatedState(action)
    val duration = if (LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current) 0 else Motion.TAB
    // The toast finds its own floor: each page used to pass a padding of its own, fixed numbers
    // that ignored the navigation bar and put toasts under three-button navigation.
    val systemBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val floor = LocalToastBottomInset.current ?: (systemBar + Dimens.sectionGap)
    LaunchedEffect(message) { queue.post(message, accent, latestAction) }
    // Leaving the app commits what the undo was holding back: an action left pending while the
    // process sits in the background could be lost with it, or undone hours later by accident.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, queue) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    queue.entries.filter { it.visible && it.action != null }.forEach { entry ->
                        if (queue.dismiss(entry) && latestMessage == entry.message) latestDismiss()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = floor)
            .motionAwareAnimateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { entry ->
            key(entry) {
                ActionToastEntry(entry, duration, onClose = {
                    if (queue.dismiss(entry) && latestMessage == entry.message) latestDismiss()
                }, onGone = { entries.remove(entry) })
            }
        }
    }
}

@Composable
private fun ActionToastEntry(
    entry: ToastEntry,
    duration: Int,
    onClose: () -> Unit,
    onGone: () -> Unit,
) {
    val palette = LocalPalette.current
    val themeAccent = LocalAccentColors.current
    val colors =
        remember(entry.accent, palette.isDark, themeAccent) {
            entry.accent?.let { resolveAccentColors(it, palette.isDark) } ?: themeAccent
        }
    val accessibility = LocalAccessibilityManager.current
    val latestClose by rememberUpdatedState(onClose)
    val latestGone by rememberUpdatedState(onGone)
    val visibility = remember(entry) { MutableTransitionState(false) }
    visibility.targetState = entry.visible
    var dragging by remember { mutableStateOf(false) }
    val exitLight = rememberLightFeedback(enhancedOnly = true)
    val currentExitLight by rememberUpdatedState(exitLight)
    var offset by remember { mutableFloatStateOf(0f) }
    // What is left of an undo's window, 1 to 0; read only while drawing the ring.
    val remaining = remember(entry) { Animatable(1f) }
    val animatedOffset =
        animateFloatAsState(
            offset,
            if (dragging ||
                duration == 0
            ) {
                snap()
            } else {
                Motion.settle()
            },
            label = "toast-drag",
        )
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(entry.visible, dragging, accessibility, duration) {
        if (entry.visible) {
            if (!dragging) {
                val base = if (entry.action != null) TOAST_UNDO_WINDOW_MS else toastDurationMillis(entry.message)
                val recommended =
                    accessibility?.calculateRecommendedTimeoutMillis(
                        base,
                        containsText = true,
                        containsControls = true,
                    ) ?: base
                val window = maxOf(base, recommended)
                // A service that asks for no timeout at all gets none: the notice waits to be closed.
                if (window == Long.MAX_VALUE) return@LaunchedEffect
                if (entry.action != null) {
                    remaining.snapTo(1f)
                    remaining.animateTo(
                        0f,
                        Motion.tween(window.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), easing = LinearEasing),
                    )
                } else {
                    delay(window)
                }
                latestClose()
            }
        } else {
            delay(duration.toLong())
            latestGone()
        }
    }
    val thrown = entry.thrown
    val action = entry.action
    val container =
        Modifier
            .padding(horizontal = Dimens.pageHorizontal)
            .lightOnAppear()
            .lightFeedback(exitLight)
            .graphicsLayer {
                translationX = animatedOffset.value
                alpha = (1f - abs(animatedOffset.value) / (threshold * 2f)).coerceIn(0.25f, 1f)
            }.draggable(
                state = rememberDraggableState { offset += it },
                orientation = Orientation.Horizontal,
                enabled = entry.visible,
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    if (abs(offset) >= threshold ||
                        (abs(offset) > threshold / 4f && abs(velocity) > threshold * 8f)
                    ) {
                        currentExitLight.emit(LightEffect.Dissolve, directionX = if (offset < 0f) -1f else 1f)
                        entry.thrown = if (offset < 0f) -1 else 1
                        latestClose()
                    } else {
                        offset = 0f
                    }
                },
            ).semantics {
                liveRegion = LiveRegionMode.Polite
                dismiss {
                    latestClose()
                    true
                }
                if (action != null) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(action.label) {
                                action.onAction()
                                latestClose()
                                true
                            },
                        )
                }
            }.pressable(enabled = entry.visible, onClickLabel = "关闭提示", onClick = onClose)
            .touchTarget()
            .shadow(Shadows.tabBar, AppShapes.chip)
            .solidGlass(AppShapes.chip, colors.container, colors.border)
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(Motion.tween(duration)) + slideInVertically(Motion.tween(duration)) { it / 2 },
        // A toast swiped sideways carries on sideways; it used to turn and drop out downwards.
        exit =
            fadeOut(Motion.tween(duration)) +
                if (thrown != 0) {
                    slideOutHorizontally(Motion.tween(duration)) { it * thrown }
                } else {
                    slideOutVertically(Motion.tween(duration)) { it / 2 }
                },
    ) {
        if (action == null) {
            Text(
                entry.message,
                style = AppTypography.body.strong,
                color = colors.accent,
                textAlign = TextAlign.Center,
                modifier = container.padding(horizontal = 16.dp, vertical = 11.dp),
            )
        } else {
            Row(
                container.padding(start = 16.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.message,
                    style = AppTypography.body.strong,
                    color = colors.accent,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ToastActionButton(
                    label = action.label,
                    color = colors.accent,
                    remaining = { remaining.value },
                    onClick = {
                        // Undo first, then close: the producer commits on close, and by then there is
                        // nothing left pending to commit.
                        action.onAction()
                        latestClose()
                    },
                )
            }
        }
    }
}

/** 撤销, behind a ring that empties as the window runs out. */
@Composable
private fun ToastActionButton(
    label: String,
    color: Color,
    remaining: () -> Float,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .pressable(onClick = onClick)
            .touchTarget()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = color.copy(alpha = color.alpha * 0.25f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke),
                    )
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * remaining().coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                },
        )
        Text(label, style = AppTypography.body.strong, color = color)
    }
}
