package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * One overlay material for the whole app, in one position.
 *
 * Before this existed each surface invented its own: a stock Material `AlertDialog`
 * (an opaque M3 surface that ignores the palette entirely), a hand-rolled anchored
 * menu, and a bottom option list — three different radii, scrims and entrances for
 * what the user reads as the same kind of interruption. [GlassDialog] is now the only
 * shape an overlay may take outside the player.
 *
 * It used to be two: a centred dialog for decisions and a bottom sheet for picking one
 * value out of a list, on the reasoning that a reversible choice belongs within thumb
 * reach. The split did not survive contact with where these are opened from — a switcher
 * chip at the top of a hero, a 更多 button in a detail page's action dock, an invite that
 * arrives over the whole app — and answering any of them from the bottom edge sent the
 * eye to the far end of the screen and back. One position, always centred, is what the
 * app settled on.
 *
 * The player is the exception to the rule, not to the material: its 一起看 and control-
 * request modals are [GlassDialog]s like everywhere else, but its own chrome — the
 * settings panel, the episode drawer, the skip pill — stays anchored to the edges. It is
 * landscape and owns the whole screen, so those edges are where the thumbs already are,
 * and a centred panel there would cover the picture it is describing.
 */
private val ScrimColor = Color(0xFF0A0E16)

/** 覆盖层圆角走 §8.4 的「大」档，与 sheet、迷你播放器、tab bar 同级. */
private val OverlayShape = GlassShapes.card

/**
 * How many overlays are on screen right now, so the app shell can stand its own floating
 * furniture down while one is up.
 *
 * An overlay is composed by the screen that opens it, which sits *below* the floating tab
 * bar and mini player in the shell's stacking order. Its scrim therefore covered the page
 * but not the bar, which kept painting — and kept taking taps — on top of a bottom sheet
 * anchored to the same edge. Counted rather than a boolean because a sheet can hand off to
 * a confirmation dialog, and the second one must not clear the flag the first still needs.
 */
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

/** Null outside the app shell — the player owns the whole screen and has no bar to yield. */
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

