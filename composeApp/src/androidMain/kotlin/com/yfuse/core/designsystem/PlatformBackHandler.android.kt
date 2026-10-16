package com.yfuse.core.designsystem

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    val context = LocalContext.current
    val activity = remember(context) { context.findBackActivity() }
    val owner = LocalOnBackPressedDispatcherOwner.current ?: checkNotNull(activity)
    val callback =
        remember(owner, visibility) {
            NavigationBackCallback(
                acceptsBack = {
                    latestEnabled && visibility.value && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                },
                onStarted = { started(it) },
                onProgressed = { progressed(it) },
                onCompleted = { completed() },
                onCancelled = { cancelled() },
            )
        }
    // Subscribe in composition so hiding a route cancels its gesture before another event.
    val callbackEnabled = enabled && visibility.value
    SideEffect { callback.updateEnabled(callbackEnabled) }
    // The window owns this bridge, even when a NavDisplay entry provides a private scene
    // dispatcher and lifecycle. Keep a dialog owner when present; visibility gates the route.
    DisposableEffect(owner, callback) {
        owner.onBackPressedDispatcher.addCallback(owner, callback)
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    callback.invalidateGesture()
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            callback.invalidateGesture()
            callback.remove()
            owner.lifecycle.removeObserver(observer)
        }
    }
}

private tailrec fun Context.findBackActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findBackActivity()
        else -> null
    }
