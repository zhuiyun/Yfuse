package com.yfuse.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Media3 construction, controls and engine-owned jobs share its main application looper.
 * Keep the owner's Job so leaving the composition still cancels every engine job. Individual
 * disk/network preparations may switch dispatchers without moving the player itself.
 */
internal fun CoroutineScope.playbackEngineScope(): CoroutineScope =
    CoroutineScope(coroutineContext + Dispatchers.Main.immediate)
