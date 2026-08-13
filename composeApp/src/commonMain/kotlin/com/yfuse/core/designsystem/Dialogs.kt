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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val ScrimColor = Color(0xFF0A0E16)
private val OverlayShape = GlassShapes.card
private val OverlayMaxWidth = 560.dp
private val OverlayMotionOffset = 32.dp
internal const val OverlayExitDurationMs = 200

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

/** The one centred modal material used outside player chrome. */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    liquidButtons: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var leaving by remember { mutableStateOf(false) }
    val requestDismiss = remember { { leaving = true } }

    Dialog(
        onDismissRequest = requestDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        ReportOverlayVisible()
        val palette = LocalPalette.current
        val modalOffset = with(LocalDensity.current) { OverlayMotionOffset.toPx() }
        val progress = rememberOverlayTransition(leaving = leaving, onLeft = onDismiss)
        CompositionLocalProvider(
            LocalOverlayDismiss provides requestDismiss,
            LocalOverlayLiquidButtons provides liquidButtons,
        ) {
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
                        .safeDrawingPadding()
                        .padding(horizontal = 26.dp, vertical = 20.dp)
                        .widthIn(max = OverlayMaxWidth)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val entered = progress()
                            alpha = entered
                            translationY = modalOffset * (1f - entered)
                        }
                        .shadow(Shadows.sheet, OverlayShape)
                        .liquidGlass(
                            shape = OverlayShape,
                            fill =
                                if (palette.isDark) {
                                    Color(0xFF111A29).copy(alpha = 0.94f)
                                } else {
                                    Color.White.copy(alpha = 0.94f)
                                },
                            border = palette.border,
                            over = ScrimColor,
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier)
                        .padding(18.dp)
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

private val LocalOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }
private val LocalOverlayLiquidButtons = staticCompositionLocalOf { true }

@Composable
fun overlayDismiss(fallback: () -> Unit): () -> Unit = LocalOverlayDismiss.current ?: fallback

@Composable
private fun rememberOverlayTransition(
    leaving: Boolean,
    onLeft: () -> Unit,
): () -> Float {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val target = if (shown && !leaving) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec =
            tween(
                durationMillis = overlayDurationMillis(leaving, reduceMotion),
                easing = Motion.Curve,
            ),
        finishedListener = { if (leaving) onLeft() },
        label = "overlayTransition",
    )
    return { progress }
}

internal fun overlayDurationMillis(
    leaving: Boolean,
    reduceMotion: Boolean,
): Int =
    when {
        reduceMotion -> 0
        leaving -> OverlayExitDurationMs
        else -> Motion.MODAL
    }

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
                        )
                        .padding(8.dp),
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
    val shape = GlassShapes.chip
    val fill =
        when (tone) {
            OverlayButtonTone.Primary -> palette.glassStrong
            OverlayButtonTone.Destructive -> palette.glassStrong
            OverlayButtonTone.Plain -> palette.card2
        }
    val border =
        when (tone) {
            OverlayButtonTone.Primary -> accent.border.copy(alpha = if (enabled) 1f else 0.38f)
            OverlayButtonTone.Destructive -> palette.error
            OverlayButtonTone.Plain -> palette.border
        }
    val ink =
        when (tone) {
            OverlayButtonTone.Primary -> accent.accent
            OverlayButtonTone.Destructive -> palette.error
            OverlayButtonTone.Plain -> palette.text
        }
    val surface =
        if (LocalOverlayLiquidButtons.current) {
            Modifier.liquidGlass(
                shape = shape,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (tone == OverlayButtonTone.Plain) 0.62f else 0.82f,
            )
        } else {
            Modifier.flatGlass(shape, fill, border)
        }
    Box(
        modifier
            .height(46.dp)
            .pressable(
                enabled = enabled && !loading,
                haptic = if (tone == OverlayButtonTone.Plain) null else HapticSignal.Confirm,
                onClick = onClick,
            )
            .then(surface),
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
            onConfirm = onConfirm,
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

/** Selectable rows use the same neutral liquid body as buttons. */
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
    val fill = palette.card2
    val border =
        when {
            destructive -> palette.error.copy(alpha = 0.72f)
            selected -> accent.border
            else -> palette.border
        }
    val ink =
        when {
            destructive -> palette.error
            selected -> accent.accent
            else -> palette.text
        }
    Row(
        modifier
            .fillMaxWidth()
            .pressable(
                haptic = if (destructive) HapticSignal.Confirm else HapticSignal.Select,
                role = Role.RadioButton,
                focusShape = GlassShapes.chip,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .heightIn(min = MinTouchTarget)
            .then(
                if (LocalOverlayLiquidButtons.current) {
                    Modifier.liquidGlass(
                        shape = GlassShapes.chip,
                        fill = fill,
                        border = border,
                        over = palette.background,
                        sheen = if (selected || destructive) 0.72f else 0.62f,
                    )
                } else {
                    Modifier.flatGlass(GlassShapes.chip, fill, border)
                },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
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
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
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
