package com.yfuse.core.designsystem

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/** AndroidX's documented Flow<BackEventCompat> integration, without a second animation clock. */
@Composable
actual fun PlatformBackGestureHandler(
    enabled: Boolean,
    onProgress: (Float, BackGestureEdge) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val currentOnProgress = rememberUpdatedState(onProgress)
    val currentOnCancel = rememberUpdatedState(onCancel)
    val currentOnBack = rememberUpdatedState(onBack)

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                currentOnProgress.value(
                    event.progress.coerceIn(0f, 1f),
                    if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) {
                        BackGestureEdge.Right
                    } else {
                        BackGestureEdge.Left
                    },
                )
            }
            currentOnBack.value()
        } catch (cancelled: CancellationException) {
            currentOnCancel.value()
            throw cancelled
        }
    }
}