/**
 * Centred modal. Use for decisions and forms — anything the user must answer before
 * carrying on. Tapping the scrim dismisses; taps inside the panel never leak through.
 *
 * Lifted into a platform [Dialog] window so its `Center` alignment is the real screen
 * centre even when the call site is nested inside a scrolling container (e.g. the
 * migration tools row in 「我的」 lives inside a `LazyColumn` with status-bar and
 * tab-bar insets, which would otherwise centre the dialog in the truncated viewport
 * rather than on the screen).
 */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The dismissal the *caller* asked for, held until the exit animation has played.
    //
    // A platform [Dialog] leaves the composition the instant its state flips, so an overlay
    // whose entrance was a 400ms 46dp rise left the screen in a single frame. Nothing in the
    // app looked less finished: every modal in it opened beautifully and then blinked out.
    // The window now outlives its own dismissal by exactly one animation.
    var leaving by remember { mutableStateOf(false) }
    // Remembered so the identity is stable: it is handed down a static local and used as a
    // `pointerInput` key, and a fresh lambda per recomposition would invalidate both.
    val requestDismiss = remember { { leaving = true } }

    Dialog(
        // System back stays on Compose Dialog's official dismissal path. Delaying it for the
        // app's own exit tween would append a second animation after predictive back commits.
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ReportOverlayVisible()
        val palette = LocalPalette.current
        val modalOffset = with(LocalDensity.current) { Motion.modalOffset.toPx() }
        // 覆盖（播放器 / 菜单）— 下方 46px 上滑, §3.1. The overlay used to borrow the tab
        // switch's 260ms and a 0.94 scale, which is the one transition in the spec that is
        // explicitly *not* for things that cover the page.
        val progress = rememberOverlayTransition(leaving = leaving, onLeft = onDismiss)
        // Everything inside gets the animated way out, so 取消 and 关闭 leave the same way
        // the scrim does — see [LocalOverlayDismiss].
        CompositionLocalProvider(LocalOverlayDismiss provides requestDismiss) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress() }
                    .background(ScrimColor.copy(alpha = 0.46f))
                    .pointerInput(requestDismiss) { detectTapGestures { requestDismiss() } },
                contentAlignment = Alignment.Center,
            ) {
                val panelScrollState = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxWidth()
                        // The window is the whole display — system bars, cutout and all —
                        // and in the player it is a short landscape one. Laid out against
                        // the display rather than the part of it that can be seen, anything
                        // with more than a few rows in it ran off the top and bottom edges:
                        // 一起看 shows a room code, a control-mode switch and a card per
                        // participant, and with the keyboard up there is barely a third of
                        // a landscape screen left to put them in. [safeDrawingPadding]
                        // covers the IME too, which is why there is no separate
                        // `imePadding` here any more.
                        .safeDrawingPadding()
                        .padding(horizontal = 26.dp, vertical = 20.dp)
                        .graphicsLayer {
                            val entered = progress()
                            alpha = entered
                            translationY = modalOffset * (1f - entered)
                        }
                        .shadow(Shadows.sheet, OverlayShape)
                        // 液态玻璃, like everything else that floats.
                        //
                        // This was an opaque `Color.White` / `#111A29` slab — the one surface
                        // in an app built entirely out of translucent material that was not
                        // made of it, and the one users look at longest while deciding
                        // something. The dialog sits in its own window, so there is no in-app
                        // backdrop to sample; the body ramp and specular of [liquidGlass] over
                        // the scrim are what carry the material here, and the scrim is what
                        // keeps the copy legible without an opaque fill.
                        .liquidGlass(
                            shape = OverlayShape,
                            fill = if (palette.isDark) {
                                Color(0xFF111A29).copy(alpha = 0.94f)
                            } else {
                                Color.White.copy(alpha = 0.94f)
                            },
                            border = palette.border,
                            over = ScrimColor,
                        )
                        // Swallow taps so the scrim's dismiss gesture stops at the panel edge.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier)
                        .padding(18.dp)
                        // The panel wraps its content until there is no more room, and then
                        // scrolls instead of growing past the screen. Callers used to cap
                        // themselves at a fixed height — 420dp, 460dp — which is a number
                        // taller than the landscape screen those dialogs also open on.
                        .then(
                            if (scrollable) {
                                Modifier.verticalScroll(panelScrollState)
                            } else {
                                Modifier
                            },
                        ),
                    content = content,
                )
            }
        }
    }
}

/**
 * The animated way out of the overlay currently on screen, or null outside one.
 *
 * An overlay's own buttons hold the caller's `onDismiss`, which tears the window down on the
 * spot. Routing them through this instead means 取消 and 关闭 play the same exit as a tap on
 * the scrim, rather than the panel vanishing under the finger while the scrim behind it
 * fades politely.
 */
private val LocalOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * [fallback], unless an overlay is up and has an exit animation to play first.
 *
 * Call it for the *dismissing* half of an overlay only. A confirm button runs an action and
 * the action decides what happens to the overlay; only 取消, 关闭 and the scrim are simply
 * leaving.
 */
@Composable
fun overlayDismiss(fallback: () -> Unit): () -> Unit = LocalOverlayDismiss.current ?: fallback

/**
 * Drives an overlay in and back out again, and calls [onLeft] once it has gone.
 *
 * Entrance and exit are the same 46dp rise on the same curve, played in opposite directions.
 * The exit is deliberately the shorter of the two — [Motion.POP]'s reasoning applies here as
 * well: arriving is worth watching, leaving is worth getting out of the way.
 *
 * Instant in both directions under 减弱动态效果, in which case [onLeft] still fires, just on
 * the next frame.
 */
@Composable
private fun rememberOverlayTransition(leaving: Boolean, onLeft: () -> Unit): () -> Float {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val target = if (shown && !leaving) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = when {
                reduceMotion -> 0
                leaving -> Motion.POP
                else -> Motion.MODAL
            },
            easing = Motion.Curve,
        ),
        // Fired by the animation itself rather than by a parallel delay, so the window is
        // torn down on the frame the panel finishes leaving — never before it, and never a
        // few frames after it.
        finishedListener = { if (leaving) onLeft() },
        label = "overlayTransition",
    )
    return { progress }
}

