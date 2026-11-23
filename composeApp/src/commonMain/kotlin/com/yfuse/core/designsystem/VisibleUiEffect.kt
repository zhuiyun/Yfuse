package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/** Retained navigation entries must not keep page-only timers running behind a stopped activity. */
@Composable
internal fun VisibleUiEffect(
    key: Any?,
    active: Boolean = true,
    block: suspend CoroutineScope.() -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible = LocalRouteVisible.current && active
    val latestBlock by rememberUpdatedState(block)
    LaunchedEffect(key, lifecycle, visible) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { latestBlock() }
    }
}
