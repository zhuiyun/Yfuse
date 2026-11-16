package com.yfuse.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Cached-content age only needs a clock while its page is actually visible. */
@Composable
internal fun rememberLibraryFreshnessTime(
    active: Boolean,
    updatedAtEpochMs: Long?,
    intervalMs: Long = 60_000L,
    now: () -> Long = System::currentTimeMillis,
): State<Long> {
    val time = remember { mutableStateOf(now()) }
    val currentClock by rememberUpdatedState(now)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, updatedAtEpochMs, lifecycle, intervalMs) {
        if (!active) return@LaunchedEffect
        // Route visibility alone stays true when Android backgrounds the activity.
        // Cancel the delay itself, and refresh immediately when the page returns.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                time.value = currentClock()
                delay(intervalMs)
            }
        }
    }
    return time
}