/** Title row with an optional subtitle and a close affordance. */
@Composable
fun OverlayHeader(
    title: String,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
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
                modifier = Modifier
                    .pressable(onClick = close)
                    // The chip stays 28dp; the region that answers to it is 44.
                    .touchTarget()
                    .size(28.dp)
                    .flatGlass(CircleShape, palette.card2, palette.border)
                    .padding(8.dp),
            )
        }
    }
}

/** Weight of an overlay button — one primary per overlay, at most one destructive. */
enum class OverlayButtonTone { Primary, Plain, Destructive }

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
    // Same 中 radius it always had; continuous now, like every other control.
    val shape = GlassShapes.chip
    val fill = when {
        tone == OverlayButtonTone.Primary && enabled -> accent.accent
        tone == OverlayButtonTone.Primary -> accent.container
        tone == OverlayButtonTone.Destructive -> palette.errorContainer
        else -> palette.card2
    }
    val border = when (tone) {
        OverlayButtonTone.Primary -> accent.border.copy(alpha = if (enabled) 1f else 0.38f)
        OverlayButtonTone.Destructive -> palette.error
        OverlayButtonTone.Plain -> palette.border
    }
    val ink = when (tone) {
        OverlayButtonTone.Primary -> if (enabled) accent.onAccent else accent.accent
        OverlayButtonTone.Destructive -> palette.error
        OverlayButtonTone.Plain -> palette.text
    }
    Box(
        modifier
            .height(46.dp)
            .pressable(
                enabled = enabled && !loading,
                // The key that commits — 发送, 确定, 删除 — is felt. 取消 is not: backing
                // out of a dialog is not an event worth a buzz.
                haptic = if (tone == OverlayButtonTone.Plain) null else HapticSignal.Confirm,
                onClick = onClick,
            )
            .flatGlass(shape, fill, border),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(17.dp), color = ink, strokeWidth = 2.dp)
        } else {
            Text(
                label,
                style = AppTypography.body.strong,
                color = if (enabled) ink else ink.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}

/** 取消 / 确认 pair, equal width, confirmation on the right. */
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
        // 取消 is purely leaving, so it leaves the way the scrim does. 确定 is not — what it
        // triggers decides whether the overlay closes at all.
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

/**
 * Confirmation modal — a question, its consequence, and two ways out. Replaces the
 * stock `AlertDialog`, which rendered an opaque Material surface on a glass app.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onDismiss) {
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
            onConfirm = onConfirm,
            confirmTone = if (destructive) {
                OverlayButtonTone.Destructive
            } else {
                OverlayButtonTone.Primary
            },
        )
    }
}

/** One selectable row inside a [GlassDialog]. */
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
    val fill = when {
        destructive -> palette.errorContainer
        selected -> accent.container
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            // Every picker row in the app comes through here — 排序, 播放器内核, 标记已看.
            .pressable(
                haptic = if (destructive) HapticSignal.Confirm else HapticSignal.Select,
                // A row in a list of choices is a radio button, and saying so is what lets a
                // screen reader announce "已选中" without the checkmark glyph being read as
                // decoration.
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            // 11dp of padding around a 12.5sp line came to roughly 39dp — under the floor,
            // and these rows are stacked, so a miss lands on the neighbouring choice rather
            // than on nothing.
            .heightIn(min = MinTouchTarget)
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = if (selected || destructive) {
                    AppTypography.body.strong
                } else {
                    AppTypography.body.medium
                },
                color = when {
                    destructive -> palette.error
                    selected -> accent.accent
                    else -> palette.text
                },
                maxLines = 1,
            )
            if (description != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(AppIcons.Check, null, tint = accent.accent, modifier = Modifier.size(13.dp))
        }
    }
}
