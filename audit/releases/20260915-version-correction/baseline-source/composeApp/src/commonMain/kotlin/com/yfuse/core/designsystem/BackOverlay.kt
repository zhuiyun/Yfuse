package com.yfuse.core.designsystem

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
