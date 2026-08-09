package com.yfuse.core.designsystem

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fallback back handler for screens that only need to react after back commits.
 *
 * Use the predictive API even when the caller does not draw progress itself. This keeps every
 * remaining local page/modal on Android's predictive-back dispatch path instead of registering
 * a legacy commit-only Compose BackHandler. Screens that own a visual transition use
 * [PlatformPredictiveBackHandler] below and consume progress explicitly.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        // Collect the gesture so cancellation remains cancellation. The caller deliberately has
        // no progress surface; completion is the only state change it asked for.
        events.collect { }
        back()
    }
}

/**
 * `enableOnBackInvokedCallback` is set in the manifest, which is what makes the framework
 * deliver progress at all. Below Android 14 the flow completes without emitting and this
 * degrades to a single committed back, which is the correct behaviour there because those
 * versions have no in-app preview progress to show.
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
