package com.yfuse.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.cancellation.CancellationException

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * `enableOnBackInvokedCallback` is set in the manifest, which is what makes the framework
 * deliver progress at all. Below Android 14 the flow completes without emitting and this
 * degrades to exactly what [PlatformBackHandler] already did — a single committed back —
 * which is the correct behaviour there, since those versions have no peek to show.
 */
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    // The handler body outlives a recomposition, so the callbacks are read through state
    // rather than captured — otherwise a gesture in flight keeps calling into the lambdas
    // that existed when it started.
    val progress by rememberUpdatedState(onProgress)
    val cancel by rememberUpdatedState(onCancel)
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event -> progress(event.progress) }
            back()
        } catch (cancellation: CancellationException) {
            cancel()
            // Rethrown so the coroutine machinery still sees the cancellation it raised;
            // swallowing it here leaks the gesture's scope.
            throw cancellation
        }
    }
}
