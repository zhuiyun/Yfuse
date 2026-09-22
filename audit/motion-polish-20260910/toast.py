from edit import read,write
p='composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Toast.kt'
write(p,'''package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val TOAST_MS = 2_600L
private const val MAX_TOASTS = 3

private class ToastEntry(val message: String, val accent: Color?) {
    var visible by mutableStateOf(false)
}

/** Bounded, independently timed feedback. Only the latest notice may clear the producer's state. */
@Composable
fun BoxScope.ActionToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val entries = remember { mutableStateListOf<ToastEntry>() }
    val latestMessage by rememberUpdatedState(message)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestEntry = remember { arrayOfNulls<ToastEntry>(1) }
    val duration = if (LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current) 0 else Motion.TAB
    LaunchedEffect(message) {
        if (message == null) {
            entries.forEach { it.visible = false }
        } else {
            // A newer copy replaces an older duplicate instead of occupying two slots.
            entries.removeAll { it.message == message }
            while (entries.size >= MAX_TOASTS) entries.removeAt(0)
            val entry = ToastEntry(message, accent)
            latestEntry[0] = entry
            entries.add(entry)
        }
    }
    Column(
        modifier.align(Alignment.BottomCenter).motionAwareAnimateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { entry ->
            key(entry) {
                LaunchedEffect(entry) { entry.visible = true }
                ActionToastEntry(entry, duration, onClose = {
                    entry.visible = false
                    if (latestEntry[0] === entry && latestMessage == entry.message) latestDismiss()
                }, onGone = { entries.remove(entry) })
            }
        }
    }
}

@Composable
private fun ActionToastEntry(entry: ToastEntry, duration: Int, onClose: () -> Unit, onGone: () -> Unit) {
    val palette = LocalPalette.current
    val themeAccent = LocalAccentColors.current
    val colors = remember(entry.accent, palette.isDark, themeAccent) {
        entry.accent?.let { resolveAccentColors(it, palette.isDark) } ?: themeAccent
    }
    val accessibility = LocalAccessibilityManager.current
    val latestClose by rememberUpdatedState(onClose)
    val latestGone by rememberUpdatedState(onGone)
    var appeared by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    val animatedOffset = animateFloatAsState(offset, if (dragging || duration == 0) snap() else Motion.settle(), label = "toast-drag")
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(entry.visible, dragging, accessibility) {
        if (entry.visible) {
            appeared = true
            if (!dragging) {
                val recommended = accessibility?.calculateRecommendedTimeoutMillis(
                    TOAST_MS, containsText = true, containsControls = true,
                ) ?: TOAST_MS
                delay(maxOf(TOAST_MS, recommended))
                latestClose()
            }
        } else if (appeared) {
            delay(duration.toLong())
            latestGone()
        }
    }
    AnimatedVisibility(
        visible = entry.visible,
        enter = fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = Motion.Curve)) { it / 2 },
        exit = fadeOut(tween(duration)) + slideOutVertically(tween(duration, easing = Motion.Curve)) { it / 2 },
    ) {
        Text(
            entry.message,
            style = AppTypography.body.strong,
            color = colors.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal)
                .graphicsLayer {
                    translationX = animatedOffset.value
                    alpha = (1f - abs(animatedOffset.value) / (threshold * 2f)).coerceIn(0.25f, 1f)
                }
                .draggable(
                    state = rememberDraggableState { offset += it }, orientation = Orientation.Horizontal,
                    enabled = entry.visible,
                    onDragStarted = { dragging = true },
                    onDragStopped = { velocity ->
                        dragging = false
                        if (abs(offset) >= threshold || (abs(offset) > threshold / 4f && abs(velocity) > threshold * 8f)) latestClose()
                        else offset = 0f
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss { latestClose(); true }
                }
                .pressable(enabled = entry.visible, onClickLabel = "关闭提示", onClick = onClose)
                .touchTarget()
                .shadow(Shadows.tabBar, GlassShapes.chip)
                .solidGlass(GlassShapes.chip, colors.container, colors.border)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}
''')
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/watch/WatchInviteSheet.kt'
write(p,read(p).replace('motionContentSize','motionAwareAnimateContentSize'))
