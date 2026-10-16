package com.yfuse.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val visibility = rememberRouteVisibility()
    val latestEnabled by rememberUpdatedState(enabled)
    BackHandler(enabled = enabled && visibility.value) {
        if (latestEnabled && visibility.value) onBack()
    }
}

@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val visibility = rememberRouteVisibility()
    val latestEnabled by rememberUpdatedState(enabled)
    val disabledGeneration = remember { intArrayOf(0) }
    SideEffect {
        if (!enabled || !visibility.value) disabledGeneration[0]++
    }
    PredictiveBackHandler(enabled = enabled && visibility.value) { events ->
        val generation = disabledGeneration[0]
        try {
            var accepted = latestEnabled && visibility.value
            events.collect { event ->
                accepted = accepted && latestEnabled && visibility.value && generation == disabledGeneration[0]
                if (accepted) onProgress(event.progress.coerceIn(0f, 1f))
            }
            // A page can hide and reopen between progress events; that still cancels the old gesture.
            if (accepted && latestEnabled && visibility.value && generation == disabledGeneration[0]) {
                onBack()
            } else {
                onCancel()
            }
        } catch (cancelled: CancellationException) {
            onCancel()
            throw cancelled
        }
    }
}

@Composable
actual fun PlatformNavigationBackHandler(
    enabled: Boolean,
    onStarted: (NavigationEvent) -> Unit,
    onProgressed: (NavigationEvent) -> Unit,
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
) {
    val visibility = rememberRouteVisibility()
    val latestEnabled by rememberUpdatedState(enabled)
    val started by rememberUpdatedState(onStarted)
    val progressed by rememberUpdatedState(onProgressed)
    val completed by rememberUpdatedState(onCompleted)
    val cancelled by rememberUpdatedState(onCancelled)
    val disabledGeneration = remember { intArrayOf(0) }
    SideEffect { if (!enabled || !visibility.value) disabledGeneration[0]++ }
    // Register with Activity's dispatcher, not just NavDisplay's scene dispatcher. This
    // supplies WindowManager's callback as well as the progress stream used by the page.
    PredictiveBackHandler(enabled = enabled && visibility.value) { events ->
        val generation = disabledGeneration[0]
        var first = true
        var accepted = latestEnabled && visibility.value
        try {
            events.collect { event ->
                accepted = accepted && latestEnabled && visibility.value && generation == disabledGeneration[0]
                if (accepted) {
                    val navigationEvent =
                        NavigationEvent(
                            touchX = event.touchX,
                            touchY = event.touchY,
                            progress = event.progress.coerceIn(0f, 1f),
                            swipeEdge = event.swipeEdge,
                        )
                    if (first) {
                        first = false
                        started(navigationEvent)
                    }
                    progressed(navigationEvent)
                }
            }
            if (accepted && latestEnabled && visibility.value && generation == disabledGeneration[0]) {
                completed()
            } else {
                cancelled()
            }
        } catch (error: CancellationException) {
            cancelled()
            throw error
        }
    }
}
