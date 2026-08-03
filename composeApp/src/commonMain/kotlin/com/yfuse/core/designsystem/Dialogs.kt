package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
private fun ReportOverlayVisible() {
    val visibility = LocalOverlayVisibility.current ?: return
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
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ReportOverlayVisible()
        val palette = LocalPalette.current
        val solidSurface = if (palette.isDark) Color(0xFF111A29) else Color.White
        val progress = rememberOverlayEntrance(Motion.TAB)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() }
                .background(ScrimColor.copy(alpha = 0.46f))
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
                    .imePadding()
                    .graphicsLayer {
                        val scale = 0.94f + 0.06f * progress()
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(Shadows.sheet, OverlayShape)
                    .clip(OverlayShape)
                    .background(solidSurface)
                    .border(1.dp, palette.border, OverlayShape)
                    // Swallow taps so the scrim's dismiss gesture stops at the panel edge.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(modifier)
                    .padding(18.dp),
                content = content,
            )
        }
    }
}

/** Fade/scale driver for the overlay entrance; instant under 减弱动态效果. */
@Composable
private fun rememberOverlayEntrance(durationMillis: Int): () -> Float {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else durationMillis,
            easing = Motion.Curve,
        ),
        label = "overlayEntrance",
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
            Text(title, style = sc(15f, 700), color = palette.text, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = mr(10.5f, 400),
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClose != null) {
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = palette.sub2,
                modifier = Modifier
                    .size(28.dp)
                    .pressable(onClick = onClose)
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
    val shape = RoundedCornerShape(Dimens.medium)
    val fill = when {
        tone == OverlayButtonTone.Primary && enabled -> Brand.Primary.copy(alpha = 0.72f)
        tone == OverlayButtonTone.Primary -> Brand.Primary.copy(alpha = 0.30f)
        tone == OverlayButtonTone.Destructive -> Brand.Danger.copy(alpha = 0.12f)
        else -> palette.card2
    }
    val border = when (tone) {
        OverlayButtonTone.Primary -> Color.White.copy(alpha = if (enabled) 0.36f else 0.18f)
        OverlayButtonTone.Destructive -> Brand.Danger.copy(alpha = 0.32f)
        OverlayButtonTone.Plain -> palette.border
    }
    val ink = when (tone) {
        OverlayButtonTone.Primary -> Color.White
        OverlayButtonTone.Destructive -> Brand.Danger
        OverlayButtonTone.Plain -> palette.text
    }
    Box(
        modifier
            .height(46.dp)
            .pressable(enabled = enabled && !loading, onClick = onClick)
            .flatGlass(shape, fill, border),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(17.dp), color = ink, strokeWidth = 2.dp)
        } else {
            Text(
                label,
                style = sc(13f, 700),
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
        OverlayButton(dismissLabel, onDismiss, Modifier.weight(1f))
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
        Text(title, style = sc(15f, 700), color = palette.text)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = sc(12.5f, 400, lineHeight = 12.5f * 1.6f),
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
    val fill = when {
        destructive -> Brand.Danger.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = sc(12.5f, if (selected || destructive) 700 else 500),
                color = when {
                    destructive -> Brand.Danger
                    selected -> Brand.Primary
                    else -> palette.text
                },
                maxLines = 1,
            )
            if (description != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    style = mr(10f, 400),
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(AppIcons.Check, null, tint = Brand.Primary, modifier = Modifier.size(13.dp))
        }
    }
}
