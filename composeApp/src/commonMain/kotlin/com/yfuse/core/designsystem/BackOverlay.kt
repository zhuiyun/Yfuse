package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Predictive return moves the overlay; cancellation restores the same content without dismissing it. */
@Composable
fun BackOverlay(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (enabled) ReportOverlayVisible()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val currentBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    val overlayShape = remember { RoundedCornerShape(16.dp) }
    val settling = remember { arrayOfNulls<Job>(1) }
    PlatformPredictiveBackHandler(
        enabled = enabled,
        onProgress = {
            settling[0]?.cancel()
            progress = if (reduceMotion) 0f else it.coerceIn(0f, 1f)
        },
        onBack = {
            settling[0]?.cancel()
            currentBack()
        },
        onCancel = {
            settling[0]?.cancel()
            settling[0] =
                scope.launch {
                    if (reduceMotion) {
                        progress = 0f
                    } else {
                        animate(progress, 0f, animationSpec = Motion.settle()) { value, _ -> progress = value }
                    }
                }
        },
    )
    Box(
        modifier.fillMaxSize().graphicsLayer {
            val p = if (reduceMotion) 0f else progress.coerceIn(0f, 1f)
            scaleX = 1f - 0.10f * p
            scaleY = scaleX
            alpha = 1f - 0.2f * p
            translationX = 24.dp.toPx() * p
            shape = overlayShape
            clip = p > 0f
        },
        content = content,
    )
}

/**
 * A full-screen page drawn over the current one — 全部剧集, 查看全部 — that arrives and leaves
 * like a pushed route instead of appearing and vanishing in a single frame.
 *
 * Shown while [value] is non-null. When it turns null the page keeps drawing the last value while
 * it leaves, so a predictive back released at 0.9 carries on from there rather than cutting away.
 * [content] must draw from the value it is handed, not from the owner's state.
 *
 * It goes back the way a page from a poster does (跟手返回): pulled down from its top, or swiped
 * from the side, it follows the finger, and let go past the point of no return it flies back into
 * [source] — the shelf or rail it was opened from — or, with none on screen, leaves the ordinary way.
 *
 * The page underneath is still composed; stop anything there that moves on its own — a carousel's
 * auto-advance — while this is up.
 */
@Composable
fun <T : Any> OverlayPage(
    value: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    source: ZoomBackAnchor? = null,
    content: @Composable BoxScope.(T) -> Unit,
) {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained = value }
    val shown = value ?: retained ?: return
    val visible = value != null
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val presence = remember { Animatable(if (reduceMotion) 1f else 0f) }
    // Set when the page has already flown back into its source: there is nothing left to leave.
    val zoomedAway = remember { booleanArrayOf(false) }
    LaunchedEffect(visible, reduceMotion) {
        val target = if (visible) 1f else 0f
        if (visible) zoomedAway[0] = false
        if (reduceMotion || (!visible && zoomedAway[0])) {
            presence.snapTo(target)
        } else {
            presence.animateTo(target, Motion.tween(if (visible) Motion.PUSH else Motion.POP))
        }
        if (!visible) retained = null
    }
    ZoomBackOverlay(
        visible = visible,
        source = source,
        onBack = onBack,
        onZoomedAway = { zoomedAway[0] = true },
        modifier =
            modifier.graphicsLayer {
                val p = presence.value
                alpha = p
                translationX = (1f - p) * OverlayPageTravel.toPx()
            },
    ) { content(shown) }
}

/** How far an [OverlayPage] travels in from the trailing edge — the route push's own distance. */
private val OverlayPageTravel = 30.dp
